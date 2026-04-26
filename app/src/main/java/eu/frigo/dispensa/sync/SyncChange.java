package eu.frigo.dispensa.sync;

/**
 * Represents a single row from the {@code crsql_changes} virtual table.
 * Used internally for JSON serialisation of the sync wire format.
 */
class SyncChange {
    String table;
    String pk;
    String cid;
    /** String representation of the column value; null if the database value is NULL. */
    String val;
    long colVersion;
    long dbVersion;
    /** Base64-encoded bytes of the CR-SQLite {@code site_id} BLOB. */
    String siteId;
    long cl;
    long seq;
}
