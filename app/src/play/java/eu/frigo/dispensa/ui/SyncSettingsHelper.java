package eu.frigo.dispensa.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;

import eu.frigo.dispensa.R;
import eu.frigo.dispensa.sync.DriveTransportFactory;

/**
 * Play-flavor helper that injects Google Drive sync preferences into {@link SettingsFragment}.
 *
 * <p>Inflates {@code preferences_sync_drive.xml} into the existing sync category and wires up:
 * <ul>
 *   <li>{@code sync_drive_sign_in} — launches the Google Sign-In flow when the user is not
 *       yet signed in.</li>
 *   <li>{@code sync_drive_account} — displays the currently signed-in Google account email
 *       (or hidden when no account is present).</li>
 *   <li>{@code sync_drive_sign_out} — signs the user out and disables Drive sync.</li>
 * </ul>
 *
 * <p>Call {@link #setup(PreferenceFragmentCompat)} after
 * {@code setPreferencesFromResource()} in {@code onCreatePreferences()}, then call
 * {@link #setSignInLauncher(PreferenceFragmentCompat, ActivityResultLauncher)} immediately
 * after to wire the sign-in button.
 */
public class SyncSettingsHelper {

    private static final String TAG = "SyncSettingsHelper";

    static final String KEY_SIGN_IN = "sync_drive_sign_in";
    static final String KEY_ACCOUNT = "sync_drive_account";
    static final String KEY_SIGN_OUT = "sync_drive_sign_out";

    private SyncSettingsHelper() {}

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Inflates Drive-specific preferences into the {@code pref_cat_sync} category and
     * sets up sign-out handling. Also updates sign-in/sign-out visibility to match the
     * current authentication state.
     */
    public static void setup(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();

        androidx.preference.PreferenceCategory syncCategory =
                fragment.findPreference("pref_cat_sync");
        if (syncCategory == null) {
            Log.w(TAG, "pref_cat_sync not found — Drive prefs not added.");
            return;
        }

        // Inflate Drive prefs from the play-flavor XML resource
        fragment.addPreferencesFromResource(R.xml.preferences_sync_drive);

        // Move inflated prefs from the root screen into the sync category
        PreferenceScreen rootScreen = fragment.getPreferenceScreen();
        Preference driveEnabled = rootScreen.findPreference(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED);
        Preference signInPref   = rootScreen.findPreference(KEY_SIGN_IN);
        Preference accountPref  = rootScreen.findPreference(KEY_ACCOUNT);
        Preference signOutPref  = rootScreen.findPreference(KEY_SIGN_OUT);

        if (driveEnabled != null) {
            rootScreen.removePreference(driveEnabled);
            syncCategory.addPreference(driveEnabled);
        }
        if (signInPref != null) {
            rootScreen.removePreference(signInPref);
            syncCategory.addPreference(signInPref);
        }
        if (accountPref != null) {
            rootScreen.removePreference(accountPref);
            syncCategory.addPreference(accountPref);
        }
        if (signOutPref != null) {
            rootScreen.removePreference(signOutPref);
            syncCategory.addPreference(signOutPref);
            signOutPref.setOnPreferenceClickListener(pref -> {
                signOut(context, driveEnabled, accountPref, signInPref, signOutPref);
                return true;
            });
        }

        // Initialise visibility based on current sign-in state
        refreshSignInState(fragment);
    }

    // ── Sign-in launcher ──────────────────────────────────────────────────────

    /**
     * Wires the {@code sync_drive_sign_in} preference to launch a Google Sign-In intent
     * via the provided {@code launcher}.  Call this after {@link #setup} so that the
     * preference already exists in the hierarchy.
     *
     * @param fragment the host fragment
     * @param launcher an {@code ActivityResultLauncher<Intent>} registered in the fragment's
     *                 {@code onCreate()} via {@code registerForActivityResult}
     */
    public static void setSignInLauncher(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<Intent> launcher) {
        Preference signInPref = fragment.findPreference(KEY_SIGN_IN);
        if (signInPref != null) {
            signInPref.setOnPreferenceClickListener(pref -> {
                launchSignIn(fragment.requireContext(), launcher);
                return true;
            });
        }
    }

