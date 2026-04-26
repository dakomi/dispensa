package eu.frigo.dispensa.sync;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * {@link SyncTransport} that syncs via a Google Drive {@code appDataFolder} file.
 *
 * <p>Available in the {@code play} product flavor only.
 *
 * <h3>Protocol</h3>
 * A single file named {@value #DRIVE_FILE_NAME} in the Drive {@code appDataFolder} stores
 * the latest change blob exported by the most recent sync.
 * <ol>
 *   <li>{@link #push(byte[], SyncCallback)} downloads the existing Drive file (if any),
 *       uploads the caller's blob to replace it, then returns the previously-downloaded bytes
 *       to the caller so that changes from other devices are imported via
 *       {@link SyncManager#importChanges(byte[])}.</li>
 *   <li>{@link #pull(SyncCallback)} downloads the Drive file and returns its contents.</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li>HTTP 401: auth expired — {@link SyncCallback#onError(Exception)} is called with
 *       an {@link AuthException}.</li>
 *   <li>HTTP 404: no existing Drive file (first sync) — treated as empty; download returns
 *       {@code null}.</li>
 *   <li>HTTP 429 / 5xx: transient error — upload is retried up to {@value #MAX_RETRIES} times
 *       with exponential backoff.</li>
 * </ul>
 */
public class GoogleDriveSyncTransport implements SyncTransport {

    private static final String TAG = "GoogleDriveSyncTransport";

    static final String DRIVE_FILE_NAME = ".dispensa_sync_changes.json";
    static final String DRIVE_MIME_TYPE = "application/json";
    static final String APP_DATA_FOLDER = "appDataFolder";
    static final int MAX_RETRIES = 3;

    /** Base delay (ms) between retry attempts: attempt 0 → 2×base, attempt 1 → 4×base, … */
    private final long backoffBaseMs;

    private final DriveOperations driveOps;

    // ── Exception type ────────────────────────────────────────────────────────

    /** Signals that Google Drive authentication has expired and the user must re-sign-in. */
    static final class AuthException extends IOException {
        AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Production constructor. Builds a {@link Drive} service authenticated with
     * the supplied Google {@link Account} using {@link DriveScopes#DRIVE_APPDATA}.
     */
    public GoogleDriveSyncTransport(Context context, Account account) {
        this(buildDriveOps(context, account), 1_000L);
    }

    /**
     * Package-private constructor for unit tests — injects a {@link DriveOperations} mock
     * and uses zero-delay backoff so tests run instantly.
     */
    GoogleDriveSyncTransport(DriveOperations driveOps) {
        this(driveOps, 0L);
    }

    private GoogleDriveSyncTransport(DriveOperations driveOps, long backoffBaseMs) {
        this.driveOps = driveOps;
        this.backoffBaseMs = backoffBaseMs;
    }

    // ── SyncTransport ─────────────────────────────────────────────────────────

    /**
     * Downloads the existing Drive sync file (if present), uploads {@code data} to replace it,
     * and delivers the previously-downloaded bytes to {@code callback}.
     *
     * <p>The caller (SyncWorker) imports the returned bytes so that changes written by other
     * devices are merged locally.  If no file exists on Drive (first sync), the callback
     * receives {@code null}.
     */
    @Override
    public void push(byte[] data, SyncCallback callback) {
        try {
            byte[] remoteBlob = driveOps.downloadSyncFile();
            uploadWithRetry(data);
            callback.onSuccess(remoteBlob);
        } catch (AuthException e) {
            Log.w(TAG, "Drive auth expired", e);
            callback.onError(e);
        } catch (IOException e) {
            Log.w(TAG, "Drive push error", e);
            callback.onError(e);
        }
    }

    /**
     * Downloads the current Drive sync file and delivers its contents to {@code callback}.
     * Delivers {@code null} if no file exists (first sync).
     */
    @Override
    public void pull(SyncCallback callback) {
        try {
            byte[] remoteBlob = driveOps.downloadSyncFile();
            callback.onSuccess(remoteBlob);
        } catch (AuthException e) {
            Log.w(TAG, "Drive auth expired", e);
            callback.onError(e);
        } catch (IOException e) {
            Log.w(TAG, "Drive pull error", e);
            callback.onError(e);
        }
    }

    // ── Retry wrapper ─────────────────────────────────────────────────────────

    private void uploadWithRetry(byte[] data) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                driveOps.uploadSyncFile(data);
                return;
            } catch (GoogleJsonResponseException e) {
                int status = e.getStatusCode();
                if (status == 429 || status >= 500) {
                    lastException = e;
                    if (attempt < MAX_RETRIES - 1) {
                        sleepBackoff(attempt);
                    }
                } else {
                    throw e;
                }
            }
        }
        throw lastException;
    }

    private void sleepBackoff(int attempt) {
        if (backoffBaseMs <= 0) return;
        try {
            Thread.sleep(backoffBaseMs * (1L << (attempt + 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Factory helper ────────────────────────────────────────────────────────

    private static DriveOperations buildDriveOps(Context context, Account account) {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_APPDATA));
        credential.setSelectedAccount(account);

        Drive driveService = new Drive.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Dispensa")
                .build();

        return new RealDriveOperations(driveService);
    }

    // ── Inner interface & implementation ──────────────────────────────────────

    /**
     * Thin wrapper around Drive API calls — package-private so tests can inject a mock.
     */
    interface DriveOperations {
        /**
         * Downloads the sync file content, or returns {@code null} if the file does not
         * exist (HTTP 404).
         *
         * @throws AuthException if the HTTP response is 401
         * @throws IOException   on any other I/O or HTTP error
         */
        byte[] downloadSyncFile() throws IOException;

        /**
         * Uploads {@code content} as the sync file (creates if absent, replaces if present).
         *
         * @throws AuthException              if the HTTP response is 401
         * @throws GoogleJsonResponseException on any other HTTP error
         * @throws IOException                on network errors
         */
        void uploadSyncFile(byte[] content) throws IOException;
    }

    /** Production implementation backed by the Google Drive REST API v3. */
    static final class RealDriveOperations implements DriveOperations {

        private final Drive drive;

        RealDriveOperations(Drive drive) {
            this.drive = drive;
        }

        @Override
        public byte[] downloadSyncFile() throws IOException {
            String fileId = findSyncFileId();
            if (fileId == null) return null;

            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                drive.files().get(fileId).executeMediaAndDownloadTo(out);
                return out.toByteArray();
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on download", e);
                if (e.getStatusCode() == 404) return null;
                throw e;
            }
        }

        @Override
        public void uploadSyncFile(byte[] content) throws IOException {
            ByteArrayContent mediaContent = new ByteArrayContent(DRIVE_MIME_TYPE, content);
            String fileId = findSyncFileId();
            try {
                if (fileId == null) {
                    File meta = new File()
                            .setName(DRIVE_FILE_NAME)
                            .setParents(Collections.singletonList(APP_DATA_FOLDER));
                    drive.files().create(meta, mediaContent).setFields("id").execute();
                } else {
                    drive.files().update(fileId, new File(), mediaContent).execute();
                }
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on upload", e);
                throw e;
            }
        }

        private String findSyncFileId() throws IOException {
            try {
                FileList result = drive.files().list()
                        .setSpaces(APP_DATA_FOLDER)
                        .setQ("name = '" + DRIVE_FILE_NAME + "'")
                        .setFields("files(id)")
                        .execute();
                List<File> files = result.getFiles();
                return (files != null && !files.isEmpty()) ? files.get(0).getId() : null;
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on list", e);
                throw e;
            }
        }
    }
}
