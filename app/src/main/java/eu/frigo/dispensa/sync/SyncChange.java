package eu.frigo.dispensa.sync;

/**
 * Represents a single row from the {@code sync_changes} table.
 * Used internally for JSON serialisation of the sync wire format.
 */
class SyncChange {
    /** Name of the table that was changed. */
    String tbl;
    /**
     * Stringified primary key. For tables with a single integer PK this is the id as a string.
     * For composite PKs (e.g. {@code product_category_links}) the values are comma-separated.
     */
    String pkVal;
    /** Operation type: {@code "UPSERT"} or {@code "DELETE"}. */
    String op;
    /** Full row serialised as a JSON object. {@code null} for DELETE operations. */
    String rowJson;
    /** Global Lamport clock value assigned by the originating device. */
    long clock;
    /** UUID of the device that originated this change. Populated at export time. */
    String deviceId;
}
