package eu.frigo.dispensa.ui;

import android.accounts.Account;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.appcompat.app.AlertDialog;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import eu.frigo.dispensa.R;
import eu.frigo.dispensa.sync.DriveTransportFactory;
import eu.frigo.dispensa.sync.HouseholdManager;
import eu.frigo.dispensa.sync.SyncCallback;
import eu.frigo.dispensa.sync.SyncTransport;
import eu.frigo.dispensa.util.DebugLogger;

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
 * {@link #setDriveAuthLauncher(PreferenceFragmentCompat, ActivityResultLauncher)} immediately
 * after to wire the sign-in button.
 */
public class SyncSettingsHelper {

    private static final String TAG = "SyncSettingsHelper";

    static final String KEY_SIGN_IN = "sync_drive_sign_in";
    static final String KEY_ACCOUNT = "sync_drive_account";
    static final String KEY_SIGN_OUT = "sync_drive_sign_out";
    static final String KEY_TEST_CONNECTION = "sync_drive_test_connection";
    static final String KEY_HOUSEHOLD_STATUS = "sync_drive_household_status";
    static final String KEY_CREATE_HOUSEHOLD = "sync_drive_create_household";
    static final String KEY_JOIN_HOUSEHOLD = "sync_drive_join_household";
    static final String KEY_LEAVE_HOUSEHOLD = "sync_drive_leave_household";

    /** Key of the Drive section collapse/expand toggle preference. */
    private static final String KEY_DRIVE_TOGGLE = "pref_sync_drive_toggle";

    /** SharedPreferences key that persists the Drive section collapsed state. */
    static final String PREF_DRIVE_SECTION_COLLAPSED = "pref_sync_drive_section_collapsed";

    /** Keys of the Drive content preferences controlled by the collapse toggle. */
    private static final String[] DRIVE_SECTION_CHILD_KEYS = {
        DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED,
        KEY_SIGN_IN, KEY_ACCOUNT, KEY_SIGN_OUT,
        KEY_TEST_CONNECTION,
        KEY_HOUSEHOLD_STATUS, KEY_CREATE_HOUSEHOLD, KEY_JOIN_HOUSEHOLD, KEY_LEAVE_HOUSEHOLD
    };

    private SyncSettingsHelper() {}

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * Inflates Drive-specific preferences into the {@code pref_cat_sync_drive} category,
     * adds a collapse/expand toggle, and wires up sign-out handling.
     * Also updates sign-in/sign-out visibility to match the current authentication state.
     */
    public static void setup(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();

        androidx.preference.PreferenceCategory driveCategory =
                fragment.findPreference("pref_cat_sync_drive");
        if (driveCategory == null) {
            Log.w(TAG, "pref_cat_sync_drive not found — Drive prefs not added.");
            return;
        }

        // Add the collapse/expand toggle as the first item in the Drive category
        Preference driveToggle = new Preference(context);
        driveToggle.setKey(KEY_DRIVE_TOGGLE);
        driveToggle.setIconSpaceReserved(false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean collapsed = prefs.getBoolean(PREF_DRIVE_SECTION_COLLAPSED, false);
        driveToggle.setTitle(collapsed
                ? R.string.pref_section_expand : R.string.pref_section_collapse);
        driveCategory.addPreference(driveToggle);

        // Inflate Drive prefs from the play-flavor XML resource
        fragment.addPreferencesFromResource(R.xml.preferences_sync_drive);

        // Move inflated prefs from the root screen into the Drive category
        PreferenceScreen rootScreen = fragment.getPreferenceScreen();
        Preference driveEnabled = rootScreen.findPreference(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED);
        Preference signInPref   = rootScreen.findPreference(KEY_SIGN_IN);
        Preference accountPref  = rootScreen.findPreference(KEY_ACCOUNT);
        Preference signOutPref  = rootScreen.findPreference(KEY_SIGN_OUT);

        if (driveEnabled != null) {
            rootScreen.removePreference(driveEnabled);
            driveCategory.addPreference(driveEnabled);
        }
        if (signInPref != null) {
            rootScreen.removePreference(signInPref);
            driveCategory.addPreference(signInPref);
        }
        if (accountPref != null) {
            rootScreen.removePreference(accountPref);
            driveCategory.addPreference(accountPref);
        }
        if (signOutPref != null) {
            rootScreen.removePreference(signOutPref);
            driveCategory.addPreference(signOutPref);
            signOutPref.setOnPreferenceClickListener(pref -> {
                signOut(context, driveEnabled, accountPref, signInPref, signOutPref);
                return true;
            });
        }

        Preference testConnectionPref = rootScreen.findPreference(KEY_TEST_CONNECTION);
        if (testConnectionPref != null) {
            rootScreen.removePreference(testConnectionPref);
            driveCategory.addPreference(testConnectionPref);
            testConnectionPref.setOnPreferenceClickListener(pref -> {
                testDriveConnection(context);
                return true;
            });
        }

        Preference householdStatusPref = rootScreen.findPreference(KEY_HOUSEHOLD_STATUS);
        if (householdStatusPref != null) {
            rootScreen.removePreference(householdStatusPref);
            driveCategory.addPreference(householdStatusPref);
            householdStatusPref.setOnPreferenceClickListener(pref -> {
                String folderId = HouseholdManager.getHouseholdFolderId(fragment.requireContext());
                if (folderId != null) {
                    String deepLink = HouseholdManager.generateJoinDeepLink(folderId);
                    showDeepLinkDialog(fragment, deepLink);
                }
                return true;
            });
        }

        Preference createHouseholdPref = rootScreen.findPreference(KEY_CREATE_HOUSEHOLD);
        if (createHouseholdPref != null) {
            rootScreen.removePreference(createHouseholdPref);
            driveCategory.addPreference(createHouseholdPref);
            createHouseholdPref.setOnPreferenceClickListener(pref -> {
                showCreateHouseholdDialog(fragment);
                return true;
            });
        }

        Preference joinHouseholdPref = rootScreen.findPreference(KEY_JOIN_HOUSEHOLD);
        if (joinHouseholdPref != null) {
            rootScreen.removePreference(joinHouseholdPref);
            driveCategory.addPreference(joinHouseholdPref);
            joinHouseholdPref.setOnPreferenceClickListener(pref -> {
                showJoinHouseholdDialog(fragment);
                return true;
            });
        }

        Preference leaveHouseholdPref = rootScreen.findPreference(KEY_LEAVE_HOUSEHOLD);
        if (leaveHouseholdPref != null) {
            rootScreen.removePreference(leaveHouseholdPref);
            driveCategory.addPreference(leaveHouseholdPref);
            leaveHouseholdPref.setOnPreferenceClickListener(pref -> {
                leaveHousehold(context, fragment);
                return true;
            });
        }

        // Wire up the Drive section collapse toggle
        driveToggle.setOnPreferenceClickListener(pref -> {
            SharedPreferences drivePrefs = PreferenceManager.getDefaultSharedPreferences(
                    fragment.requireContext());
            boolean nowCollapsed = !drivePrefs.getBoolean(PREF_DRIVE_SECTION_COLLAPSED, false);
            drivePrefs.edit().putBoolean(PREF_DRIVE_SECTION_COLLAPSED, nowCollapsed).apply();
            driveToggle.setTitle(nowCollapsed
                    ? R.string.pref_section_expand : R.string.pref_section_collapse);
            if (nowCollapsed) {
                setDriveSectionChildrenVisible(fragment, false);
            } else {
                // When expanding, let refreshSignInState() restore proper per-pref visibility
                refreshSignInState(fragment);
            }
            return true;
        });

        // Initialise visibility: apply collapse state first, then sign-in state
        if (collapsed) {
            setDriveSectionChildrenVisible(fragment, false);
        } else {
            refreshSignInState(fragment);
        }
    }

    // ── Drive auth launcher ───────────────────────────────────────────────────

    /**
     * Wires the {@code sync_drive_sign_in} preference to launch the Google Sign-In flow
     * via the Android Credential Manager API.  Call this after {@link #setup} so that the
     * preference already exists in the hierarchy.
     *
     * <p>On a successful sign-in the email is persisted in SharedPreferences and Drive scope
     * authorization is requested immediately via {@code authLauncher}.
     *
     * @param fragment    the host fragment
     * @param authLauncher an {@code ActivityResultLauncher<IntentSenderRequest>} registered in
     *                    the fragment's {@code onCreate()} via {@code registerForActivityResult}
     *                    — used for the Drive scope consent screen shown after sign-in
     */
    public static void setDriveAuthLauncher(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<IntentSenderRequest> authLauncher) {
        Preference signInPref = fragment.findPreference(KEY_SIGN_IN);
        if (signInPref != null) {
            signInPref.setOnPreferenceClickListener(pref -> {
                launchSignIn(fragment, authLauncher);
                return true;
            });
        }
    }

    /**
     * Called when the {@code sync_drive_enabled} toggle changes.
     * <ul>
     *   <li>If Drive sync was enabled but the user is not yet signed in, the preference is
     *       reverted to {@code false} and the Google Sign-In flow is launched.</li>
     *   <li>If the user is already signed in, Drive scope authorization is requested via
     *       {@link Identity#getAuthorizationClient} so the toggle only stays on once the
     *       required scopes have been granted.</li>
     * </ul>
     *
     * @param fragment     the host fragment
     * @param enabled      the new value of the Drive-sync toggle
     * @param authLauncher the Drive-authorization launcher registered in the fragment
     */
    public static void onDriveEnabledChanged(PreferenceFragmentCompat fragment,
            boolean enabled, ActivityResultLauncher<IntentSenderRequest> authLauncher) {
        DebugLogger.i(TAG, "onDriveEnabledChanged: enabled=" + enabled);
        if (enabled) {
            String email = PreferenceManager
                    .getDefaultSharedPreferences(fragment.requireContext())
                    .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
            if (email == null || email.isEmpty()) {
                DebugLogger.w(TAG, "Drive enabled but no signed-in account — launching sign-in");
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
                launchSignIn(fragment, authLauncher);
            } else {
                // Account present — verify Drive scope authorization.
                // The toggle is left as-is; onDriveAuthorizationFailed() reverts it on error.
                DebugLogger.i(TAG, "onDriveEnabledChanged: account present, checking Drive scopes");
                launchDriveAuthorization(fragment, authLauncher);
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
     * current sign-in state (read from SharedPreferences).
     * <p>No-op if the Drive section is currently collapsed.</p>
     */
    static void refreshSignInState(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();

        // Do not change child visibility while the section is collapsed
        boolean collapsed = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_DRIVE_SECTION_COLLAPSED, false);
        if (collapsed) return;

        String email = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
        boolean signedIn = (email != null && !email.isEmpty());

        Preference signInPref  = fragment.findPreference(KEY_SIGN_IN);
        Preference accountPref = fragment.findPreference(KEY_ACCOUNT);
        Preference signOutPref = fragment.findPreference(KEY_SIGN_OUT);
        Preference testConnPref = fragment.findPreference(KEY_TEST_CONNECTION);
        Preference householdStatusPref   = fragment.findPreference(KEY_HOUSEHOLD_STATUS);
        Preference createHouseholdPref   = fragment.findPreference(KEY_CREATE_HOUSEHOLD);
        Preference joinHouseholdPref     = fragment.findPreference(KEY_JOIN_HOUSEHOLD);
        Preference leaveHouseholdPref    = fragment.findPreference(KEY_LEAVE_HOUSEHOLD);

        if (signInPref  != null) signInPref.setVisible(!signedIn);
        if (signOutPref != null) signOutPref.setVisible(signedIn);
        if (testConnPref != null) testConnPref.setVisible(signedIn);

        if (accountPref != null) {
            accountPref.setVisible(signedIn);
            if (signedIn) {
                accountPref.setSummary(email);
            }
        }

        // Household prefs visible only when signed in
        boolean inHousehold = signedIn
                && HouseholdManager.getHouseholdFolderId(context) != null;

        if (householdStatusPref != null) {
            householdStatusPref.setVisible(signedIn);
            if (signedIn) {
                householdStatusPref.setSummary(inHousehold
                        ? context.getString(R.string.pref_sync_drive_household_status_active,
                                householdDisplayName(context))
                        : context.getString(R.string.pref_sync_drive_household_status_solo));
            }
            // If in household but folder name not yet stored, fetch it from Drive in the background
            if (inHousehold && HouseholdManager.getHouseholdFolderName(context) == null) {
                String folderId = HouseholdManager.getHouseholdFolderId(context);
                fetchAndStoreFolderName(fragment, folderId);
            }
        }
        if (createHouseholdPref != null) createHouseholdPref.setVisible(signedIn && !inHousehold);
        if (joinHouseholdPref   != null) joinHouseholdPref.setVisible(signedIn && !inHousehold);
        if (leaveHouseholdPref  != null) leaveHouseholdPref.setVisible(inHousehold);
    }

    /**
     * Shows or hides all Drive section content preferences (i.e. all children except the
     * collapse/expand toggle itself).
     */
    private static void setDriveSectionChildrenVisible(PreferenceFragmentCompat fragment,
            boolean visible) {
        for (String key : DRIVE_SECTION_CHILD_KEYS) {
            Preference p = fragment.findPreference(key);
            if (p != null) p.setVisible(visible);
        }
    }

    /**
     * Runs a lightweight Drive connectivity test on a background thread and reports
     * the result via a {@link Toast} on the main thread.
     *
     * <p>Uses {@link DriveTransportFactory#create} to obtain a transport (which verifies
     * sign-in state).  If the user is not signed in, a Toast is shown immediately.
     * Otherwise the {@link SyncTransport#pull} method is called — a successful response
     * (even a {@code null} result meaning no file yet) confirms that Drive is reachable.
     */
    private static void testDriveConnection(Context context) {
        DebugLogger.i(TAG, "testDriveConnection: starting");
        SyncTransport transport = DriveTransportFactory.create(context, null);
        if (transport == null) {
            DebugLogger.w(TAG, "testDriveConnection: no transport — user not signed in");
            Toast.makeText(context,
                    context.getString(R.string.notify_sync_drive_test_not_signed_in),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                transport.pull(new SyncCallback() {
                    @Override
                    public void onSuccess(byte[] data) {
                        DebugLogger.i(TAG, "testDriveConnection: success, dataBytes="
                                + (data == null ? 0 : data.length));
                        mainHandler.post(() -> Toast.makeText(context,
                                context.getString(R.string.notify_sync_drive_test_ok),
                                Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onError(Exception e) {
                        DebugLogger.e(TAG, "testDriveConnection: error", e);
                        String msg = context.getString(
                                R.string.notify_sync_drive_test_fail, e.getMessage());
                        mainHandler.post(() -> Toast.makeText(context, msg,
                                Toast.LENGTH_LONG).show());
                    }
                });
            } catch (Exception e) {
                // Safety net: pull() now handles all exceptions internally, but this catch
                // ensures the app cannot crash if an Error or truly unexpected throwable escapes.
                DebugLogger.e(TAG, "testDriveConnection: uncaught exception in executor", e);
                String msg = context.getString(
                        R.string.notify_sync_drive_test_fail, e.getMessage());
                mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
            }
        });
        executor.shutdown();
    }

    /**
     * Called when {@link android.app.Activity} receives a {@code dispensa://household?folderId=…}
     * deep-link Intent.  Pre-fills the join-household dialog with the extracted folder ID and
     * shows it to the user.
     *
     * <p>No-op if {@code folderId} is null or blank.
     *
     * @param fragment  the host {@link SettingsFragment}
     * @param folderId  the folder ID extracted from the deep-link URI
     */
    public static void handleHouseholdDeepLink(PreferenceFragmentCompat fragment, String folderId) {
        if (folderId == null || folderId.trim().isEmpty()) return;
        showJoinHouseholdDialogWithId(fragment, folderId.trim());
    }

    private static void showCreateHouseholdDialog(PreferenceFragmentCompat fragment) {
        Context context = fragment.requireContext();

        EditText emailsInput = new EditText(context);
        emailsInput.setHint(context.getString(R.string.hint_household_emails));
        emailsInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_create_household_title)
                .setMessage(R.string.dialog_create_household_message)
                .setView(emailsInput)
                .setPositiveButton(R.string.dialog_create_household_ok, (dlg, which) -> {
                    String raw = emailsInput.getText().toString().trim();
                    String[] emails = raw.isEmpty() ? new String[0]
                            : raw.split("[,\\s]+");
                    createHousehold(fragment, emails);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showJoinHouseholdDialog(PreferenceFragmentCompat fragment) {
        showJoinHouseholdDialogWithId(fragment, "");
    }

    private static void showJoinHouseholdDialogWithId(PreferenceFragmentCompat fragment,
            String prefillFolderId) {
        Context context = fragment.requireContext();

        EditText folderIdInput = new EditText(context);
        folderIdInput.setHint(context.getString(R.string.hint_household_folder_id));
        folderIdInput.setInputType(InputType.TYPE_CLASS_TEXT);
        if (!prefillFolderId.isEmpty()) {
            folderIdInput.setText(prefillFolderId);
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_join_household_title)
                .setMessage(R.string.dialog_join_household_message)
                .setView(folderIdInput)
                .setPositiveButton(R.string.dialog_join_household_ok, (dlg, which) -> {
                    String input = folderIdInput.getText().toString().trim();
                    if (input.isEmpty()) return;
                    // Accept either the full deep-link or a bare folder ID
                    String folderId = extractFolderIdFromInput(input);
                    if (folderId != null) {
                        joinHousehold(fragment, folderId);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Extracts the folder ID from either a bare folder ID string or a full
     * {@code dispensa://household?folderId=…} deep-link.
     *
     * @return the folder ID, or {@code null} if the input cannot be parsed
     */
    static String extractFolderIdFromInput(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();
        // Check for deep-link format
        if (trimmed.startsWith(HouseholdManager.DEEP_LINK_SCHEME + "://")) {
            android.net.Uri uri = android.net.Uri.parse(trimmed);
            return uri.getQueryParameter(HouseholdManager.DEEP_LINK_PARAM);
        }
        // Treat as bare folder ID
        return trimmed;
    }

    private static void createHousehold(PreferenceFragmentCompat fragment, String[] emails) {
        Context context = fragment.requireContext();
        String signedInEmail = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
        if (signedInEmail == null || signedInEmail.isEmpty()) {
            DebugLogger.w(TAG, "createHousehold: no signed-in account");
            Toast.makeText(context,
                    context.getString(R.string.notify_sync_drive_test_not_signed_in),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        DebugLogger.i(TAG, "createHousehold: starting, emailCount=" + emails.length);
        Account account = new Account(signedInEmail, DriveTransportFactory.GOOGLE_ACCOUNT_TYPE);
        Drive drive = HouseholdManager.buildDrive(context, account);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                String folderId = HouseholdManager.createHousehold(drive, context);
                DebugLogger.i(TAG, "createHousehold: folder created, id=" + folderId);
                for (String memberEmail : emails) {
                    String trimmed = memberEmail.trim();
                    if (!trimmed.isEmpty()) {
                        HouseholdManager.grantAccess(drive, folderId, trimmed);
                        DebugLogger.i(TAG, "createHousehold: granted access to " + trimmed);
                    }
                }
                String deepLink = HouseholdManager.generateJoinDeepLink(folderId);
                mainHandler.post(() -> {
                    try {
                        if (fragment.isAdded()) {
                            refreshSignInState(fragment);
                            showDeepLinkDialog(fragment, deepLink);
                        }
                    } catch (Exception dialogEx) {
                        DebugLogger.e(TAG, "createHousehold: could not show link dialog", dialogEx);
                    }
                });
            } catch (Throwable e) {
                // Catch Throwable (not just Exception) so that Error subclasses thrown by R8-
                // optimised Drive API code (e.g. NoClassDefFoundError) are also captured and
                // logged rather than crashing the app via the default uncaught-exception handler.
                DebugLogger.e(TAG, "createHousehold: failed", e);
                String msg = context.getString(R.string.err_household_create, messageOf(e));
                mainHandler.post(() ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
            }
        });
        executor.shutdown();
    }

    private static void joinHousehold(PreferenceFragmentCompat fragment, String folderId) {
        Context context = fragment.requireContext();
        String email = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
        if (email == null || email.isEmpty()) {
            DebugLogger.w(TAG, "joinHousehold: no signed-in account");
            Toast.makeText(context,
                    context.getString(R.string.notify_sync_drive_test_not_signed_in),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        DebugLogger.i(TAG, "joinHousehold: attempting folderId=" + folderId);
        Account account = new Account(email, DriveTransportFactory.GOOGLE_ACCOUNT_TYPE);
        Drive drive = HouseholdManager.buildDrive(context, account);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                boolean joined = HouseholdManager.verifyAndJoin(drive, context, folderId);
                DebugLogger.i(TAG, "joinHousehold: result=" + joined);
                mainHandler.post(() -> {
                    if (joined) {
                        try {
                            if (fragment.isAdded()) {
                                refreshSignInState(fragment);
                            }
                        } catch (Exception uiEx) {
                            DebugLogger.e(TAG, "joinHousehold: could not refresh UI state", uiEx);
                        }
                        Toast.makeText(context,
                                context.getString(R.string.notify_household_joined),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context,
                                context.getString(R.string.err_household_join_not_found),
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                // Catch Throwable so that Error subclasses from R8-optimised Drive API code
                // are logged instead of crashing via the uncaught-exception handler.
                DebugLogger.e(TAG, "joinHousehold: failed", e);
                String msg = context.getString(R.string.err_household_join, messageOf(e));
                mainHandler.post(() ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
            }
        });
        executor.shutdown();
    }

    /**
     * Returns {@link Throwable#getMessage()} if non-null, otherwise falls back to the
     * simple class name.  Useful for displaying {@link Error} subclasses (e.g.
     * {@link NoClassDefFoundError}) whose message may be null.
     */
    private static String messageOf(Throwable t) {
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    private static void leaveHousehold(Context context, PreferenceFragmentCompat fragment) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_leave_household_title)
                .setMessage(R.string.dialog_leave_household_message)
                .setPositiveButton(R.string.dialog_leave_household_ok, (dlg, which) -> {
                    HouseholdManager.clearHouseholdFolderId(context);
                    refreshSignInState(fragment);
                    Toast.makeText(context,
                            context.getString(R.string.notify_household_left),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showDeepLinkDialog(PreferenceFragmentCompat fragment, String deepLink) {
        if (!fragment.isAdded()) return;
        Context context = fragment.requireContext();
        String folderId = extractFolderIdFromInput(deepLink);

        // Build a vertical layout: QR code image + read-only link text
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        int padPx = (int) (16 * context.getResources().getDisplayMetrics().density);
        dialogView.setPadding(padPx, padPx, padPx, 0);

        ImageView qrImageView = new ImageView(context);
        int qrSizePx = (int) (240 * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(qrSizePx, qrSizePx);
        qrParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        qrParams.bottomMargin = padPx;
        qrImageView.setLayoutParams(qrParams);
        Bitmap qrBitmap = generateQrBitmap(deepLink, qrSizePx);
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
        } else {
            qrImageView.setVisibility(android.view.View.GONE);
        }
        dialogView.addView(qrImageView);

        EditText linkView = new EditText(context);
        linkView.setText(deepLink);
        linkView.setFocusable(false);
        linkView.setClickable(false);
        linkView.setLongClickable(true);
        dialogView.addView(linkView);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_household_link_title)
                .setMessage(R.string.dialog_household_link_message)
                .setView(dialogView)
                .setPositiveButton(R.string.dialog_household_link_copy, (dlg, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Dispensa invite link", deepLink));
                    }
                    Toast.makeText(context,
                            context.getString(R.string.dialog_household_link_copied),
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.dialog_household_link_invite, (dlg, which) -> {
                    if (folderId != null) {
                        showInviteByEmailDialog(fragment, folderId);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showInviteByEmailDialog(PreferenceFragmentCompat fragment, String folderId) {
        if (!fragment.isAdded()) return;
        Context context = fragment.requireContext();

        EditText emailInput = new EditText(context);
        emailInput.setHint(context.getString(R.string.hint_household_invite_email));
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_invite_by_email_title)
                .setMessage(R.string.dialog_invite_by_email_message)
                .setView(emailInput)
                .setPositiveButton(R.string.dialog_household_link_invite, (dlg, which) -> {
                    String email = emailInput.getText().toString().trim();
                    if (!email.isEmpty()) {
                        inviteHouseholdMember(fragment, folderId, email);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void inviteHouseholdMember(PreferenceFragmentCompat fragment,
            String folderId, String email) {
        Context context = fragment.requireContext();
        String signedInEmail = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
        if (signedInEmail == null || signedInEmail.isEmpty()) {
            Toast.makeText(context,
                    context.getString(R.string.notify_sync_drive_test_not_signed_in),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        DebugLogger.i(TAG, "inviteHouseholdMember: inviting " + email + " to folderId=" + folderId);
        Account account = new Account(signedInEmail, DriveTransportFactory.GOOGLE_ACCOUNT_TYPE);
        Drive drive = HouseholdManager.buildDrive(context, account);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                HouseholdManager.grantAccess(drive, folderId, email);
                DebugLogger.i(TAG, "inviteHouseholdMember: granted access to " + email);
                mainHandler.post(() -> Toast.makeText(context,
                        context.getString(R.string.notify_household_invite_sent, email),
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                DebugLogger.e(TAG, "inviteHouseholdMember: failed for " + email, e);
                String msg = context.getString(R.string.err_household_invite, email, messageOf(e));
                mainHandler.post(() ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
            }
        });
        executor.shutdown();
    }

    /**
     * Fetches the household folder name from Drive in the background and stores it in
     * SharedPreferences.  Called when the folder ID is known but the display name is not
     * yet cached (e.g. users who joined before Session 18.1).
     */
    private static void fetchAndStoreFolderName(PreferenceFragmentCompat fragment,
            String folderId) {
        if (folderId == null) return;
        Context context = fragment.requireContext();
        String signedInEmail = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, null);
        if (signedInEmail == null || signedInEmail.isEmpty()) return;

        DebugLogger.i(TAG, "fetchAndStoreFolderName: fetching name for folderId=" + folderId);
        Account account = new Account(signedInEmail, DriveTransportFactory.GOOGLE_ACCOUNT_TYPE);
        Drive drive = HouseholdManager.buildDrive(context, account);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                com.google.api.services.drive.model.File folder = drive.files().get(folderId)
                        .setFields("id,name")
                        .execute();
                if (folder != null && folder.getName() != null) {
                    HouseholdManager.setHouseholdFolderName(context, folder.getName());
                    DebugLogger.i(TAG, "fetchAndStoreFolderName: stored name='" + folder.getName() + "'");
                    mainHandler.post(() -> {
                        if (fragment.isAdded()) refreshSignInState(fragment);
                    });
                }
            } catch (Exception e) {
                DebugLogger.w(TAG, "fetchAndStoreFolderName: could not fetch folder name: "
                        + e.getMessage());
            }
        });
        executor.shutdown();
    }

    /**
     * Generates a square QR code bitmap for the given content.
     *
     * @param content the string to encode
     * @param sizePx  the width and height of the resulting bitmap in pixels
     * @return a {@link Bitmap}, or {@code null} if encoding fails
     */
    private static Bitmap generateQrBitmap(String content, int sizePx) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
            int[] pixels = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    pixels[y * sizePx + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx);
            return bitmap;
        } catch (WriterException e) {
            DebugLogger.w(TAG, "generateQrBitmap: failed to encode QR code: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the household folder display name for the status preference summary.
     * Falls back to the folder ID if no name is stored.
     */
    private static String householdDisplayName(Context context) {
        String name = HouseholdManager.getHouseholdFolderName(context);
        if (name != null && !name.isEmpty()) return name;
        String id = HouseholdManager.getHouseholdFolderId(context);
        return id != null ? id : "";
    }

    // ── Sign-in (Credential Manager) ──────────────────────────────────────────

    /**
     * Launches the Google Sign-In flow using the Android Credential Manager API.
     *
     * <p>Attempts to sign in with a previously authorized account first
     * ({@code filterByAuthorizedAccounts = true}); if none is found, falls back to the
     * full account picker ({@code filterByAuthorizedAccounts = false}).
     *
     * <p>On success the signed-in email is stored in SharedPreferences and Drive scope
     * authorization is requested immediately via {@code authLauncher}.
     *
     * @param fragment    the host fragment
     * @param authLauncher the launcher for the Drive scope consent screen
     */
    private static void launchSignIn(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<IntentSenderRequest> authLauncher) {
        DebugLogger.i(TAG, "launchSignIn: initiating Credential Manager sign-in");
        doLaunchSignIn(fragment, authLauncher, /* filterByAuthorized= */ true);
    }

    private static void doLaunchSignIn(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<IntentSenderRequest> authLauncher,
            boolean filterByAuthorized) {
        Context context = fragment.requireContext();
        String webClientId = context.getString(R.string.google_web_client_id);

        // For the first attempt (filterByAuthorized=true) use GetGoogleIdOption so that a
        // returning user is signed back in silently / with a one-tap bottom sheet.
        // For the account-picker fallback use GetSignInWithGoogleOption: it shows the standard
        // "Sign in with Google" sheet that works correctly for apps whose OAuth consent screen
        // is still in Testing mode and for first-time sign-ins, unlike GetGoogleIdOption with
        // filterByAuthorizedAccounts=false which silently returns NoCredentialException in
        // those configurations.
        androidx.credentials.CredentialOption credentialOption;
        if (filterByAuthorized) {
            credentialOption = new GetGoogleIdOption.Builder()
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(true)
                    .setAutoSelectEnabled(false)
                    .build();
        } else {
            credentialOption = new GetSignInWithGoogleOption.Builder(webClientId)
                    .build();
        }

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(credentialOption)
                .build();

        CredentialManager credentialManager = CredentialManager.create(context);
        DebugLogger.i(TAG, "launchSignIn: calling getCredentialAsync"
                + " filterByAuthorized=" + filterByAuthorized);
        credentialManager.getCredentialAsync(
                fragment.requireActivity(),
                request,
                null,
                fragment.requireActivity().getMainExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleCredentialResponse(fragment, response, authLauncher);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (filterByAuthorized && e instanceof NoCredentialException) {
                            // No previously authorized account found — show the full picker
                            DebugLogger.i(TAG,
                                    "launchSignIn: no authorized account, retrying with picker");
                            doLaunchSignIn(fragment, authLauncher, /* filterByAuthorized= */ false);
                        } else {
                            DebugLogger.w(TAG, "launchSignIn: sign-in failed ["
                                    + e.getClass().getSimpleName() + "]: " + e.getMessage());
                            Toast.makeText(context,
                                    context.getString(R.string.notify_sync_sign_in_failed),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );
    }

    /**
     * Processes a successful {@link GetCredentialResponse} from the Credential Manager.
     * Extracts the signed-in email, persists it, and triggers Drive scope authorization.
     */
    private static void handleCredentialResponse(PreferenceFragmentCompat fragment,
            GetCredentialResponse response,
            ActivityResultLauncher<IntentSenderRequest> authLauncher) {
        androidx.credentials.Credential credential = response.getCredential();
        GoogleIdTokenCredential googleCredential;
        if (credential instanceof GoogleIdTokenCredential) {
            googleCredential = (GoogleIdTokenCredential) credential;
        } else if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(credential.getType())) {
            // GetSignInWithGoogleOption returns a CustomCredential wrapping a Google ID token.
            googleCredential = GoogleIdTokenCredential.createFrom(
                    ((CustomCredential) credential).getData());
        } else {
            DebugLogger.w(TAG, "handleCredentialResponse: unexpected credential type: "
                    + credential.getClass().getSimpleName());
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(R.string.notify_sync_sign_in_failed),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String email = googleCredential.getId();
        DebugLogger.i(TAG, "handleCredentialResponse: sign-in successful, email=" + email);

        PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                .edit()
                .putString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, email)
                .apply();

        refreshSignInState(fragment);
        launchDriveAuthorization(fragment, authLauncher);
    }

    // ── Drive authorization (two-step flow) ───────────────────────────────────

    /**
     * Requests {@code DRIVE_APPDATA} and {@code DRIVE_FILE} authorization using the
     * {@link Identity#getAuthorizationClient} API (required since play-services-auth 21.x;
     * Drive scopes must be requested separately from sign-in).
     *
     * <p>If the user has already granted the scopes, {@link #completeDriveAuthorization} is
     * called immediately (no UI shown).  Otherwise the consent screen is launched via
     * {@code authLauncher} and the result is delivered to {@link #handleAuthorizationResult}.
     *
     * @param fragment    the host fragment
     * @param authLauncher the {@code ActivityResultLauncher<IntentSenderRequest>} registered
     *                    in the fragment's {@code onCreate()}
     */
    static void launchDriveAuthorization(PreferenceFragmentCompat fragment,
            ActivityResultLauncher<IntentSenderRequest> authLauncher) {
        DebugLogger.i(TAG, "launchDriveAuthorization: requesting DRIVE_APPDATA + DRIVE_FILE");
        List<Scope> scopes = Arrays.asList(
                new Scope(DriveScopes.DRIVE_APPDATA),
                new Scope(DriveScopes.DRIVE_FILE));
        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes)
                .build();
        Identity.getAuthorizationClient(fragment.requireActivity())
                .authorize(request)
                .addOnSuccessListener(authResult -> {
                    if (authResult.hasResolution()) {
                        DebugLogger.i(TAG, "launchDriveAuthorization: consent required, launching");
                        try {
                            authLauncher.launch(new IntentSenderRequest.Builder(
                                    authResult.getPendingIntent().getIntentSender()).build());
                        } catch (IllegalStateException e) {
                            DebugLogger.e(TAG, "launchDriveAuthorization: failed to launch consent", e);
                            onDriveAuthorizationFailed(fragment);
                        }
                    } else {
                        DebugLogger.i(TAG, "launchDriveAuthorization: scopes already granted");
                        completeDriveAuthorization(fragment, false);
                    }
                })
                .addOnFailureListener(e -> {
                    DebugLogger.e(TAG, "launchDriveAuthorization: authorize() failed", e);
                    onDriveAuthorizationFailed(fragment);
                });
    }

    /**
     * Handles the result delivered from the Drive-scope consent screen.  On success,
     * {@link #completeDriveAuthorization} is called to enable Drive sync; on failure or
     * cancellation the toggle is reverted and an error toast is shown.
     *
     * @param fragment the host fragment
     * @param result   the {@link ActivityResult} delivered to the authorization launcher
     */
    public static void handleAuthorizationResult(PreferenceFragmentCompat fragment,
            ActivityResult result) {
        DebugLogger.i(TAG, "handleAuthorizationResult: resultCode=" + result.getResultCode());
        if (result.getResultCode() != Activity.RESULT_OK
                || result.getData() == null) {
            DebugLogger.w(TAG, "Drive authorization cancelled (resultCode="
                    + result.getResultCode() + ")");
            onDriveAuthorizationFailed(fragment);
            return;
        }
        try {
            AuthorizationResult authResult = Identity.getAuthorizationClient(
                    fragment.requireActivity())
                    .getAuthorizationResultFromIntent(result.getData());
            DebugLogger.i(TAG, "handleAuthorizationResult: Drive scopes granted"
                    + " hasToken=" + (authResult.getAccessToken() != null));
            completeDriveAuthorization(fragment, true);
        } catch (ApiException e) {
            DebugLogger.e(TAG, "handleAuthorizationResult: ApiException statusCode="
                    + e.getStatusCode(), e);
            onDriveAuthorizationFailed(fragment);
        }
    }

    /**
     * Finalises Drive sync enablement after a successful authorization.
     *
     * <p>Enables the {@code sync_drive_enabled} preference if it is not already on, refreshes
     * the sign-in state, and optionally shows a confirmation toast.
     *
     * @param fragment      the host fragment
     * @param alwaysToast   {@code true} when the user just completed the consent screen and
     *                      must see feedback regardless of the previous toggle state; {@code
     *                      false} when scopes were already granted and a toast is only shown
     *                      if Drive sync was not yet enabled
     */
    private static void completeDriveAuthorization(PreferenceFragmentCompat fragment,
            boolean alwaysToast) {
        boolean wasEnabled = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                .getBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false);
        if (!wasEnabled) {
            PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                    .edit()
                    .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, true)
                    .apply();
        }
        refreshSignInState(fragment);
        if (alwaysToast || !wasEnabled) {
            String email = PreferenceManager
                    .getDefaultSharedPreferences(fragment.requireContext())
                    .getString(DriveTransportFactory.PREF_SIGNED_IN_EMAIL, "");
            DebugLogger.i(TAG, "completeDriveAuthorization: Drive sync enabled, email=" + email);
            Toast.makeText(fragment.requireContext(),
                    fragment.requireContext().getString(R.string.notify_sync_signed_in, email),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when Drive scope authorization has failed or been cancelled.
     * Reverts the Drive sync toggle to {@code false} and shows an error toast.
     */
    private static void onDriveAuthorizationFailed(PreferenceFragmentCompat fragment) {
        DebugLogger.w(TAG, "onDriveAuthorizationFailed: reverting Drive sync toggle");
        PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
                .edit()
                .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false)
                .apply();
        Preference drivePref = fragment.findPreference(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED);
        if (drivePref instanceof androidx.preference.CheckBoxPreference) {
            ((androidx.preference.CheckBoxPreference) drivePref).setChecked(false);
        }
        Toast.makeText(fragment.requireContext(),
                fragment.requireContext().getString(R.string.notify_sync_sign_in_failed),
                Toast.LENGTH_SHORT).show();
    }

    // ── Sign-out ───────────────────────────────────────────────────────────────

    private static void signOut(Context context, Preference driveEnabledPref,
            Preference accountPref, Preference signInPref, Preference signOutPref) {
        DebugLogger.i(TAG, "signOut: initiating sign-out via Credential Manager");
        CredentialManager credentialManager = CredentialManager.create(context);
        credentialManager.clearCredentialStateAsync(
                new ClearCredentialStateRequest(),
                null,
                Executors.newSingleThreadExecutor(),
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        DebugLogger.i(TAG, "signOut: credential state cleared");
                        performSignOut(context, driveEnabledPref, accountPref,
                                signInPref, signOutPref);
                    }

                    @Override
                    public void onError(ClearCredentialException e) {
                        DebugLogger.w(TAG, "signOut: clearCredentialState error (clearing locally): "
                                + e.getMessage());
                        // Clear locally even if the credential manager reports an error
                        performSignOut(context, driveEnabledPref, accountPref,
                                signInPref, signOutPref);
                    }
                }
        );
    }

    /**
     * Removes the stored email, disables Drive sync, and updates the UI.
     * Must be called on the main thread (or post to main handler).
     */
    private static void performSignOut(Context context, Preference driveEnabledPref,
            Preference accountPref, Preference signInPref, Preference signOutPref) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .remove(DriveTransportFactory.PREF_SIGNED_IN_EMAIL)
                    .putBoolean(DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false)
                    .apply();
            HouseholdManager.clearHouseholdFolderId(context);

            if (driveEnabledPref instanceof androidx.preference.CheckBoxPreference) {
                ((androidx.preference.CheckBoxPreference) driveEnabledPref).setChecked(false);
            }

            if (signInPref  != null) signInPref.setVisible(true);
            if (accountPref != null) accountPref.setVisible(false);
            if (signOutPref != null) signOutPref.setVisible(false);

            Toast.makeText(context,
                    context.getString(R.string.notify_sync_signed_out),
                    Toast.LENGTH_SHORT).show();
            DebugLogger.i(TAG, "signOut: UI updated, signed out.");
        });
    }
}
