package eu.frigo.dispensa.ui;

import androidx.preference.PreferenceFragmentCompat;

/**
 * F-Droid flavor implementation of the sync-settings helper.
 *
 * <p>Google Drive sync is not available in the F-Droid flavor, so this class
 * is intentionally a no-op.
 */
public class SyncSettingsHelper {

    private SyncSettingsHelper() {}

    /**
     * No-op for the F-Droid flavor — Drive preferences are not injected.
     */
    public static void setup(PreferenceFragmentCompat fragment) {
        // Drive sync is not available in the F-Droid build.
    }

    /**
     * No-op for the F-Droid flavor.
     */
    public static void refreshAccountSummary(PreferenceFragmentCompat fragment) {
        // Drive sync is not available in the F-Droid build.
    }
}
