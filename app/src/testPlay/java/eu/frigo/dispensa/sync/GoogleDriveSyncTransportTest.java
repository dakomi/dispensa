package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.io.IOException;

/**
 * JUnit 4 unit tests for {@link GoogleDriveSyncTransport}.
 *
 * <p>All Drive I/O is performed through the {@link GoogleDriveSyncTransport.DriveOperations}
 * interface, which is injected as a mock so tests run without network access.
 * The test constructor also sets {@code backoffBaseMs = 0}, so retry tests run instantly.
 */
public class GoogleDriveSyncTransportTest {

    private GoogleDriveSyncTransport.DriveOperations mockOps;
    private SyncManager mockSyncManager;
    private GoogleDriveSyncTransport transport;

    @Before
    public void setUp() {
        mockOps = mock(GoogleDriveSyncTransport.DriveOperations.class);
        mockSyncManager = mock(SyncManager.class);
        // Package-private test constructor: zero-delay backoff, injected DriveOperations.
        transport = new GoogleDriveSyncTransport(mockOps);
    }

    // ── push(): happy-path ────────────────────────────────────────────────────

    @Test
    public void push_downloadsBeforeUploading() throws Exception {
        byte[] localData = "local".getBytes();
        byte[] remoteData = "remote".getBytes();
        when(mockOps.downloadSyncFile()).thenReturn(remoteData);

        SyncCallback callback = mock(SyncCallback.class);
        transport.push(localData, callback);

        InOrder order = inOrder(mockOps);
        order.verify(mockOps).downloadSyncFile();
        order.verify(mockOps).uploadSyncFile(localData);
    }

    @Test
    public void push_returnsDownloadedContentViaCallback() throws Exception {
        byte[] remoteData = "remote".getBytes();
        when(mockOps.downloadSyncFile()).thenReturn(remoteData);

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("local".getBytes(), callback);

        verify(callback).onSuccess(remoteData);
        verify(callback, never()).onError(any());
    }

    @Test
    public void push_returnsNull_whenNoExistingDriveFile() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("local".getBytes(), callback);

        verify(callback).onSuccess(null);
        verify(callback, never()).onError(any());
    }

    @Test
    public void push_stillUploads_whenRemoteBlobIsNull() throws Exception {
        byte[] localData = "local".getBytes();
        when(mockOps.downloadSyncFile()).thenReturn(null);

        SyncCallback callback = mock(SyncCallback.class);
        transport.push(localData, callback);

        verify(mockOps).uploadSyncFile(localData);
    }

    // ── push(): error handling ────────────────────────────────────────────────

    @Test
    public void push_callsOnError_whenDownloadThrowsIoException() throws Exception {
        when(mockOps.downloadSyncFile()).thenThrow(new IOException("network error"));

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("local".getBytes(), callback);

        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any(IOException.class));
    }

    @Test
    public void push_callsOnError_withAuthException_on401() throws Exception {
        when(mockOps.downloadSyncFile())
                .thenThrow(new GoogleDriveSyncTransport.AuthException("401", new IOException()));

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("local".getBytes(), callback);

        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any(GoogleDriveSyncTransport.AuthException.class));
    }

    // ── push(): retry logic ───────────────────────────────────────────────────

    @Test
    public void push_retriesUpload_onTransientError_thenSucceeds() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);
        doThrow(buildJsonResponseException(503))
                .doThrow(buildJsonResponseException(503))
                .doNothing()
                .when(mockOps).uploadSyncFile(any());

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("data".getBytes(), callback);

        verify(mockOps, times(3)).uploadSyncFile(any());
        verify(callback).onSuccess(null);
        verify(callback, never()).onError(any());
    }

    @Test
    public void push_retriesUpload_on429RateLimitError_thenSucceeds() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);
        doThrow(buildJsonResponseException(429))
                .doNothing()
                .when(mockOps).uploadSyncFile(any());

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("data".getBytes(), callback);

        verify(mockOps, times(2)).uploadSyncFile(any());
        verify(callback).onSuccess(null);
    }

    @Test
    public void push_failsAfterMaxRetries_onPersistentTransientError() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);
        doThrow(buildJsonResponseException(503))
                .when(mockOps).uploadSyncFile(any());

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("data".getBytes(), callback);

        verify(mockOps, times(GoogleDriveSyncTransport.MAX_RETRIES)).uploadSyncFile(any());
        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any());
    }

    @Test
    public void push_doesNotRetry_on4xxClientError() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);
        doThrow(buildJsonResponseException(400))
                .when(mockOps).uploadSyncFile(any());

        SyncCallback callback = mock(SyncCallback.class);
        transport.push("data".getBytes(), callback);

        verify(mockOps, times(1)).uploadSyncFile(any());
        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any());
    }

    // ── pull() ────────────────────────────────────────────────────────────────

    @Test
    public void pull_returnsDownloadedContent() throws Exception {
        byte[] remoteData = "remote".getBytes();
        when(mockOps.downloadSyncFile()).thenReturn(remoteData);

        SyncCallback callback = mock(SyncCallback.class);
        transport.pull(callback);

        verify(callback).onSuccess(remoteData);
        verify(callback, never()).onError(any());
    }

    @Test
    public void pull_returnsNull_whenNoFileExists() throws Exception {
        when(mockOps.downloadSyncFile()).thenReturn(null);

        SyncCallback callback = mock(SyncCallback.class);
        transport.pull(callback);

        verify(callback).onSuccess(null);
        verify(callback, never()).onError(any());
    }

    @Test
    public void pull_callsOnError_onIoException() throws Exception {
        when(mockOps.downloadSyncFile()).thenThrow(new IOException("network error"));

        SyncCallback callback = mock(SyncCallback.class);
        transport.pull(callback);

        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any(IOException.class));
    }

    @Test
    public void pull_callsOnError_withAuthException_on401() throws Exception {
        when(mockOps.downloadSyncFile())
                .thenThrow(new GoogleDriveSyncTransport.AuthException("401", new IOException()));

        SyncCallback callback = mock(SyncCallback.class);
        transport.pull(callback);

        verify(callback, never()).onSuccess(any());
        verify(callback).onError(any(GoogleDriveSyncTransport.AuthException.class));
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    public void driveFileName_hasLeadingDot() {
        assertTrue("Drive file name should start with '.'",
                GoogleDriveSyncTransport.DRIVE_FILE_NAME.startsWith("."));
    }

    @Test
    public void appDataFolder_isCorrectValue() {
        assertTrue("APP_DATA_FOLDER must be 'appDataFolder'",
                "appDataFolder".equals(GoogleDriveSyncTransport.APP_DATA_FOLDER));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static GoogleJsonResponseException buildJsonResponseException(int statusCode) {
        HttpResponseException.Builder builder = new HttpResponseException.Builder(
                statusCode, "Error", new HttpHeaders());
        return new GoogleJsonResponseException(builder, null);
    }
}
