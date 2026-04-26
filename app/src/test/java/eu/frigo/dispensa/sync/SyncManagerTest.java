package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.google.gson.Gson;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

public class SyncManagerTest {

    private SupportSQLiteDatabase mockDb;
    private SharedPreferences mockPrefs;
    private SharedPreferences.Editor mockEditor;
    private SyncManager syncManager;

    @Before
    public void setUp() {
        mockDb = mock(SupportSQLiteDatabase.class);
        mockPrefs = mock(SharedPreferences.class);
        mockEditor = mock(SharedPreferences.Editor.class);

        when(mockPrefs.edit()).thenReturn(mockEditor);
        when(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor);
        when(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor);

        // Return a stable device ID so tests are deterministic
        when(mockPrefs.getString(eq(SyncManager.PREFS_KEY_DEVICE_ID), isNull()))
                .thenReturn("test-device-a");
        when(mockPrefs.getLong(anyString(), anyLong())).thenReturn(0L);

        syncManager = new SyncManager(mockDb, mockPrefs);
    }

    // ── exportChanges ─────────────────────────────────────────────────────────

    @Test
    public void exportChanges_returnsValidJsonBlob_whenCursorHasRows() {
        Cursor cursor = buildExportCursor("products", "1", "UPSERT",
                "{\"id\":1,\"product_name\":\"Apple\"}", 5L);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        byte[] blob = syncManager.exportChanges(0L);

        assertNotNull(blob);
        assertTrue(blob.length > 0);

        String json = new String(blob, StandardCharsets.UTF_8);
        SyncBlob parsed = new Gson().fromJson(json, SyncBlob.class);
        assertNotNull(parsed);
        assertEquals(1, parsed.version);
        assertNotNull(parsed.changes);
        assertEquals(1, parsed.changes.size());

        SyncChange change = parsed.changes.get(0);
        assertEquals("products", change.tbl);
        assertEquals("1", change.pkVal);
        assertEquals("UPSERT", change.op);
        assertEquals("{\"id\":1,\"product_name\":\"Apple\"}", change.rowJson);
        assertEquals(5L, change.clock);
        assertEquals("test-device-a", change.deviceId);
    }

    @Test
    public void exportChanges_handlesNullRowJson_forDelete() {
        Cursor cursor = buildExportCursor("products", "2", "DELETE", null, 3L);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        byte[] blob = syncManager.exportChanges(0L);

        String json = new String(blob, StandardCharsets.UTF_8);
        SyncBlob parsed = new Gson().fromJson(json, SyncBlob.class);
        assertEquals(1, parsed.changes.size());
        assertEquals("DELETE", parsed.changes.get(0).op);
        assertNull(parsed.changes.get(0).rowJson);
    }

    @Test
    public void exportChanges_returnsEmptyChangesList_whenCursorIsEmpty() {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToNext()).thenReturn(false);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        byte[] blob = syncManager.exportChanges(42L);

