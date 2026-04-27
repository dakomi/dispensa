package eu.frigo.dispensa.ui;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.preference.PreferenceFragmentCompat;

/**
 * F-Droid flavor implementation of the sync-settings helper.
 *
 * <p>Google Drive sync is not available in the F-Droid flavor, so this class
 * is intentionally a no-op.
 */
public class SyncSettingsHelper {

    private SyncSettingsHelper() {}

    /** No-op — Drive preferences are not available in the F-Droid build. */
    public static void setup(PreferenceFragmentCompat fragment) {}

    /** No-op — Drive sync is not available in the F-Droid build. */
    public static void refreshAccountSummary(PreferenceFragmentCompat fragment) {}

    /** No-op — Drive sync is not available in the F-Droid build. */
    public static void setDriveAuthLauncher(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<IntentSenderRequest> authLauncher) {}

    /** No-op — Drive sync is not available in the F-Droid build. */
    public static void handleAuthorizationResult(PreferenceFragmentCompat fragment,
            ActivityResult result) {}

    /** No-op — Drive sync is not available in the F-Droid build. */
    public static void onDriveEnabledChanged(PreferenceFragmentCompat fragment,
            boolean enabled, ActivityResultLauncher<IntentSenderRequest> authLauncher) {}

    /** No-op — household deep-links are not supported in the F-Droid build. */
    public static void handleHouseholdDeepLink(PreferenceFragmentCompat fragment,
            String folderId) {}
}
