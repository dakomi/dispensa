package eu.frigo.dispensa.ui;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;

import eu.frigo.dispensa.R;

/**
 * Play-flavor helper that injects Google Drive sync preferences into {@link SettingsFragment}.
 *
 * <p>Inflates {@code preferences_sync_drive.xml} into the existing sync category and wires up:
 * <ul>
 *   <li>{@code sync_drive_account} — displays the currently signed-in Google account email
 *       (or "Not signed in" when no account is present).</li>
 *   <li>{@code sync_drive_sign_out} — signs the user out of Google Sign-In and disables
 *       Drive sync.</li>
 * </ul>
 */
public class SyncSettingsHelper {

    private static final String TAG = "SyncSettingsHelper";

    private SyncSettingsHelper() {}

    /**
     * Inflates Drive-specific preferences into the {@code pref_cat_sync} category of the
     * given fragment and sets up sign-out handling.
     *
     * @param fragment the fragment whose preference screen should be augmented
     */
    public static void setup(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();

        // Add Drive prefs to the sync category
        PreferenceScreen screen = fragment.getPreferenceScreen();
        androidx.preference.PreferenceCategory syncCategory =
                fragment.findPreference("pref_cat_sync");
        if (syncCategory == null) {
            Log.w(TAG, "pref_cat_sync category not found — Drive prefs not added.");
            return;
        }

        // Inflate Drive prefs from the play-flavor XML resource
        fragment.addPreferencesFromResource(R.xml.preferences_sync_drive);

        // Move the inflated prefs from the root screen into the sync category
        PreferenceScreen rootScreen = fragment.getPreferenceScreen();
        // The inflated preferences land at root level; move them into the sync category
        Preference driveEnabled = rootScreen.findPreference("sync_drive_enabled");
        Preference driveAccount = rootScreen.findPreference("sync_drive_account");
        Preference driveSignOut = rootScreen.findPreference("sync_drive_sign_out");

        if (driveEnabled != null) {
            rootScreen.removePreference(driveEnabled);
            syncCategory.addPreference(driveEnabled);
        }
        if (driveAccount != null) {
            rootScreen.removePreference(driveAccount);
            syncCategory.addPreference(driveAccount);
            updateDriveAccountSummary(context, driveAccount);
        }
        if (driveSignOut != null) {
            rootScreen.removePreference(driveSignOut);
            syncCategory.addPreference(driveSignOut);
            driveSignOut.setOnPreferenceClickListener(pref -> {
                signOut(context, driveEnabled, driveAccount);
                return true;
            });
        }
    }

    /**
     * Refreshes the {@code sync_drive_account} preference summary with the currently
     * signed-in account email (or the "not signed in" string if no account is present).
     */
    public static void refreshAccountSummary(PreferenceFragmentCompat fragment) {
        Preference accountPref = fragment.findPreference("sync_drive_account");
        if (accountPref != null) {
            updateDriveAccountSummary(fragment.requireContext(), accountPref);
        }
    }

    private static void updateDriveAccountSummary(Context context, Preference accountPref) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account != null && account.getEmail() != null) {
            accountPref.setSummary(account.getEmail());
        } else {
            accountPref.setSummary(context.getString(R.string.pref_sync_drive_account_not_signed_in));
        }
    }

    private static void signOut(Context context, Preference driveEnabledPref, Preference accountPref) {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(context, options);
        client.signOut().addOnCompleteListener(task -> {
            // Disable Drive sync preference after sign-out
            if (driveEnabledPref != null) {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .edit()
                        .putBoolean("sync_drive_enabled", false)
                        .apply();
            }
            if (accountPref != null) {
                accountPref.setSummary(
                        context.getString(R.string.pref_sync_drive_account_not_signed_in));
            }
            Toast.makeText(context, context.getString(R.string.notify_sync_signed_out),
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Signed out of Google.");
        });
    }
}
