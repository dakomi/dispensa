package eu.frigo.dispensa.sync;

/**
 * Callback interface for async sync transport results.
 */
public interface SyncCallback {
    void onSuccess(byte[] data);
    void onError(Exception error);
}
