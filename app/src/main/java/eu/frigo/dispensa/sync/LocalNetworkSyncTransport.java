package eu.frigo.dispensa.sync;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.frigo.dispensa.R;
import eu.frigo.dispensa.util.DebugLogger;

/**
 * {@link SyncTransport} implementation that uses Android {@link NsdManager} (mDNS / DNS-SD)
 * for peer discovery and plain TCP sockets for bidirectional change-blob exchange.
 *
 * <h3>Protocol</h3>
 * When two Dispensa instances are on the same Wi-Fi network:
 * <ol>
 *   <li>Each instance registers an {@code _dispensa._tcp} NSD service pointing to its local
 *       TCP server socket.</li>
 *   <li>The device that initiates sync (the WorkManager worker) acts as the <em>client</em>:
 *       it connects to the peer, sends its change-blob (4-byte big-endian length + bytes),
 *       then reads the peer's change-blob back.</li>
 *   <li>The <em>server</em> (the TCP accept-loop running in a background thread) reads the
 *       client's blob, applies it via {@link SyncManager#importChanges}, then sends its own
 *       blob back.</li>
 * </ol>
 *
 * <h3>Lifecycle</h3>
 * Call {@link #start()} before use and {@link #stop()} when done (e.g. at the end of
 * {@code SyncWorker.doWork()}).
 *
 * <h3>Self-detection</h3>
 * When the NSD resolver returns a service whose port matches the local server port the entry
 * is silently discarded — this prevents a device from syncing with itself.
 */
public class LocalNetworkSyncTransport implements SyncTransport {

    private static final String TAG = "LocalNetworkSyncTransport";

    /** DNS-SD service type for all Dispensa instances (trailing dot required). */
    static final String SERVICE_TYPE = "_dispensa._tcp.";

    /** Human-readable service name; NSD will append a numeric suffix on collision. */
    static final String SERVICE_NAME = "DispensaSync";

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Maximum accepted blob size (16 MiB) to guard against malicious peers. */
    private static final int MAX_BLOB_SIZE_BYTES = 16 * 1024 * 1024;

    /** Notification channel ID for sync device approval requests. */
    public static final String CHANNEL_ID_SYNC_DEVICE = "SYNC_DEVICE_CHANNEL";

    /** Notification ID used for the pending-device approval notification. */
    private static final int NOTIFICATION_ID_SYNC_DEVICE = 2;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final Context context;  // application context; may be null in unit tests
    private final NsdManager nsdManager;
    private final WifiManager.MulticastLock multicastLock;  // may be null in tests
    private final SyncManager syncManager;
    private final SyncPermissionManager permissionManager;  // may be null (allows all)
    private final ExecutorService executor;

    // ── Runtime state ─────────────────────────────────────────────────────────

    private ServerSocket serverSocket;
    int localPort = 0;  // package-private for tests
    private volatile boolean running = false;

    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    /** Resolved peers (host + port) ready for connection. Thread-safe. */
    final List<NsdServiceInfo> discoveredPeers = new CopyOnWriteArrayList<>();

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Production constructor — obtains {@link NsdManager} and {@link WifiManager} from
     * the system.  Uses a bounded thread pool (max 8 threads) to limit resource usage
     * when multiple peers connect simultaneously.
     */
    public LocalNetworkSyncTransport(Context context, SyncManager syncManager) {
        this(
                context.getApplicationContext(),
                (NsdManager) context.getSystemService(Context.NSD_SERVICE),
                buildMulticastLock(context),
                syncManager,
                Executors.newFixedThreadPool(8),
                new SyncPermissionManager(context)
        );
    }

    /**
     * Package-private constructor for unit tests — allows all dependencies to be injected.
     *
     * @param multicastLock may be {@code null}; the code guards all usages
     */
    LocalNetworkSyncTransport(NsdManager nsdManager,
            WifiManager.MulticastLock multicastLock,
            SyncManager syncManager,
            ExecutorService executor) {
        this(null, nsdManager, multicastLock, syncManager, executor, null);
    }

