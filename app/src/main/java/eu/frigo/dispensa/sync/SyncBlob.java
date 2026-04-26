package eu.frigo.dispensa.sync;

import java.util.List;

/**
 * Top-level JSON wrapper for the sync wire format.
 * {@code version} allows future format evolution without breaking older clients.
 */
class SyncBlob {
    /** Wire-format version. Currently always 1. */
    int version = 1;
    List<SyncChange> changes;

    SyncBlob(List<SyncChange> changes) {
        this.changes = changes;
    }
}
