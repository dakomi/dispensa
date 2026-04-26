package eu.frigo.dispensa.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.preference.PreferenceManager;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import eu.frigo.dispensa.data.AppDatabase;

/**
 * Transport-agnostic sync manager.
 *
 * <p>Serialises outgoing changes from CR-SQLite's {@code crsql_changes} virtual table into
 * a JSON byte blob, and deserialises incoming blobs back into the database.  The last
 * synchronised {@code db_version} is persisted in {@link SharedPreferences} so that
 * subsequent exports only ship changes that the remote peer has not yet seen.
 *
 * <h3>Bootstrap</h3>
 * When {@code lastSyncVersion == 0} the export query becomes {@code db_version > 0},
 * which includes the full change history — the correct behaviour for a first sync.
 */
public class SyncManager {

    static final String PREFS_KEY_LAST_SYNC_VERSION = "last_sync_version";

    private static final String EXPORT_CHANGES_SQL =
            "SELECT \"table\", pk, cid, val, col_version, db_version, site_id, cl, seq"
                    + " FROM crsql_changes WHERE db_version > ?";

    private static final String IMPORT_CHANGE_SQL =
            "INSERT INTO crsql_changes"
                    + " (\"table\", pk, cid, val, col_version, db_version, site_id, cl, seq)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final SupportSQLiteDatabase db;
    private final SharedPreferences prefs;
    private final Gson gson;

    /**
     * Production constructor.  Obtains a writable database handle from {@code database}
     * and uses the app-wide default {@link SharedPreferences}.
     */
    public SyncManager(AppDatabase database, Context context) {
        this(database.getOpenHelper().getWritableDatabase(),
                PreferenceManager.getDefaultSharedPreferences(context));
    }

    /**
     * Package-private constructor used by unit tests to inject mocks.
     */
    SyncManager(SupportSQLiteDatabase db, SharedPreferences prefs) {
        this.db = db;
        this.prefs = prefs;
        this.gson = new Gson();
    }

    /**
     * Export all changes with {@code db_version > lastSyncVersion} as a JSON byte blob.
     *
     * @param lastSyncVersion the highest {@code db_version} already sent to the remote peer;
     *                        pass {@code 0} to export the full change log (bootstrap sync)
     * @return UTF-8 encoded JSON blob suitable for passing to {@link SyncTransport#push}
     */
    public byte[] exportChanges(long lastSyncVersion) {
        String sql = EXPORT_CHANGES_SQL;
        List<SyncChange> changes = new ArrayList<>();

        try (Cursor cursor = db.query(sql, new Object[]{lastSyncVersion})) {
            while (cursor.moveToNext()) {
                SyncChange change = new SyncChange();
                change.table = cursor.getString(0);
                change.pk = cursor.getString(1);
                change.cid = cursor.getString(2);
                change.val = cursor.isNull(3) ? null : cursor.getString(3);
                change.colVersion = cursor.getLong(4);
                change.dbVersion = cursor.getLong(5);
                byte[] siteIdBytes = cursor.getBlob(6);
                change.siteId = siteIdBytes != null
                        ? Base64.getEncoder().encodeToString(siteIdBytes) : null;
                change.cl = cursor.getLong(7);
                change.seq = cursor.getLong(8);
                changes.add(change);
            }
        }

        SyncBlob blob = new SyncBlob(changes);
        return gson.toJson(blob).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Import a change blob received from a remote peer into the local database.
     * CR-SQLite's built-in LWW (Lamport-clock) conflict resolution is applied automatically.
     *
     * @param blobBytes UTF-8 JSON blob as produced by a remote {@link #exportChanges(long)}
     */
    public void importChanges(byte[] blobBytes) {
        String json = new String(blobBytes, StandardCharsets.UTF_8);
        SyncBlob blob = gson.fromJson(json, SyncBlob.class);
        if (blob == null || blob.changes == null || blob.changes.isEmpty()) {
            return;
        }
        for (SyncChange change : blob.changes) {
            byte[] siteIdBytes = change.siteId != null
                    ? Base64.getDecoder().decode(change.siteId) : null;
            db.execSQL(IMPORT_CHANGE_SQL,
                    new Object[]{
                            change.table, change.pk, change.cid, change.val,
                            change.colVersion, change.dbVersion, siteIdBytes,
                            change.cl, change.seq
                    }
            );
        }
    }

    /**
     * Returns the highest {@code db_version} that was successfully synced to all known peers,
     * or {@code 0} if no sync has occurred yet.
     */
    public long getLastSyncVersion() {
        return prefs.getLong(PREFS_KEY_LAST_SYNC_VERSION, 0L);
    }

    /**
     * Persist the highest {@code db_version} that has been confirmed synced.
     * Must be called after a successful {@link SyncTransport#push} / {@link SyncTransport#pull}.
     */
    public void persistLastSyncVersion(long version) {
        prefs.edit().putLong(PREFS_KEY_LAST_SYNC_VERSION, version).apply();
    }
}