    /**
     * Full package-private constructor for unit tests — allows all dependencies to be injected.
     *
     * @param multicastLock     may be {@code null}
     * @param permissionManager may be {@code null}; when {@code null} all connections are allowed
     */
    LocalNetworkSyncTransport(NsdManager nsdManager,
            WifiManager.MulticastLock multicastLock,
            SyncManager syncManager,
            ExecutorService executor,
            SyncPermissionManager permissionManager) {
        this(null, nsdManager, multicastLock, syncManager, executor, permissionManager);
    }

    private LocalNetworkSyncTransport(Context context,
            NsdManager nsdManager,
            WifiManager.MulticastLock multicastLock,
            SyncManager syncManager,
            ExecutorService executor,
            SyncPermissionManager permissionManager) {
        this.context = context;
        this.nsdManager = nsdManager;
        this.multicastLock = multicastLock;
        this.syncManager = syncManager;
        this.executor = executor;
        this.permissionManager = permissionManager;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the TCP server socket, registers the NSD service, and begins peer discovery.
     * Also acquires the Wi-Fi multicast lock so mDNS packets are not filtered by the driver.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public void start() throws IOException {
        if (running) return;

        DebugLogger.i(TAG, "start: acquiring multicast lock and opening TCP server socket");
        if (multicastLock != null && !multicastLock.isHeld()) {
            multicastLock.acquire();
        }

        serverSocket = new ServerSocket(0);
        localPort = serverSocket.getLocalPort();
        running = true;
        DebugLogger.i(TAG, "start: TCP server listening on port " + localPort);

        executor.execute(this::acceptLoop);

        registerService(localPort);
        startDiscovery();
    }

    /**
     * Stops the TCP server, releases the multicast lock, and unregisters NSD services.
     * Safe to call multiple times or if {@link #start()} was never called.
     */
    public void stop() {
        DebugLogger.i(TAG, "stop: stopping transport");
        running = false;

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
            registrationListener = null;
        }

        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
            discoveryListener = null;
        }

