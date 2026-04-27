package eu.frigo.dispensa.sync;

import java.util.List;

/**
 * Top-level JSON wrapper for the sync wire format.
 * {@code version} allows future format evolution without breaking older clients.
 */
class SyncBlob {
    /** Wire-format version. Currently always 1. */
    int version = 1;
    /**
     * UUID of the device that produced this blob.
     * Populated by {@link SyncManager#exportChanges} and used by
     * {@link LocalNetworkSyncTransport} to enforce the device trust list.
     * Devices that do not include this field (older clients) are treated as
     * unidentifiable and rejected until they upgrade.
     * {@code null} is also used by the household Drive merge blob, which has
     * multiple sources — Drive sync does not go through the trust check.
     */
    String senderDeviceId;
    List<SyncChange> changes;

    SyncBlob(String senderDeviceId, List<SyncChange> changes) {
        this.senderDeviceId = senderDeviceId;
        this.changes = changes;
    }
}