    /**
     * Handles the result returned from the Google Sign-In activity.  On success the
     * account summary is refreshed and Drive sync is automatically enabled.
     *
     * @param fragment the host fragment
     * @param result   the {@link ActivityResult} delivered to the launcher callback
     */
    public static void handleSignInResult(PreferenceFragmentCompat fragment,
            ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            Log.d(TAG, "Sign-in cancelled or failed (resultCode=" + result.getResultCode() + ")");
            return;
        }
        Task<GoogleSignInAccount> task =
                GoogleSignIn.getSignedInAccountFromIntent(result.getData());
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            Log.d(TAG, "Sign-in successful: " + account.getEmail());

            // Auto-enable Drive sync now that the user is signed in
            PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                    .edit()
                    .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, true)
                    .apply();

            refreshSignInState(fragment);
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(
                            R.string.notify_sync_signed_in, account.getEmail()),
                    Toast.LENGTH_SHORT).show();

        } catch (ApiException e) {
            Log.w(TAG, "Google Sign-In failed, statusCode=" + e.getStatusCode(), e);
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(R.string.notify_sync_sign_in_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when the {@code sync_drive_enabled} toggle changes.  If Drive sync was enabled
     * but the user is not yet signed in, the preference is reverted to {@code false} and the
     * Google Sign-In flow is launched automatically.
     *
     * @param fragment the host fragment
     * @param enabled  the new value of the Drive-sync toggle
     * @param launcher the sign-in launcher registered in the fragment
     */
    public static void onDriveEnabledChanged(PreferenceFragmentCompat fragment,
            boolean enabled, ActivityResultLauncher<Intent> launcher) {
        if (enabled) {
            GoogleSignInAccount account =
                    GoogleSignIn.getLastSignedInAccount(fragment.requireContext());
            if (account == null) {
                // Revert the toggle and prompt the user to sign in first
                PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                        .edit()
                        .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false)
                        .apply();
                Preference drivePref = fragment.findPreference(
                        DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED);
                if (drivePref instanceof androidx.preference.CheckBoxPreference) {
                    ((androidx.preference.CheckBoxPreference) drivePref).setChecked(false);
                }
                launchSignIn(fragment.requireContext(), launcher);
            }
        }
        refreshSignInState(fragment);
    }

    // ── Account summary ───────────────────────────────────────────────────────

    /**
     * Refreshes sign-in/sign-out preference visibility and updates the account summary
     * to reflect the current authentication state.
     */
    public static void refreshAccountSummary(PreferenceFragmentCompat fragment) {
        refreshSignInState(fragment);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Updates the visibility of sign-in, account, and sign-out preferences to match the
     * current Google Sign-In state.
     */
    static void refreshSignInState(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        boolean signedIn = (account != null && account.getEmail() != null);

        Preference signInPref  = fragment.findPreference(KEY_SIGN_IN);
        Preference accountPref = fragment.findPreference(KEY_ACCOUNT);
        Preference signOutPref = fragment.findPreference(KEY_SIGN_OUT);

        if (signInPref  != null) signInPref.setVisible(!signedIn);
        if (signOutPref != null) signOutPref.setVisible(signedIn);
        if (accountPref != null) {
            accountPref.setVisible(signedIn);
            if (signedIn) {
                accountPref.setSummary(account.getEmail());
            }
        }
    }

    private static void launchSignIn(Context context, ActivityResultLauncher<Intent> launcher) {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_APPDATA))
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(context, options);
        launcher.launch(client.getSignInIntent());
    }

    private static void signOut(Context context, Preference driveEnabledPref,
            Preference accountPref, Preference signInPref, Preference signOutPref) {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(context, options);
        client.signOut().addOnCompleteListener(task -> {
            // Disable Drive sync after sign-out
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false)
                    .apply();
            if (driveEnabledPref instanceof androidx.preference.CheckBoxPreference) {
                ((androidx.preference.CheckBoxPreference) driveEnabledPref).setChecked(false);
            }

            // Update visibility
            if (signInPref  != null) signInPref.setVisible(true);
            if (accountPref != null) accountPref.setVisible(false);
            if (signOutPref != null) signOutPref.setVisible(false);

            Toast.makeText(context,
                    context.getString(R.string.notify_sync_signed_out),
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Signed out of Google.");
        });
    }
}
