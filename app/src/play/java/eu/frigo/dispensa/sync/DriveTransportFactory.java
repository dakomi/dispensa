package eu.frigo.dispensa.sync;

import android.content.Context;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import androidx.preference.PreferenceManager;

/**
 * Play-flavor implementation of the Drive transport factory.
 *
 * <p>Returns a {@link GoogleDriveSyncTransport} when both the {@code sync_drive_enabled}
 * preference is {@code true} and the user has a signed-in Google account.
 * Returns {@code null} otherwise so the caller can skip the Drive sync cycle gracefully.
 */
public class DriveTransportFactory {

    /** Preference key that enables / disables Google Drive sync (play flavor only). */
    public static final String PREF_SYNC_DRIVE_ENABLED = "sync_drive_enabled";

    private DriveTransportFactory() {
        // utility class
    }

    /**
     * Creates a {@link SyncTransport} backed by Google Drive, or {@code null} if Drive sync
     * is disabled or the user is not signed in.
     *
     * @param context     application context
     * @param syncManager the shared {@link SyncManager} instance
     * @return a ready-to-use transport, or {@code null}
     */
    public static SyncTransport create(Context context, SyncManager syncManager) {
        boolean enabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(PREF_SYNC_DRIVE_ENABLED, false);
        if (!enabled) return null;

        GoogleSignInAccount signInAccount = GoogleSignIn.getLastSignedInAccount(context);
        if (signInAccount == null || signInAccount.getAccount() == null) return null;

        return new GoogleDriveSyncTransport(context, signInAccount.getAccount());
    }
}
