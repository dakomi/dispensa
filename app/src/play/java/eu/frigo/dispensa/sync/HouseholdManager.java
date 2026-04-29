package eu.frigo.dispensa.sync;

import android.accounts.Account;
import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;

import java.io.IOException;
import java.util.Collections;

import eu.frigo.dispensa.util.DebugLogger;

/**
 * Manages the "Dispensa Household" shared Drive folder for multi-account sync.
 *
 * <p>A "household" is a named Drive folder owned by the host user.  The host can grant
 * write access to other Google accounts; each device then uploads its own per-device
 * JSON file to the shared folder, enabling cross-account pantry sync.
 *
 * <p>The folder ID is persisted in {@link android.content.SharedPreferences} so it
 * survives app restarts.
 *
 * <h3>Usage flow</h3>
 * <ol>
 *   <li>Host: calls {@link #createHousehold} → gets a folderId → shares the
 *       {@link #generateJoinDeepLink deep-link} with household members.</li>
 *   <li>Guests: tap or paste the deep link → app calls
 *       {@link #verifyAndJoin} → if successful, folderId is stored locally.</li>
 * </ol>
 *
 * <h3>Scope</h3>
 * Household-mode Drive calls use {@link DriveScopes#DRIVE_FILE}, which grants access only
 * to files created or explicitly opened by this app on the user's account.  The host
 * creates the folder (DRIVE_FILE owns it); guests access it once the host shares it with
 * their accounts, adding the folder to their "opened with this app" set.
 *
 * <p>All Drive API calls are synchronous and must be performed on a background thread.
 */
public class HouseholdManager {

    private static final String TAG = "HouseholdManager";

    /** Deep-link URI scheme. Full URI: {@code dispensa://household?folderId=<id>}. */
    public static final String DEEP_LINK_SCHEME = "dispensa";

    /** Deep-link host component. */
    public static final String DEEP_LINK_HOST = "household";

    /** Query-parameter name that carries the Drive folder ID in the join deep-link. */
    public static final String DEEP_LINK_PARAM = "folderId";

    /** SharedPreferences key for the stored household folder ID. */
    public static final String PREF_HOUSEHOLD_FOLDER_ID = "sync_drive_household_folder_id";

    /** SharedPreferences key for the stored household folder display name. */
    public static final String PREF_HOUSEHOLD_FOLDER_NAME = "sync_drive_household_folder_name";

    /** Display name of the shared Drive folder created by the host. */
    static final String FOLDER_NAME = "Dispensa Household";

    /** MIME type for a Google Drive folder. */
    static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

    private HouseholdManager() {}

    // ── SharedPreferences helpers ─────────────────────────────────────────────

