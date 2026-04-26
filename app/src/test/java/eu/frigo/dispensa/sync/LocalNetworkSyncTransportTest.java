package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalNetworkSyncTransportTest {

    private NsdManager mockNsdManager;
    private SyncManager mockSyncManager;
    private ExecutorService executor;
    private LocalNetworkSyncTransport transport;

    @Before
    public void setUp() {
        mockNsdManager = mock(NsdManager.class);
        mockSyncManager = mock(SyncManager.class);
        // Use a real single-thread executor so submitted tasks run synchronously in tests
        executor = Executors.newSingleThreadExecutor();

        transport = new LocalNetworkSyncTransport(
                mockNsdManager,
                null,           // no multicast lock needed in JVM unit tests
                mockSyncManager,
                executor
        );
    }

    // ── NSD registration ──────────────────────────────────────────────────────

    @Test
    public void registerService_callsNsdManagerRegisterService() {
        transport.registerService(12345);

        verify(mockNsdManager).registerService(
                any(NsdServiceInfo.class),
                eq(NsdManager.PROTOCOL_DNS_SD),
                any(NsdManager.RegistrationListener.class));
    }

    @Test
    public void registerService_usesCorrectServiceType() {
        // Verify the SERVICE_TYPE constant itself has the required trailing dot
        assertTrue("SERVICE_TYPE must end with '.'",
                LocalNetworkSyncTransport.SERVICE_TYPE.endsWith("."));
        assertTrue("SERVICE_TYPE must contain '_dispensa._tcp'",
                LocalNetworkSyncTransport.SERVICE_TYPE.contains("_dispensa._tcp"));
    }

    // ── NSD discovery ─────────────────────────────────────────────────────────

    @Test
    public void startDiscovery_callsNsdManagerDiscoverServices() {
        transport.startDiscovery();

        verify(mockNsdManager).discoverServices(
                eq(LocalNetworkSyncTransport.SERVICE_TYPE),
                eq(NsdManager.PROTOCOL_DNS_SD),
                any(NsdManager.DiscoveryListener.class));
    }

    @Test
    public void stop_callsUnregisterService_afterRegisterService() {
        transport.registerService(5555);
        transport.stop();

        verify(mockNsdManager).unregisterService(any(NsdManager.RegistrationListener.class));
    }

    @Test
    public void stop_callsStopServiceDiscovery_afterStartDiscovery() {
        transport.startDiscovery();
        transport.stop();

        verify(mockNsdManager).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
    }

    @Test
    public void stop_doesNotCallUnregisterService_ifNotRegistered() {
        // stop() without a prior registerService() must not throw or call unregister
        transport.stop();

        verify(mockNsdManager, never()).unregisterService(any());
    }

    @Test
    public void stop_doesNotCallStopDiscovery_ifDiscoveryNotStarted() {
        transport.stop();

        verify(mockNsdManager, never()).stopServiceDiscovery(any());
    }

    // ── Discovery listener callbacks ──────────────────────────────────────────

    @Test
    public void discoveryListener_onServiceLost_removesPeerByName() {
        // Add a fake peer — use a mock so getServiceName() returns a real value
        NsdServiceInfo peer = mock(NsdServiceInfo.class);
        when(peer.getServiceName()).thenReturn("DispensaSync");
        transport.discoveredPeers.add(peer);

        // Start discovery to create the listener
        transport.startDiscovery();

        // Capture the DiscoveryListener
        ArgumentCaptor<NsdManager.DiscoveryListener> listenerCaptor =
                ArgumentCaptor.forClass(NsdManager.DiscoveryListener.class);
        verify(mockNsdManager).discoverServices(
                any(), anyInt(), listenerCaptor.capture());

        NsdManager.DiscoveryListener listener = listenerCaptor.getValue();

        // Signal service lost for the same service name
        NsdServiceInfo lostInfo = mock(NsdServiceInfo.class);
        when(lostInfo.getServiceName()).thenReturn("DispensaSync");
        listener.onServiceLost(lostInfo);

        assertTrue("Peer should have been removed", transport.discoveredPeers.isEmpty());
    }

    @Test
    public void discoveryListener_onServiceFound_callsResolveService() {
        transport.startDiscovery();

        ArgumentCaptor<NsdManager.DiscoveryListener> listenerCaptor =
                ArgumentCaptor.forClass(NsdManager.DiscoveryListener.class);
        verify(mockNsdManager).discoverServices(any(), anyInt(), listenerCaptor.capture());

        NsdServiceInfo foundInfo = mock(NsdServiceInfo.class);
        when(foundInfo.getServiceName()).thenReturn("DispensaSync");
        listenerCaptor.getValue().onServiceFound(foundInfo);

        // resolveService() should have been triggered
        verify(mockNsdManager).resolveService(
                eq(foundInfo), any(NsdManager.ResolveListener.class));
    }

    // ── push() with no peers ──────────────────────────────────────────────────

    @Test
    public void push_callsOnSuccessWithNull_whenNoPeersDiscovered() throws InterruptedException {
        // No peers in discoveredPeers

        SyncCallback callback = mock(SyncCallback.class);
        transport.push(new byte[]{1, 2, 3}, callback);

        // Verify onSuccess(null) called immediately (no peers)
        verify(callback).onSuccess(null);
        verify(callback, never()).onError(any());
    }

    // ── pull() is passive ─────────────────────────────────────────────────────

    @Test
    public void pull_callsOnSuccessWithNull_immediately() {
        SyncCallback callback = mock(SyncCallback.class);
        transport.pull(callback);

        verify(callback).onSuccess(null);
        verify(callback, never()).onError(any());
    }

    // ── discoveredPeers list ──────────────────────────────────────────────────

    @Test
    public void discoveredPeers_initiallyEmpty() {
        assertTrue(transport.discoveredPeers.isEmpty());
    }

    @Test
    public void stop_clearsPeerList() {
        NsdServiceInfo peer = mock(NsdServiceInfo.class);
        when(peer.getServiceName()).thenReturn("DispensaSync");
        transport.discoveredPeers.add(peer);

        transport.stop();

        assertTrue("discoveredPeers should be empty after stop()",
                transport.discoveredPeers.isEmpty());
    }
}