        assertNotNull(blob);
        String json = new String(blob, StandardCharsets.UTF_8);
        SyncBlob parsed = new Gson().fromJson(json, SyncBlob.class);
        assertNotNull(parsed.changes);
        assertTrue(parsed.changes.isEmpty());
    }

    @Test
    public void exportChanges_passesLastSyncVersionAsBindArg() {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToNext()).thenReturn(false);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        syncManager.exportChanges(99L);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(mockDb).query(anyString(), argsCaptor.capture());
        assertArrayEquals(new Object[]{99L}, argsCaptor.getValue());
    }

    // ── importChanges ─────────────────────────────────────────────────────────

    @Test
    public void importChanges_insertsProductUpsertIntoDatabase() {
        stubZeroLocalClock();

        SyncChange c = makeChange("products", "1", "UPSERT",
                "{\"id\":1,\"barcode\":null,\"quantity\":2,\"expiry_date\":null,"
                        + "\"product_name\":\"Milk\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                5L, "device-b");
        byte[] blobBytes = makeBlobBytes(c);

        syncManager.importChanges(blobBytes);

        verify(mockDb).execSQL(contains("INSERT OR REPLACE INTO products"), any(Object[].class));
    }

    @Test
    public void importChanges_passesCorrectParametersForProductUpsert() {
        stubZeroLocalClock();

        SyncChange c = makeChange("products", "7", "UPSERT",
                "{\"id\":7,\"barcode\":\"123\",\"quantity\":3,\"expiry_date\":null,"
                        + "\"product_name\":\"Yogurt\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                8L, "device-b");
        byte[] blobBytes = makeBlobBytes(c);

        syncManager.importChanges(blobBytes);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(mockDb).execSQL(contains("INSERT OR REPLACE INTO products"), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        assertEquals(7, args[0]);          // id
        assertEquals("123", args[1]);      // barcode
        assertEquals(3, args[2]);          // quantity
        assertNull(args[3]);               // expiry_date nullable → null
        assertEquals("FRIDGE", args[6]);   // storage_location
        assertEquals(0L, args[7]);         // opened_date
        assertEquals(-1, args[8]);         // shelf_life_after_opening_days
    }

    @Test
    public void importChanges_insertsEachChangeIntoDatabase() {
        stubZeroLocalClock();

        SyncChange c1 = makeChange("products", "1", "UPSERT",
                "{\"id\":1,\"barcode\":null,\"quantity\":1,\"expiry_date\":null,"
                        + "\"product_name\":\"Milk\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                2L, "device-b");
        SyncChange c2 = makeChange("storage_locations", "5", "UPSERT",
                "{\"id\":5,\"name\":\"Fridge\",\"internal_key\":\"FRIDGE\","
                        + "\"order_index\":0,\"is_default\":1,\"is_predefined\":1}",
                3L, "device-b");
        byte[] blobBytes = makeBlobBytes(new Gson().toJson(new SyncBlob(
                java.util.Arrays.asList(c1, c2))));

        syncManager.importChanges(blobBytes);

        // Verify the two per-table upserts (neither is sync_changes)
        verify(mockDb).execSQL(contains("INSERT OR REPLACE INTO products"), any(Object[].class));
        verify(mockDb).execSQL(contains("INSERT OR REPLACE INTO storage_locations"),
                any(Object[].class));
        // Verify two change records written to sync_changes
        verify(mockDb, times(2)).execSQL(contains("INTO sync_changes"), any(Object[].class));
    }

    @Test
    public void importChanges_doesNothing_whenBlobIsEmpty() {
        SyncBlob blob = new SyncBlob(new java.util.ArrayList<>());
        byte[] blobBytes = new Gson().toJson(blob).getBytes(StandardCharsets.UTF_8);

        syncManager.importChanges(blobBytes);

        verify(mockDb, never()).execSQL(anyString(), any(Object[].class));
        verify(mockDb, never()).beginTransaction();
    }

    @Test
    public void importChanges_doesNothing_whenBlobIsNull() {
        syncManager.importChanges(null);

        verify(mockDb, never()).execSQL(anyString(), any(Object[].class));
        verify(mockDb, never()).beginTransaction();
    }

    @Test
    public void importChanges_skipsChange_whenLocalClockIsHigher() {
        // Local clock = 10, incoming clock = 5 → incoming loses, no upsert
        Cursor highClockCursor = mock(Cursor.class);
        when(highClockCursor.moveToFirst()).thenReturn(true);
        when(highClockCursor.getLong(0)).thenReturn(10L);
        when(mockDb.query(contains("MAX"), any(Object[].class))).thenReturn(highClockCursor);

        SyncChange c = makeChange("products", "1", "UPSERT",
                "{\"id\":1,\"barcode\":null,\"quantity\":1,\"expiry_date\":null,"
                        + "\"product_name\":\"Milk\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                5L, "device-b");
        byte[] blobBytes = makeBlobBytes(c);

        syncManager.importChanges(blobBytes);

        verify(mockDb, never()).execSQL(contains("INSERT OR REPLACE INTO products"),
                any(Object[].class));
    }

    @Test
    public void importChanges_setsAndReleasesImportLock() {
        stubZeroLocalClock();

        SyncChange c = makeChange("products", "1", "UPSERT",
                "{\"id\":1,\"barcode\":null,\"quantity\":1,\"expiry_date\":null,"
                        + "\"product_name\":\"Milk\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                5L, "device-b");
        byte[] blobBytes = makeBlobBytes(c);

        syncManager.importChanges(blobBytes);

        verify(mockDb).execSQL(eq("UPDATE sync_import_lock SET locked = 1"));
        verify(mockDb).execSQL(eq("UPDATE sync_import_lock SET locked = 0"));
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    public void roundTrip_exportThenImport_appliesCorrectProductUpsert() {
        Cursor exportCursor = buildExportCursor("products", "42", "UPSERT",
                "{\"id\":42,\"barcode\":\"999\",\"quantity\":1,\"expiry_date\":null,"
                        + "\"product_name\":\"Yogurt\",\"image_url\":null,"
                        + "\"storage_location\":\"FRIDGE\",\"opened_date\":0,"
                        + "\"shelf_life_after_opening_days\":-1}",
                10L);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(exportCursor);

        // Export from first manager
        byte[] blob = syncManager.exportChanges(0L);

        // Import into second manager (different device)
        SupportSQLiteDatabase importDb = mock(SupportSQLiteDatabase.class);
        SharedPreferences importPrefs = mock(SharedPreferences.class);
        when(importPrefs.getString(eq(SyncManager.PREFS_KEY_DEVICE_ID), isNull()))
                .thenReturn("test-device-b");
        when(importPrefs.getLong(anyString(), anyLong())).thenReturn(0L);
        SharedPreferences.Editor importEditor = mock(SharedPreferences.Editor.class);
        when(importPrefs.edit()).thenReturn(importEditor);
        when(importEditor.putString(anyString(), anyString())).thenReturn(importEditor);
        // Stub zero local clock for the import device
        Cursor zeroClockCursor = mock(Cursor.class);
        when(zeroClockCursor.moveToFirst()).thenReturn(true);
        when(zeroClockCursor.getLong(0)).thenReturn(0L);
        when(importDb.query(contains("MAX"), any(Object[].class))).thenReturn(zeroClockCursor);

        SyncManager importManager = new SyncManager(importDb, importPrefs);
        importManager.importChanges(blob);

        // Verify the product row was upserted
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(importDb).execSQL(contains("INSERT OR REPLACE INTO products"),
                argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertEquals(42, args[0]);        // id
        assertEquals("999", args[1]);     // barcode
        assertEquals(1, args[2]);         // quantity
        assertNull(args[3]);              // expiry_date
        assertEquals("Yogurt", args[4]);  // product_name
    }

    // ── SharedPreferences persistence ─────────────────────────────────────────

    @Test
    public void getLastSyncVersion_returnsZero_whenNoPreviousSync() {
        when(mockPrefs.getLong(SyncManager.PREFS_KEY_LAST_SYNC_VERSION, 0L)).thenReturn(0L);

        assertEquals(0L, syncManager.getLastSyncVersion());
    }

    @Test
    public void getLastSyncVersion_returnsStoredValue() {
        when(mockPrefs.getLong(SyncManager.PREFS_KEY_LAST_SYNC_VERSION, 0L)).thenReturn(42L);

        assertEquals(42L, syncManager.getLastSyncVersion());
    }

    @Test
    public void persistLastSyncVersion_savesVersionToSharedPrefs() {
        syncManager.persistLastSyncVersion(77L);

        verify(mockEditor).putLong(eq(SyncManager.PREFS_KEY_LAST_SYNC_VERSION), eq(77L));
        verify(mockEditor).apply();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a mock {@link Cursor} representing one row from the export query.
     * Columns: tbl(0), pk_val(1), op(2), row_json(3), clock(4).
     */
    private Cursor buildExportCursor(String tbl, String pkVal, String op,
            String rowJson, long clock) {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToNext()).thenReturn(true, false);
        when(cursor.getString(0)).thenReturn(tbl);
        when(cursor.getString(1)).thenReturn(pkVal);
        when(cursor.getString(2)).thenReturn(op);
        when(cursor.isNull(3)).thenReturn(rowJson == null);
        when(cursor.getString(3)).thenReturn(rowJson);
        when(cursor.getLong(4)).thenReturn(clock);
        return cursor;
    }

    private SyncChange makeChange(String tbl, String pkVal, String op,
            String rowJson, long clock, String deviceId) {
        SyncChange c = new SyncChange();
        c.tbl = tbl;
        c.pkVal = pkVal;
        c.op = op;
        c.rowJson = rowJson;
        c.clock = clock;
        c.deviceId = deviceId;
        return c;
    }

    private byte[] makeBlobBytes(SyncChange change) {
        SyncBlob blob = new SyncBlob(java.util.Collections.singletonList(change));
        return new Gson().toJson(blob).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] makeBlobBytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Stubs the MAX-clock query to return 0 (no prior local changes). */
    private void stubZeroLocalClock() {
        Cursor zeroClockCursor = mock(Cursor.class);
        when(zeroClockCursor.moveToFirst()).thenReturn(true);
        when(zeroClockCursor.getLong(0)).thenReturn(0L);
        when(mockDb.query(contains("MAX"), any(Object[].class))).thenReturn(zeroClockCursor);
    }
}
