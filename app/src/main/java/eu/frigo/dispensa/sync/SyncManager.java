package eu.frigo.dispensa.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.preference.PreferenceManager;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import eu.frigo.dispensa.data.AppDatabase;
import eu.frigo.dispensa.util.DebugLogger;

/**
 * Transport-agnostic sync manager.
 *
 * <p>Changes to the four synced tables ({@code products}, {@code categories_definitions},
 * {@code product_category_links}, {@code storage_locations}) are automatically recorded
 * in the {@code sync_changes} table by SQLite triggers installed by
 * {@link AppDatabase#MIGRATION_9_10}.  This class serialises those changes into a JSON
 * byte blob for transport, and deserialises incoming blobs back into the database using
 * Lamport-clock last-write-wins (LWW) conflict resolution.
 *
 * <h3>Conflict resolution</h3>
 * Each change carries a monotonically-increasing {@code clock} value assigned by the
 * originating device.  On import the incoming change wins if its clock is strictly
 * greater than the locally stored clock for the same {@code (tbl, pk_val)} pair, or if
 * the clocks are equal and the incoming {@code deviceId} is lexicographically greater
 * (stable tiebreaker).
 *
 * <h3>Import lock</h3>
 * Applying an incoming change triggers a raw {@code INSERT OR REPLACE} on the target
 * table.  To prevent the change-capture triggers from re-firing and creating a spurious
 * second entry in {@code sync_changes}, all imports are performed inside a transaction
 * with the {@code sync_import_lock} flag set to 1.  The triggers check this flag and
 * skip firing when it is set.
 *
 * <h3>Bootstrap</h3>
 * When {@code lastSyncVersion == 0} the export query becomes {@code clock > 0}, which
 * returns the full change log — the correct behaviour for a first sync.
 */
public class SyncManager {

    static final String PREFS_KEY_LAST_SYNC_VERSION = "last_sync_version";
    static final String PREFS_KEY_DEVICE_ID = "sync_device_id";

    // ── Export ────────────────────────────────────────────────────────────────

    private static final String EXPORT_CHANGES_SQL =
            "SELECT tbl, pk_val, op, row_json, clock"
                    + " FROM sync_changes WHERE clock > ? ORDER BY clock ASC";

    // ── Import: lock management ───────────────────────────────────────────────

    private static final String LOCK_SQL =
            "UPDATE sync_import_lock SET locked = 1";
    private static final String UNLOCK_SQL =
            "UPDATE sync_import_lock SET locked = 0";

    // ── Import: conflict check ────────────────────────────────────────────────

    private static final String GET_LOCAL_MAX_CLOCK_SQL =
            "SELECT COALESCE(MAX(clock), 0) FROM sync_changes WHERE tbl = ? AND pk_val = ?";

    // ── Import: record change in local log (upsert by composite PK) ──────────

    private static final String RECORD_CHANGE_SQL =
            "INSERT OR REPLACE INTO sync_changes (tbl, pk_val, op, row_json, clock)"
                    + " VALUES (?, ?, ?, ?, ?)";

    // ── Import: per-table UPSERT SQL ──────────────────────────────────────────

    private static final String UPSERT_PRODUCT_SQL =
            "INSERT OR REPLACE INTO products"
                    + " (id, barcode, quantity, expiry_date, product_name, image_url,"
                    + " storage_location, opened_date, shelf_life_after_opening_days)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPSERT_CATEGORY_DEFINITION_SQL =
            "INSERT OR REPLACE INTO categories_definitions"
                    + " (category_id, tag_name, display_name_it, language_code, color_hex)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private static final String UPSERT_PRODUCT_CATEGORY_LINK_SQL =
            "INSERT OR REPLACE INTO product_category_links"
                    + " (product_id_fk, category_id_fk) VALUES (?, ?)";

    private static final String UPSERT_STORAGE_LOCATION_SQL =
            "INSERT OR REPLACE INTO storage_locations"
                    + " (id, name, internal_key, order_index, is_default, is_predefined)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    // ── Import: per-table DELETE SQL ──────────────────────────────────────────

    private static final String DELETE_PRODUCT_SQL =
            "DELETE FROM products WHERE id = ?";
    private static final String DELETE_CATEGORY_DEFINITION_SQL =
            "DELETE FROM categories_definitions WHERE category_id = ?";
    private static final String DELETE_PRODUCT_CATEGORY_LINK_SQL =
            "DELETE FROM product_category_links WHERE product_id_fk = ? AND category_id_fk = ?";
    private static final String DELETE_STORAGE_LOCATION_SQL =
            "DELETE FROM storage_locations WHERE id = ?";

