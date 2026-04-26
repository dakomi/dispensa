package eu.frigo.dispensa.sync;

import android.content.Context;

/**
 * F-Droid-flavor implementation of the Drive transport factory.
 *
 * <p>Always returns {@code null} — Google Drive sync is not available in the F-Droid build.
 * The {@link eu.frigo.dispensa.work.SyncWorker} skips the Drive sync cycle when this
 * factory returns {@code null}.
 */
public class DriveTransportFactory {

    /** Preference key defined here for consistency; Drive sync is always disabled in fdroid. */
    public static final String PREF_SYNC_DRIVE_ENABLED = "sync_drive_enabled";

    private DriveTransportFactory() {
        // utility class
    }

    /**
     * Always returns {@code null} in the F-Droid flavor — Drive sync is unavailable.
     *
     * @param context     unused
     * @param syncManager unused
     * @return {@code null}
     */
    public static SyncTransport create(Context context, SyncManager syncManager) {
        return null;
    }
}