    /**
     * Returns the stored household folder ID, or {@code null} if this device has not
     * joined or created a household.
     */
    public static String getHouseholdFolderId(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_HOUSEHOLD_FOLDER_ID, null);
    }

    /**
     * Persists the household folder ID in SharedPreferences.
     */
    static void setHouseholdFolderId(Context context, String folderId) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_HOUSEHOLD_FOLDER_ID, folderId)
                .apply();
    }

    /**
     * Removes the stored household folder ID, reverting this device to solo-mode Drive sync.
     */
    public static void clearHouseholdFolderId(Context context) {
        DebugLogger.i(TAG, "clearHouseholdFolderId: removing stored household folder ID");
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(PREF_HOUSEHOLD_FOLDER_ID)
                .remove(PREF_HOUSEHOLD_FOLDER_NAME)
                .apply();
    }

    /**
     * Returns the stored household folder display name, or {@code null} if unknown.
     */
    public static String getHouseholdFolderName(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_HOUSEHOLD_FOLDER_NAME, null);
    }

    /**
     * Persists the household folder display name in SharedPreferences.
     */
    static void setHouseholdFolderName(Context context, String name) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_HOUSEHOLD_FOLDER_NAME, name)
                .apply();
    }

    // ── Drive service builder ─────────────────────────────────────────────────

    /**
     * Builds a {@link Drive} service for the authenticated user using
     * {@link DriveScopes#DRIVE_FILE} scope.  Suitable for household creation/join
     * operations and per-device file uploads.
     *
     * @param context application context
     * @param account the signed-in Google account
     * @return an authenticated Drive service
     */
    public static Drive buildDrive(Context context, Account account) {
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE));
        credential.setSelectedAccount(account);
        return new Drive.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Dispensa")
                .build();
    }

    // ── Drive API operations ──────────────────────────────────────────────────

    /**
     * Creates the "Dispensa Household" folder in the authenticated user's Drive,
     * stores the resulting folder ID in SharedPreferences, and returns it.
     *
     * <p>Must be called on a background thread.
     *
     * @param drive   an authenticated {@link Drive} service (built via {@link #buildDrive})
     * @param context application context (used to persist the folder ID)
     * @return the newly created folder ID
     * @throws IOException on any Drive API or network error
     */
    public static String createHousehold(Drive drive, Context context) throws IOException {
        DebugLogger.i(TAG, "createHousehold: creating Drive folder '" + FOLDER_NAME + "'");
        File folderMeta = new File()
                .setName(FOLDER_NAME)
                .setMimeType(FOLDER_MIME_TYPE);
        File created = drive.files().create(folderMeta)
                .setFields("id")
                .execute();
        String folderId = created.getId();
        setHouseholdFolderId(context, folderId);
        setHouseholdFolderName(context, FOLDER_NAME);
        DebugLogger.i(TAG, "createHousehold: folder created, id=" + folderId);
        Log.d(TAG, "Household folder created: " + folderId);
        return folderId;
    }

    /**
     * Grants {@code writer} access to {@code email} on the household folder.
     *
     * <p>Must be called on a background thread.
     *
     * @param drive    an authenticated {@link Drive} service
     * @param folderId the household folder ID
     * @param email    the Google account email of the new household member
     * @throws IOException on any Drive API or network error
     */
    public static void grantAccess(Drive drive, String folderId, String email)
            throws IOException {
        DebugLogger.i(TAG, "grantAccess: granting writer access to " + email
                + " on folderId=" + folderId);
        Permission permission = new Permission()
                .setType("user")
                .setRole("writer")
                .setEmailAddress(email);
        drive.permissions().create(folderId, permission)
                .setSendNotificationEmail(false)
                .execute();
        DebugLogger.i(TAG, "grantAccess: permission granted to " + email);
        Log.d(TAG, "Writer access granted to " + email + " on folder " + folderId);
    }

    /**
     * Verifies that the current user has access to {@code folderId} and, if so, stores
     * it in SharedPreferences as the household folder ID.
     *
     * <p>Must be called on a background thread.
     *
     * @param drive    an authenticated {@link Drive} service
     * @param context  application context (used to persist the folder ID)
     * @param folderId the folder ID extracted from the join deep-link
     * @return {@code true} if access was verified and the folder ID was stored;
     *         {@code false} if the folder is not accessible (HTTP 403 / 404)
     * @throws IOException on network errors other than 403 / 404
     */
    public static boolean verifyAndJoin(Drive drive, Context context, String folderId)
            throws IOException {
        DebugLogger.i(TAG, "verifyAndJoin: checking access to folderId=" + folderId);
        try {
            File folder = drive.files().get(folderId)
                    .setFields("id,name")
                    .execute();
            if (folder == null || folder.getId() == null) {
                DebugLogger.w(TAG, "verifyAndJoin: folder response was null or had no id");
                return false;
            }
            setHouseholdFolderId(context, folderId);
            setHouseholdFolderName(context, folder.getName() != null ? folder.getName() : folderId);
            DebugLogger.i(TAG, "verifyAndJoin: joined household folder '"
                    + folder.getName() + "' id=" + folderId);
            Log.d(TAG, "Joined household folder: " + folderId
                    + " (" + folder.getName() + ")");
            return true;
        } catch (GoogleJsonResponseException e) {
            int status = e.getStatusCode();
            if (status == 403 || status == 404) {
                DebugLogger.w(TAG, "verifyAndJoin: cannot access folder " + folderId
                        + ": HTTP " + status);
                Log.w(TAG, "Cannot access folder " + folderId + ": HTTP " + status);
                return false;
            }
            DebugLogger.e(TAG, "verifyAndJoin: unexpected Drive error HTTP " + status, e);
            throw e;
        }
    }

    // ── Deep-link helpers ─────────────────────────────────────────────────────

    /**
     * Returns the deep-link URI string that members use to join the household.
     * <p>Format: {@code dispensa://household?folderId=<id>}
     *
     * @param folderId the household Drive folder ID
     * @return the join deep-link URI as a string
     */
    public static String generateJoinDeepLink(String folderId) {
        return DEEP_LINK_SCHEME + "://" + DEEP_LINK_HOST
                + "?" + DEEP_LINK_PARAM + "=" + folderId;
    }
}