        discoveredPeers.clear();
    }

    // ── SyncTransport ─────────────────────────────────────────────────────────

    /**
     * Connects to the first discovered peer, sends {@code data} (our change blob), and
     * receives the peer's change blob.  The received blob is delivered via
     * {@link SyncCallback#onSuccess(byte[])}.
     *
     * <p>If no peers have been discovered yet {@code onSuccess(null)} is called immediately —
     * the caller should treat {@code null} as "no changes from peer".
     */
    @Override
    public void push(byte[] data, SyncCallback callback) {
        if (discoveredPeers.isEmpty()) {
            DebugLogger.i(TAG, "push: no peers discovered, calling onSuccess(null)");
            callback.onSuccess(null);
            return;
        }

        NsdServiceInfo peer = discoveredPeers.get(0);
        DebugLogger.i(TAG, "push: connecting to peer " + peer.getHost() + ":" + peer.getPort()
                + ", dataBytes=" + (data == null ? 0 : data.length));
        executor.execute(() -> {
            try {
                byte[] peerBlob = exchangeWithPeer(peer.getHost(), peer.getPort(), data);
                DebugLogger.i(TAG, "push: exchange complete, peerBlobBytes="
                        + (peerBlob == null ? 0 : peerBlob.length));
                callback.onSuccess(peerBlob);
            } catch (IOException e) {
                DebugLogger.e(TAG, "push: failed to exchange with peer " + peer.getHost(), e);
                Log.w(TAG, "Failed to exchange with peer " + peer.getHost(), e);
                callback.onError(e);
            }
        });
    }

    /**
     * Passive operation — incoming peer connections are handled by the TCP accept loop
     * (started in {@link #start()}).  {@link SyncCallback#onSuccess(byte[])} is called
     * immediately with {@code null}.
     */
    @Override
    public void pull(SyncCallback callback) {
        callback.onSuccess(null);
    }

    // ── TCP server (accept loop) ───────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                executor.execute(() -> handleIncomingConnection(client));
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Accept loop error", e);
                }
            }
        }
    }

    private void handleIncomingConnection(Socket client) {
        try (Socket s = client) {
            DebugLogger.i(TAG, "handleIncomingConnection: accepted from "
                    + client.getInetAddress());
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            // Read peer's blob
            int peerBlobLen = in.readInt();
            if (peerBlobLen < 0 || peerBlobLen > MAX_BLOB_SIZE_BYTES) {
                DebugLogger.w(TAG, "handleIncomingConnection: invalid blob size " + peerBlobLen);
                throw new IOException("Invalid blob size from peer: " + peerBlobLen);
            }
            byte[] peerBlob = new byte[peerBlobLen];
            in.readFully(peerBlob);

            // Enforce device trust list when a SyncPermissionManager is present.
            // Devices without a senderDeviceId (e.g. older clients) cannot be identified
            // and are therefore rejected — they must upgrade to be approved.
            if (permissionManager != null) {
                String senderDeviceId = SyncManager.extractSenderDeviceId(peerBlob);
                if (senderDeviceId == null) {
                    DebugLogger.w(TAG, "handleIncomingConnection: rejected — no senderDeviceId");
                    Log.w(TAG, "Rejected sync from unidentifiable device (no senderDeviceId)");
                    writeEmptyBlob(out);
                    return;
                }
                if (!permissionManager.isTrusted(senderDeviceId)) {
                    Set<String> alreadyPending = permissionManager.getPendingDeviceIds();
                    boolean isNew = !alreadyPending.contains(senderDeviceId);
                    permissionManager.markPending(senderDeviceId);
                    DebugLogger.w(TAG, "handleIncomingConnection: rejected untrusted device "
                            + senderDeviceId + " — added to pending");
                    Log.d(TAG, "Rejected sync from untrusted device " + senderDeviceId
                            + " — added to pending list");
                    if (isNew) {
                        postPendingDeviceNotification(context);
                    }
                    writeEmptyBlob(out);
                    return;
                }
                DebugLogger.i(TAG, "handleIncomingConnection: trusted device " + senderDeviceId);
            }

            // Apply peer's changes to local database
            DebugLogger.i(TAG, "handleIncomingConnection: importing " + peerBlobLen + " bytes");
            syncManager.importChanges(peerBlob);

            // Respond with our own changes
            byte[] ourBlob = syncManager.exportChanges(syncManager.getLastSyncVersion());
            DebugLogger.i(TAG, "handleIncomingConnection: responding with " + ourBlob.length
                    + " bytes");
            out.writeInt(ourBlob.length);
            out.write(ourBlob);
            out.flush();

        } catch (IOException e) {
            DebugLogger.e(TAG, "handleIncomingConnection: error", e);
            Log.w(TAG, "Error handling incoming connection", e);
        }
    }

    /** Sends an empty (zero-change) sync blob to the peer so it does not time out. */
    private void writeEmptyBlob(DataOutputStream out) throws IOException {
        byte[] emptyBlob = syncManager.exportChanges(syncManager.getMaxSyncClock());
        out.writeInt(emptyBlob.length);
        out.write(emptyBlob);
        out.flush();
    }

    // ── TCP client (outbound exchange) ────────────────────────────────────────

    private byte[] exchangeWithPeer(java.net.InetAddress host, int port, byte[] ourBlob)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            out.writeInt(ourBlob.length);
            out.write(ourBlob);
            out.flush();

            int peerBlobLen = in.readInt();
            if (peerBlobLen < 0 || peerBlobLen > MAX_BLOB_SIZE_BYTES) {
                throw new IOException("Invalid blob size from peer: " + peerBlobLen);
            }
            byte[] peerBlob = new byte[peerBlobLen];
            in.readFully(peerBlob);
            return peerBlob;
        }
    }

    // ── NSD registration ──────────────────────────────────────────────────────

    /**
     * Registers the local Dispensa service via NSD so that peers on the same network can
     * discover it.  Package-private to allow direct invocation from unit tests.
     *
     * @param port the TCP port the server socket is listening on
     */
    void registerService(int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(SERVICE_NAME);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                DebugLogger.i(TAG, "NSD service registered: " + info.getServiceName());
                Log.d(TAG, "NSD service registered: " + info.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                DebugLogger.w(TAG, "NSD registration failed, error=" + errorCode);
                Log.w(TAG, "NSD registration failed, error=" + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                DebugLogger.i(TAG, "NSD service unregistered");
                Log.d(TAG, "NSD service unregistered");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                DebugLogger.w(TAG, "NSD unregistration failed, error=" + errorCode);
                Log.w(TAG, "NSD unregistration failed, error=" + errorCode);
            }
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    // ── NSD discovery ─────────────────────────────────────────────────────────

    /**
     * Starts NSD service discovery for {@code _dispensa._tcp} peers.
     * Package-private to allow direct invocation from unit tests.
     */
    void startDiscovery() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                DebugLogger.w(TAG, "NSD discovery start failed, error=" + errorCode);
                Log.w(TAG, "Discovery start failed, error=" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                DebugLogger.w(TAG, "NSD discovery stop failed, error=" + errorCode);
                Log.w(TAG, "Discovery stop failed, error=" + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                DebugLogger.i(TAG, "NSD discovery started for " + serviceType);
                Log.d(TAG, "NSD discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                DebugLogger.i(TAG, "NSD discovery stopped for " + serviceType);
                Log.d(TAG, "NSD discovery stopped for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                DebugLogger.i(TAG, "NSD service found: " + serviceInfo.getServiceName());
                Log.d(TAG, "NSD service found: " + serviceInfo.getServiceName());
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String lostName = serviceInfo.getServiceName();
                if (lostName != null) {
                    discoveredPeers.removeIf(p -> lostName.equals(p.getServiceName()));
                }
                DebugLogger.i(TAG, "NSD service lost: " + lostName);
                Log.d(TAG, "NSD service lost: " + lostName);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void resolveService(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                DebugLogger.w(TAG, "NSD resolve failed for " + info.getServiceName()
                        + ", error=" + errorCode);
                Log.w(TAG, "NSD resolve failed for " + info.getServiceName()
                        + ", error=" + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                if (info.getPort() == localPort) {
                    // Resolved to our own service — skip
                    DebugLogger.i(TAG, "Skipping self-resolved NSD service (port=" + localPort + ")");
                    Log.d(TAG, "Skipping self-resolved NSD service (port=" + localPort + ")");
                    return;
                }
                discoveredPeers.add(info);
                DebugLogger.i(TAG, "Peer resolved: " + info.getHost() + ":" + info.getPort());
                Log.d(TAG, "Peer resolved: " + info.getHost() + ":" + info.getPort());
            }
        });
    }

    // ── Public accessors ─────────────────────────────────────────────────────

    /**
     * Returns a snapshot of currently discovered peers.  Safe to call from any thread.
     *
     * <p>Note: the returned list is a copy — changes to it do not affect the internal state.
     * Call this <em>before</em> {@link #stop()} since {@code stop()} clears the peer list.
     */
    public java.util.List<NsdServiceInfo> getDiscoveredPeers() {
        return new java.util.ArrayList<>(discoveredPeers);
    }

    // ── Private factory helpers ───────────────────────────────────────────────

    private static WifiManager.MulticastLock buildMulticastLock(Context context) {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                return wifiManager.createMulticastLock(TAG);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not create MulticastLock", e);
        }
        return null;
    }

    /**
     * Posts a notification informing the user that a new device is waiting for approval to
     * sync over the local network.  Only called the first time a device is seen (not on
     * every repeated connection attempt).
     *
     * @param ctx application context
     */
    private void postPendingDeviceNotification(Context ctx) {
        if (ctx == null) return;
        try {
            // Open SettingsActivity so the user can navigate to Manage trusted devices
            Intent intent = new Intent();
            intent.setClassName(ctx, "eu.frigo.dispensa.activity.SettingsActivity");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            String title = ctx.getString(R.string.notify_sync_device_pending_title);
            String text = ctx.getString(R.string.notify_sync_device_pending_text);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID_SYNC_DEVICE)
                    .setSmallIcon(R.drawable.ic_fridge)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(ctx);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    DebugLogger.w(TAG, "postPendingDeviceNotification: POST_NOTIFICATIONS not granted");
                    return;
                }
            }
            notificationManager.notify(NOTIFICATION_ID_SYNC_DEVICE, builder.build());
            DebugLogger.i(TAG, "postPendingDeviceNotification: posted");
        } catch (Exception e) {
            DebugLogger.e(TAG, "postPendingDeviceNotification: failed", e);
        }
    }
}
