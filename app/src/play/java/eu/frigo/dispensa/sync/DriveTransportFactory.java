package eu.frigo.dispensa.sync;

import android.accounts.Account;
import android.content.Context;

import androidx.preference.PreferenceManager;

import eu.frigo.dispensa.util.DebugLogger;

/**
 * Play-flavor implementation of the Drive transport factory.
 *
 * <p>Returns a {@link GoogleDriveSyncTransport} when both the {@code sync_drive_enabled}
 * preference is {@code true} and the user has a signed-in Google account.
 *
 * <h3>Household mode</h3>
 * When {@link HouseholdManager#getHouseholdFolderId(Context)} returns a non-null folder ID
 * the transport operates in <em>household mode</em>: each device uploads its own
 * {@code dispensa_{deviceId}.json} file to the shared folder and downloads all peer files
 * on sync.
 *
 * <p>Falls back to <em>solo mode</em> (single {@code appDataFolder} file) when no household
 * folder ID is stored or when the device ID has not yet been initialised.
 *
 * <p>Returns {@code null} if Drive sync is disabled or the user is not signed in.
 */
public class DriveTransportFactory {

    /** Preference key that enables / disables Google Drive sync (play flavor only). */
    public static final String PREF_SYNC_DRIVE_ENABLED = "sync_drive_enabled";

    /**
     * Preference key for the signed-in Google account email (play flavor only).
     * Set by {@link eu.frigo.dispensa.ui.SyncSettingsHelper} after a successful Credential
     * Manager sign-in; cleared on sign-out.
     */
    public static final String PREF_SIGNED_IN_EMAIL = "sync_drive_signed_in_email";

    /** Account type for Google accounts, used when constructing an {@link Account} object. */
    public static final String GOOGLE_ACCOUNT_TYPE = "com.google";

    private DriveTransportFactory() {
        // utility class
    }

    /**
     * Creates a {@link SyncTransport} backed by Google Drive, or {@code null} if Drive sync
     * is disabled or the user is not signed in.
     *
     * @param context     application context
     * @param syncManager the shared {@link SyncManager} instance (unused currently; kept for
     *                    API compatibility)
     * @return a ready-to-use transport, or {@code null}
     */
    public static SyncTransport create(Context context, SyncManager syncManager) {
        boolean enabled = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(PREF_SYNC_DRIVE_ENABLED, false);
        if (!enabled) {
            DebugLogger.i("DriveTransportFactory", "create: Drive sync is disabled — returning null");
            return null;
        }

        String email = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(PREF_SIGNED_IN_EMAIL, null);
        if (email == null || email.isEmpty()) {
            DebugLogger.w("DriveTransportFactory",
                    "create: Drive sync enabled but no signed-in account — returning null");
            return null;
        }

        Account account = new Account(email, GOOGLE_ACCOUNT_TYPE);

        String householdFolderId = HouseholdManager.getHouseholdFolderId(context);
        if (householdFolderId != null) {
            // Device ID is initialised by SyncManager; fall back to solo mode if not yet set.
            String deviceId = PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getString(SyncManager.PREFS_KEY_DEVICE_ID, null);
            if (deviceId != null) {
                DebugLogger.i("DriveTransportFactory",
                        "create: returning household transport, folderId=" + householdFolderId
                                + " deviceId=" + deviceId);
                return new GoogleDriveSyncTransport(context, account,
                        householdFolderId, deviceId);
            }
            DebugLogger.w("DriveTransportFactory",
                    "create: householdFolderId set but deviceId not yet initialised — falling back to solo mode");
        }

        DebugLogger.i("DriveTransportFactory",
                "create: returning solo transport, account=" + email);
        return new GoogleDriveSyncTransport(context, account);
    }
}
