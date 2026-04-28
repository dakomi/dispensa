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
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.frigo.dispensa.util.DebugLogger;

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
     * Production constructor for <em>solo mode</em>.  Builds a {@link Drive} service
     * authenticated with the supplied Google {@link Account} using
     * {@link DriveScopes#DRIVE_APPDATA}.
     */
    public GoogleDriveSyncTransport(Context context, Account account) {
        this(buildDriveOps(context, account), 1_000L);
    }

    /**
     * Production constructor for <em>household mode</em>.  Builds a {@link Drive} service
     * using {@link DriveScopes#DRIVE_FILE} and returns a {@link HouseholdDriveOperations}
     * that reads/writes per-device files inside the shared household folder.
     *
     * @param context           application context
     * @param account           the signed-in Google account
     * @param householdFolderId the shared household folder ID (non-null)
     * @param deviceId          the local device UUID used to name this device's sync file
     */
    public GoogleDriveSyncTransport(Context context, Account account,
            String householdFolderId, String deviceId) {
        this(buildHouseholdDriveOps(context, account, householdFolderId, deviceId), 1_000L);
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
        DebugLogger.i(TAG, "push: starting, dataBytes=" + (data == null ? 0 : data.length));
        try {
            byte[] remoteBlob = driveOps.downloadSyncFile();
            DebugLogger.i(TAG, "push: downloaded remote blob, bytes="
                    + (remoteBlob == null ? 0 : remoteBlob.length));
            uploadWithRetry(data);
            DebugLogger.i(TAG, "push: upload complete, calling onSuccess");
            callback.onSuccess(remoteBlob);
        } catch (AuthException e) {
            DebugLogger.e(TAG, "push: Drive auth expired", e);
            Log.w(TAG, "Drive auth expired", e);
            callback.onError(e);
        } catch (IOException e) {
            DebugLogger.e(TAG, "push: Drive push error", e);
            Log.w(TAG, "Drive push error", e);
            callback.onError(e);
        } catch (Exception e) {
            // Guard against unexpected non-IOException from the auth/Drive layer (e.g.
            // RuntimeException from GoogleAccountCredential / play-services-auth 21.x).
            DebugLogger.e(TAG, "push: unexpected Drive exception", e);
            Log.e(TAG, "Unexpected Drive exception in push", e);
            callback.onError(new IOException("Unexpected Drive exception: " + e.getMessage(), e));
        }
    }

    /**
     * Downloads the current Drive sync file and delivers its contents to {@code callback}.
     * Delivers {@code null} if no file exists (first sync).
     */
    @Override
    public void pull(SyncCallback callback) {
        DebugLogger.i(TAG, "pull: starting");
        try {
            byte[] remoteBlob = driveOps.downloadSyncFile();
            DebugLogger.i(TAG, "pull: success, bytes="
                    + (remoteBlob == null ? 0 : remoteBlob.length));
            callback.onSuccess(remoteBlob);
        } catch (AuthException e) {
            DebugLogger.e(TAG, "pull: Drive auth expired", e);
            Log.w(TAG, "Drive auth expired", e);
            callback.onError(e);
        } catch (IOException e) {
            DebugLogger.e(TAG, "pull: Drive pull error", e);
            Log.w(TAG, "Drive pull error", e);
            callback.onError(e);
        } catch (Exception e) {
            // Guard against unexpected non-IOException from the auth/Drive layer (e.g.
            // RuntimeException from GoogleAccountCredential / play-services-auth 21.x).
            DebugLogger.e(TAG, "pull: unexpected Drive exception", e);
            Log.e(TAG, "Unexpected Drive exception in pull", e);
            callback.onError(new IOException("Unexpected Drive exception: " + e.getMessage(), e));
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

    // ── Factory helpers ────────────────────────────────────────────────────────

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

    private static DriveOperations buildHouseholdDriveOps(Context context, Account account,
            String householdFolderId, String deviceId) {
        Drive driveService = HouseholdManager.buildDrive(context, account);
        return new HouseholdDriveOperations(driveService, householdFolderId, deviceId);
    }

    // ── Inner interface & implementations ─────────────────────────────────────

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

    /**
     * Household-mode {@link DriveOperations} implementation.
     *
     * <p>Each device maintains its own {@code dispensa_{deviceId}.json} file inside the
     * shared household folder.  On upload the device overwrites (or creates) its own file.
     * On download all <em>peer</em> files in the folder are fetched and their change lists
     * are merged into a single blob before being returned to the caller.
     *
     * <p>All calls require {@link DriveScopes#DRIVE_FILE} scope.
     */
    static final class HouseholdDriveOperations implements DriveOperations {

        /** Filename prefix shared by all per-device sync files. */
        static final String FILE_PREFIX = "dispensa_";

        private static final Gson GSON = new Gson();

        private final Drive drive;
        private final String folderId;
        private final String deviceId;

        HouseholdDriveOperations(Drive drive, String folderId, String deviceId) {
            this.drive = drive;
            this.folderId = folderId;
            this.deviceId = deviceId;
        }
        /** Returns the filename for this device's sync file, e.g. {@code dispensa_abc123.json}. */
        String deviceFileName() {
            return FILE_PREFIX + deviceId + DRIVE_FILE_NAME;
        }

        /**
         * Lists all {@code dispensa_*.json} files in the household folder (excluding this
         * device's own file), downloads each, and merges their {@link SyncChange} lists into
         * a single blob.  Returns {@code null} if there are no peer files or all peer files
         * are empty.
         */
        @Override
        public byte[] downloadSyncFile() throws IOException {
            FileList result;
            try {
                result = drive.files().list()
                        .setQ("'" + folderId + "' in parents"
                                + " and name contains '" + FILE_PREFIX + "'")
                        .setFields("files(id,name)")
                        .execute();
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on list", e);
                throw e;
            }

            List<File> files = result.getFiles();
            if (files == null || files.isEmpty()) return null;

            String ownFileName = deviceFileName();
            List<SyncChange> allChanges = new ArrayList<>();

            for (File f : files) {
                if (ownFileName.equals(f.getName())) continue; // skip this device's own file

                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    drive.files().get(f.getId()).executeMediaAndDownloadTo(out);
                    String json = out.toString(StandardCharsets.UTF_8.name());
                    SyncBlob peerBlob = GSON.fromJson(json, SyncBlob.class);
                    if (peerBlob != null && peerBlob.changes != null) {
                        allChanges.addAll(peerBlob.changes);
                    }
                } catch (GoogleJsonResponseException e) {
                    if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on download", e);
                    if (e.getStatusCode() == 404) continue; // file disappeared between list and get
                    throw e;
                }
            }

            if (allChanges.isEmpty()) return null;
            // senderDeviceId is null for the merged household blob (multiple sources)
            return GSON.toJson(new SyncBlob(null, allChanges)).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Uploads {@code content} as this device's sync file inside the household folder.
         * Creates the file if it does not yet exist; updates it otherwise.
         */
        @Override
        public void uploadSyncFile(byte[] content) throws IOException {
            ByteArrayContent mediaContent = new ByteArrayContent(DRIVE_MIME_TYPE, content);
            String fileName = deviceFileName();
            String fileId = findDeviceFileId(fileName);
            try {
                if (fileId == null) {
                    File meta = new File()
                            .setName(fileName)
                            .setParents(Collections.singletonList(folderId));
                    drive.files().create(meta, mediaContent).setFields("id").execute();
                } else {
                    drive.files().update(fileId, new File(), mediaContent).execute();
                }
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 401) throw new AuthException("Drive 401 on upload", e);
                throw e;
            }
        }

        private String findDeviceFileId(String fileName) throws IOException {
            try {
                FileList result = drive.files().list()
                        .setQ("'" + folderId + "' in parents"
                                + " and name = '" + fileName + "'")
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