    private static final String TAG = "SyncManager";

    // ── Fields ────────────────────────────────────────────────────────────────

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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Export all changes with {@code clock > lastSyncVersion} as a JSON byte blob.
     *
     * @param lastSyncVersion the highest clock value already sent to the remote peer;
     *                        pass {@code 0} to export the full change log (bootstrap sync)
     * @return UTF-8 encoded JSON blob suitable for passing to {@link SyncTransport#push}
     */
    public byte[] exportChanges(long lastSyncVersion) {
        String localDeviceId = getLocalDeviceId();
        List<SyncChange> changes = new ArrayList<>();

        try (Cursor cursor = db.query(EXPORT_CHANGES_SQL, new Object[]{lastSyncVersion})) {
            while (cursor.moveToNext()) {
                SyncChange change = new SyncChange();
                change.tbl = cursor.getString(0);
                change.pkVal = cursor.getString(1);
                change.op = cursor.getString(2);
                change.rowJson = cursor.isNull(3) ? null : cursor.getString(3);
                change.clock = cursor.getLong(4);
                change.deviceId = localDeviceId;
                changes.add(change);
            }
        }

        SyncBlob blob = new SyncBlob(localDeviceId, changes);
        byte[] bytes = gson.toJson(blob).getBytes(StandardCharsets.UTF_8);
        DebugLogger.i(TAG, "exportChanges: lastSyncVersion=" + lastSyncVersion
                + " changeCount=" + changes.size() + " bytes=" + bytes.length);
        return bytes;
    }

    /**
     * Import a change blob received from a remote peer into the local database.
     * LWW (last-write-wins) conflict resolution is applied using Lamport clocks;
     * equal clocks are broken lexicographically by {@code deviceId}.
     *
     * @param blobBytes UTF-8 JSON blob as produced by a remote {@link #exportChanges(long)}
     */
    public void importChanges(byte[] blobBytes) {
        if (blobBytes == null) return;
        String json = new String(blobBytes, StandardCharsets.UTF_8);
        SyncBlob blob = gson.fromJson(json, SyncBlob.class);
        if (blob == null || blob.changes == null || blob.changes.isEmpty()) {
            DebugLogger.i(TAG, "importChanges: blob is empty or null — nothing to import");
            return;
        }

        DebugLogger.i(TAG, "importChanges: processing " + blob.changes.size()
                + " changes from senderDeviceId=" + blob.senderDeviceId);
        String localDeviceId = getLocalDeviceId();

        db.beginTransaction();
        try {
            db.execSQL(LOCK_SQL);

            for (SyncChange incoming : blob.changes) {
                long localMaxClock = getLocalMaxClock(incoming.tbl, incoming.pkVal);

                boolean incomingWins = incoming.clock > localMaxClock
                        || (incoming.clock == localMaxClock
                        && incoming.deviceId != null
                        && incoming.deviceId.compareTo(localDeviceId) > 0);

                if (incomingWins) {
                    if ("UPSERT".equals(incoming.op) && incoming.rowJson != null) {
                        applyUpsert(incoming.tbl, incoming.rowJson);
                    } else if ("DELETE".equals(incoming.op)) {
                        applyDelete(incoming.tbl, incoming.pkVal);
                    }
                    db.execSQL(RECORD_CHANGE_SQL, new Object[]{
                            incoming.tbl, incoming.pkVal, incoming.op,
                            incoming.rowJson, incoming.clock
                    });
                }
            }

            db.execSQL(UNLOCK_SQL);
            db.setTransactionSuccessful();
            DebugLogger.i(TAG, "importChanges: transaction committed successfully");
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Returns the highest {@code clock} value currently stored in the local
     * {@code sync_changes} table, or {@code 0} if the table is empty.
     *
     * <p>This is used after a successful sync cycle to update
     * {@link #persistLastSyncVersion(long)} so that subsequent exports only include
     * changes that occurred after this point.
     */
    public long getMaxSyncClock() {
        try (Cursor c = db.query("SELECT COALESCE(MAX(clock), 0) FROM sync_changes", null)) {
            if (c.moveToFirst()) return c.getLong(0);
            return 0L;
        }
    }

    /**
     * Returns the highest clock value that was successfully synced with all known peers,
     * or {@code 0} if no sync has occurred yet.
     */
    public long getLastSyncVersion() {
        return prefs.getLong(PREFS_KEY_LAST_SYNC_VERSION, 0L);
    }

    /**
     * Persist the highest clock value that has been confirmed synced.
     * Must be called after a successful {@link SyncTransport#push} / {@link SyncTransport#pull}.
     */
    public void persistLastSyncVersion(long version) {
        prefs.edit().putLong(PREFS_KEY_LAST_SYNC_VERSION, version).apply();
    }

    // ── Package-private helpers (accessible from tests) ───────────────────────

    String getLocalDeviceId() {
        String deviceId = prefs.getString(PREFS_KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(PREFS_KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }

    /**
     * Parses the {@link SyncBlob#senderDeviceId} field from a raw blob byte array.
     * Returns {@code null} if the blob is malformed or was produced by an older client
     * that does not include the field.
     */
    static String extractSenderDeviceId(byte[] blobBytes) {
        if (blobBytes == null) return null;
        try {
            String json = new String(blobBytes, StandardCharsets.UTF_8);
            SyncBlob blob = new Gson().fromJson(json, SyncBlob.class);
            return blob != null ? blob.senderDeviceId : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private long getLocalMaxClock(String tbl, String pkVal) {
        try (Cursor c = db.query(GET_LOCAL_MAX_CLOCK_SQL, new Object[]{tbl, pkVal})) {
            if (c.moveToFirst()) return c.getLong(0);
            return 0L;
        }
    }

    private void applyUpsert(String tbl, String rowJson) {
        JsonObject obj = JsonParser.parseString(rowJson).getAsJsonObject();
        switch (tbl) {
            case "products":
                db.execSQL(UPSERT_PRODUCT_SQL, new Object[]{
                        getInt(obj, "id"),
                        getString(obj, "barcode"),
                        getInt(obj, "quantity"),
                        getNullableLong(obj, "expiry_date"),
                        getString(obj, "product_name"),
                        getString(obj, "image_url"),
                        getString(obj, "storage_location"),
                        getLong(obj, "opened_date"),
                        getInt(obj, "shelf_life_after_opening_days")
                });
                break;
            case "categories_definitions":
                db.execSQL(UPSERT_CATEGORY_DEFINITION_SQL, new Object[]{
                        getInt(obj, "category_id"),
                        getString(obj, "tag_name"),
                        getString(obj, "display_name_it"),
                        getString(obj, "language_code"),
                        getString(obj, "color_hex")
                });
                break;
            case "product_category_links":
                db.execSQL(UPSERT_PRODUCT_CATEGORY_LINK_SQL, new Object[]{
                        getInt(obj, "product_id_fk"),
                        getInt(obj, "category_id_fk")
                });
                break;
            case "storage_locations":
                db.execSQL(UPSERT_STORAGE_LOCATION_SQL, new Object[]{
                        getInt(obj, "id"),
                        getString(obj, "name"),
                        getString(obj, "internal_key"),
                        getInt(obj, "order_index"),
                        getInt(obj, "is_default"),
                        getInt(obj, "is_predefined")
                });
                break;
            default:
                android.util.Log.w("SyncManager",
                        "applyUpsert: unknown table '" + tbl + "' — change ignored");
                break;
        }
    }

    private void applyDelete(String tbl, String pkVal) {
        switch (tbl) {
            case "products":
                db.execSQL(DELETE_PRODUCT_SQL, new Object[]{Long.parseLong(pkVal)});
                break;
            case "categories_definitions":
                db.execSQL(DELETE_CATEGORY_DEFINITION_SQL,
                        new Object[]{Long.parseLong(pkVal)});
                break;
            case "product_category_links": {
                String[] parts = pkVal.split(",", 2);
                if (parts.length < 2) {
                    throw new IllegalArgumentException(
                            "Invalid composite pk_val for product_category_links: " + pkVal);
                }
                db.execSQL(DELETE_PRODUCT_CATEGORY_LINK_SQL,
                        new Object[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])});
                break;
            }
            case "storage_locations":
                db.execSQL(DELETE_STORAGE_LOCATION_SQL,
                        new Object[]{Long.parseLong(pkVal)});
                break;
            default:
                android.util.Log.w("SyncManager",
                        "applyDelete: unknown table '" + tbl + "' — change ignored");
                break;
        }
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }

    private static int getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? 0 : el.getAsInt();
    }

    private static long getLong(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? 0L : el.getAsLong();
    }

    private static Long getNullableLong(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsLong();
    }
}
