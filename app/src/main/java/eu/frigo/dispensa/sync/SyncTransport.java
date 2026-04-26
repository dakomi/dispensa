package eu.frigo.dispensa.sync;

/**
 * Transport-agnostic interface for pushing/pulling sync change blobs between devices.
 * Implementations include {@code LocalNetworkSyncTransport} (both flavors)
 * and {@code GoogleDriveSyncTransport} (play flavor only).
 */
public interface SyncTransport {
    /**
     * Push a serialised change blob to remote peer(s).
     *
     * @param data     JSON byte blob produced by {@link SyncManager#exportChanges(long)}
     * @param callback result callback invoked on completion or error
     */
    void push(byte[] data, SyncCallback callback);

    /**
     * Pull the latest change blob from a remote peer.
     *
     * @param callback invoked with the received blob on success, or with the error on failure
     */
    void pull(SyncCallback callback);
}
