package eu.frigo.dispensa.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import eu.frigo.dispensa.data.AppDatabase;
import eu.frigo.dispensa.sync.DriveTransportFactory;
import eu.frigo.dispensa.sync.LocalNetworkSyncTransport;
import eu.frigo.dispensa.sync.SyncCallback;
import eu.frigo.dispensa.sync.SyncManager;
import eu.frigo.dispensa.sync.SyncTransport;
import eu.frigo.dispensa.util.DebugLogger;

/**
 * WorkManager {@link Worker} that performs a full local-network sync cycle.
 *
 * <ol>
 *   <li>Checks the {@code sync_local_network_enabled} preference — exits early if disabled.</li>
 *   <li>Starts {@link LocalNetworkSyncTransport} (registers NSD service + starts TCP server).</li>
 *   <li>Waits {@value #DISCOVERY_WAIT_MS} ms for mDNS peer discovery to complete.</li>
 *   <li>Exports local changes and pushes them to the first discovered peer, receiving the
 *       peer's changes in the same TCP exchange.</li>
 *   <li>Imports the peer's changes and persists the new last-sync clock.</li>
 *   <li>Stops the transport (unregisters NSD, releases multicast lock).</li>
 * </ol>
 *
 * <p>Schedule via {@link SyncWorkerScheduler}.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    /** Preference key that enables / disables local-network sync. */
    public static final String PREF_SYNC_LOCAL_NETWORK_ENABLED = "sync_local_network_enabled";

    /** Work tag used for one-shot manual sync requests. */
    public static final String TAG_MANUAL = "MANUAL_SYNC";

    /**
     * How long (in ms) the worker waits after starting NSD discovery before attempting to
     * connect to a peer.  This gives the mDNS responder time to locate peers.
     */
    static final long DISCOVERY_WAIT_MS = 5_000L;

    /** Maximum time (in seconds) to wait for a push/pull exchange to complete. */
    private static final long EXCHANGE_TIMEOUT_SECONDS = 30L;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        AppDatabase db = AppDatabase.getDatabase(ctx);
        SyncManager syncManager = new SyncManager(db, ctx);

        // ── Local-network sync (optional) ────────────────────────────────────────
        if (!prefs.getBoolean(PREF_SYNC_LOCAL_NETWORK_ENABLED, false)) {
            DebugLogger.i(TAG, "doWork: local network sync is disabled — skipping");
            Log.d(TAG, "Local network sync is disabled — skipping.");
        } else {
            LocalNetworkSyncTransport transport = new LocalNetworkSyncTransport(ctx, syncManager);
            DebugLogger.i(TAG, "doWork: starting local network sync");
            try {
                transport.start();
                DebugLogger.i(TAG, "doWork: transport started, waiting " + DISCOVERY_WAIT_MS + "ms for peer discovery");

                // Allow mDNS discovery to run before we attempt to connect.
                Thread.sleep(DISCOVERY_WAIT_MS);
                DebugLogger.i(TAG, "doWork: discovery wait complete, peers found=" + transport.getDiscoveredPeers().size());

                byte[] ourBlob = syncManager.exportChanges(syncManager.getLastSyncVersion());
                DebugLogger.i(TAG, "doWork: exported local changes, bytes=" + ourBlob.length);

                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<byte[]> peerBlobRef = new AtomicReference<>();

                transport.push(ourBlob, new SyncCallback() {
                    @Override
                    public void onSuccess(byte[] data) {
                        peerBlobRef.set(data);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception error) {
                        DebugLogger.e(TAG, "doWork: sync push error", error);
                        Log.w(TAG, "Sync push error", error);
                        latch.countDown();
                    }
                });

                boolean completed = latch.await(EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!completed) {
                    DebugLogger.w(TAG, "doWork: sync exchange timed out after " + EXCHANGE_TIMEOUT_SECONDS + "s");
                    Log.w(TAG, "Sync exchange timed out after " + EXCHANGE_TIMEOUT_SECONDS + "s");
                }

                byte[] peerBlob = peerBlobRef.get();
                if (peerBlob != null) {
                    // Capture max clock before import so that changes written by
                    // importChanges() are included in the next sync export.
                    long maxClockBeforeImport = syncManager.getMaxSyncClock();
                    syncManager.importChanges(peerBlob);
                    // Persist the higher of pre/post-import max clocks to avoid
                    // re-exporting imported changes on the next cycle.
                    syncManager.persistLastSyncVersion(
                            Math.max(maxClockBeforeImport, syncManager.getMaxSyncClock()));
                    DebugLogger.i(TAG, "doWork: local sync complete — imported peer changes");
                    Log.d(TAG, "Sync complete — imported peer changes.");
                } else {
                    DebugLogger.i(TAG, "doWork: local sync complete — no peers found or no peer changes");
                    Log.d(TAG, "Sync complete — no peers found or no peer changes.");
                }

            } catch (IOException e) {
                DebugLogger.e(TAG, "doWork: sync transport error", e);
                Log.e(TAG, "Sync transport error", e);
                return Result.failure();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DebugLogger.w(TAG, "doWork: sync worker interrupted");
                Log.w(TAG, "Sync worker interrupted");
                return Result.failure();
            } finally {
                transport.stop();
                DebugLogger.i(TAG, "doWork: transport stopped");
            }
        }

        // ── Drive sync (always runs when enabled and signed in) ──────────────────
        // play flavor: syncs via appDataFolder (solo) or shared household folder;
        // fdroid flavor: DriveTransportFactory.create() always returns null — no-op.
        try {
            SyncTransport driveTransport = DriveTransportFactory.create(ctx, syncManager);
            if (driveTransport != null) {
                DebugLogger.i(TAG, "doWork: Drive transport available, starting Drive sync");
                // Always export the full change log (clock > 0) for Drive.
                // Drive acts as a canonical snapshot store: any device — including one that
                // has never done a local-network sync — must be able to bootstrap from it.
                // Using getLastSyncVersion() here would produce an empty export whenever a
                // local-network sync had just run (which updates lastSyncVersion to the current
                // max clock), so changes would never reach Drive-only peers.
                byte[] driveExport = syncManager.exportChanges(0L);

                CountDownLatch driveLatch = new CountDownLatch(1);
                AtomicReference<byte[]> driveBlobRef = new AtomicReference<>();

                driveTransport.push(driveExport, new SyncCallback() {
                    @Override
                    public void onSuccess(byte[] data) {
                        driveBlobRef.set(data);
                        driveLatch.countDown();
                    }

                    @Override
                    public void onError(Exception error) {
                        DebugLogger.e(TAG, "doWork: Drive sync push error", error);
                        Log.w(TAG, "Drive sync push error", error);
                        driveLatch.countDown();
                    }
                });

                boolean driveCompleted = driveLatch.await(EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!driveCompleted) {
                    DebugLogger.w(TAG, "doWork: Drive sync timed out after " + EXCHANGE_TIMEOUT_SECONDS + "s");
                    Log.w(TAG, "Drive sync timed out after " + EXCHANGE_TIMEOUT_SECONDS + "s");
                }

                byte[] driveBlob = driveBlobRef.get();
                if (driveBlob != null) {
                    long maxClockBeforeDriveImport = syncManager.getMaxSyncClock();
                    syncManager.importChanges(driveBlob);
                    syncManager.persistLastSyncVersion(
                            Math.max(maxClockBeforeDriveImport, syncManager.getMaxSyncClock()));
                    DebugLogger.i(TAG, "doWork: Drive sync complete — imported changes from Drive");
                    Log.d(TAG, "Drive sync complete — imported changes from Drive.");
                } else {
                    DebugLogger.i(TAG, "doWork: Drive sync complete — no changes from Drive");
                    Log.d(TAG, "Drive sync complete — no changes from Drive.");
                }
            } else {
                DebugLogger.i(TAG, "doWork: Drive transport not available (disabled or not signed in)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DebugLogger.w(TAG, "doWork: Drive sync interrupted");
            Log.w(TAG, "Drive sync interrupted");
            return Result.failure();
        }

        DebugLogger.i(TAG, "doWork: sync cycle complete — returning success");
        return Result.success();
    }
}
