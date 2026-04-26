package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Base64;

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

        syncManager = new SyncManager(mockDb, mockPrefs);
    }

    // ── exportChanges ─────────────────────────────────────────────────────────

    @Test
    public void exportChanges_returnsValidJsonBlob_whenCursorHasRows() {
        Cursor cursor = buildSingleRowCursor(
                "products", "['1']", "product_name", "Apple",
                1L, 5L, new byte[]{0x01, 0x02, 0x03, 0x04}, 1L, 0L);
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
        assertEquals("products", change.table);
        assertEquals("['1']", change.pk);
        assertEquals("product_name", change.cid);
        assertEquals("Apple", change.val);
        assertEquals(1L, change.colVersion);
        assertEquals(5L, change.dbVersion);
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{0x01, 0x02, 0x03, 0x04}),
                change.siteId);
        assertEquals(1L, change.cl);
        assertEquals(0L, change.seq);
    }

    @Test
    public void exportChanges_handlesNullVal() {
        Cursor cursor = buildSingleRowCursor(
                "products", "['2']", "expiry_date", null,
                1L, 3L, new byte[]{0x0A}, 1L, 0L);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        byte[] blob = syncManager.exportChanges(0L);

        String json = new String(blob, StandardCharsets.UTF_8);
        SyncBlob parsed = new Gson().fromJson(json, SyncBlob.class);
        assertEquals(1, parsed.changes.size());
        assertTrue(parsed.changes.get(0).val == null);
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
    public void importChanges_insertsEachChangeIntoDatabase() {
        // Build a blob with two changes
        SyncChange c1 = makeChange("products", "['1']", "product_name", "Milk",
                1L, 2L, new byte[]{0x11}, 1L, 0L);
        SyncChange c2 = makeChange("storage_locations", "['5']", "name", "Fridge",
                1L, 3L, new byte[]{0x22}, 1L, 0L);
        SyncBlob blob = new SyncBlob(java.util.Arrays.asList(c1, c2));
        byte[] blobBytes = new Gson().toJson(blob).getBytes(StandardCharsets.UTF_8);

        syncManager.importChanges(blobBytes);

        verify(mockDb, times(2)).execSQL(anyString(), any(Object[].class));
    }

    @Test
    public void importChanges_passesCorrectParametersToExecSql() {
        byte[] siteIdBytes = new byte[]{0x01, 0x02, 0x03};
        SyncChange c = makeChange("products", "['1']", "quantity", "3",
                2L, 7L, siteIdBytes, 1L, 0L);
        SyncBlob blob = new SyncBlob(java.util.Collections.singletonList(c));
        byte[] blobBytes = new Gson().toJson(blob).getBytes(StandardCharsets.UTF_8);

        syncManager.importChanges(blobBytes);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(mockDb).execSQL(anyString(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        assertEquals("products", args[0]);
        assertEquals("['1']", args[1]);
        assertEquals("quantity", args[2]);
        assertEquals("3", args[3]);
        assertEquals(2L, args[4]);
        assertEquals(7L, args[5]);
        assertArrayEquals(siteIdBytes, (byte[]) args[6]);
        assertEquals(1L, args[7]);
        assertEquals(0L, args[8]);
    }

    @Test
    public void importChanges_doesNothing_whenBlobIsEmpty() {
        SyncBlob blob = new SyncBlob(new java.util.ArrayList<>());
        byte[] blobBytes = new Gson().toJson(blob).getBytes(StandardCharsets.UTF_8);

        syncManager.importChanges(blobBytes);

        verify(mockDb, never()).execSQL(anyString(), any(Object[].class));
    }

    @Test
    public void importChanges_doesNothing_whenBlobIsNull() {
        syncManager.importChanges("null".getBytes(StandardCharsets.UTF_8));

        verify(mockDb, never()).execSQL(anyString(), any(Object[].class));
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    public void roundTrip_exportThenImport_callsExecSqlWithOriginalData() {
        byte[] siteIdBytes = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
        Cursor cursor = buildSingleRowCursor(
                "products", "['42']", "product_name", "Yogurt",
                3L, 10L, siteIdBytes, 2L, 1L);
        when(mockDb.query(anyString(), any(Object[].class))).thenReturn(cursor);

        // Export from one SyncManager
        byte[] blob = syncManager.exportChanges(0L);

        // Import into a second SyncManager backed by a fresh mock db
        SupportSQLiteDatabase importDb = mock(SupportSQLiteDatabase.class);
        SyncManager importManager = new SyncManager(importDb, mock(SharedPreferences.class));
        importManager.importChanges(blob);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(importDb).execSQL(anyString(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        assertEquals("products", args[0]);
        assertEquals("['42']", args[1]);
        assertEquals("product_name", args[2]);
        assertEquals("Yogurt", args[3]);
        assertEquals(3L, args[4]);
        assertEquals(10L, args[5]);
        assertArrayEquals(siteIdBytes, (byte[]) args[6]);
        assertEquals(2L, args[7]);
        assertEquals(1L, args[8]);
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
     * Builds a mock {@link Cursor} that returns exactly one row.
     * {@code val} may be {@code null} to simulate a DB NULL column value.
     */
    private Cursor buildSingleRowCursor(String table, String pk, String cid, String val,
            long colVersion, long dbVersion, byte[] siteId, long cl, long seq) {
        Cursor cursor = mock(Cursor.class);
        when(cursor.moveToNext()).thenReturn(true, false);
        when(cursor.getString(0)).thenReturn(table);
        when(cursor.getString(1)).thenReturn(pk);
        when(cursor.getString(2)).thenReturn(cid);
        when(cursor.isNull(3)).thenReturn(val == null);
        when(cursor.getString(3)).thenReturn(val);
        when(cursor.getLong(4)).thenReturn(colVersion);
        when(cursor.getLong(5)).thenReturn(dbVersion);
        when(cursor.getBlob(6)).thenReturn(siteId);
        when(cursor.getLong(7)).thenReturn(cl);
        when(cursor.getLong(8)).thenReturn(seq);
        return cursor;
    }

    private SyncChange makeChange(String table, String pk, String cid, String val,
            long colVersion, long dbVersion, byte[] siteId, long cl, long seq) {
        SyncChange c = new SyncChange();
        c.table = table;
        c.pk = pk;
        c.cid = cid;
        c.val = val;
        c.colVersion = colVersion;
        c.dbVersion = dbVersion;
        c.siteId = Base64.getEncoder().encodeToString(siteId);
        c.cl = cl;
        c.seq = seq;
        return c;
    }
}
