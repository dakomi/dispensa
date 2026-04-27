package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;

/**
 * Unit tests for {@link HouseholdManager}.
 *
 * <p>Pure-logic tests (deep-link, constant values) run without Android dependencies.
 * Drive API tests use Mockito {@link Mockito#RETURNS_DEEP_STUBS} to avoid verbose
 * mock setup for the chained Drive builder pattern.
 */
public class HouseholdManagerTest {

    // ── Deep-link tests ───────────────────────────────────────────────────────

    @Test
    public void generateJoinDeepLink_producesCorrectUri() {
        String link = HouseholdManager.generateJoinDeepLink("folder_abc123");
        assertEquals("dispensa://household?folderId=folder_abc123", link);
    }

    @Test
    public void generateJoinDeepLink_preservesFolderIdExactly() {
        String folderId = "0B_a1b2c3-D4E5";
        String link = HouseholdManager.generateJoinDeepLink(folderId);
        assertTrue(link.endsWith("folderId=" + folderId));
    }

    @Test
    public void deepLinkConstants_areCorrect() {
        assertEquals("dispensa", HouseholdManager.DEEP_LINK_SCHEME);
        assertEquals("household", HouseholdManager.DEEP_LINK_HOST);
        assertEquals("folderId", HouseholdManager.DEEP_LINK_PARAM);
    }

    // ── createHousehold ───────────────────────────────────────────────────────

    @Test
    public void createHousehold_returnsCreatedFolderId() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        File createdFile = new File().setId("new_folder_id");
        when(mockDrive.files().create(any(File.class)).setFields("id").execute())
                .thenReturn(createdFile);

        android.content.Context mockCtx = mockContext();

        String folderId = HouseholdManager.createHousehold(mockDrive, mockCtx);

        assertEquals("new_folder_id", folderId);
    }

    // ── grantAccess ───────────────────────────────────────────────────────────

    @Test
    public void grantAccess_doesNotThrowOnSuccess() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockDrive.permissions().create(anyString(), any(Permission.class))
                .setSendNotificationEmail(false).execute())
                .thenReturn(new Permission());

        // Should complete without throwing
        HouseholdManager.grantAccess(mockDrive, "folder_id", "alice@example.com");
    }

    // ── verifyAndJoin ─────────────────────────────────────────────────────────

    @Test
    public void verifyAndJoin_returnsTrueWhenFolderAccessible() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        File folder = new File().setId("folder_xyz").setName("Dispensa Household");
        when(mockDrive.files().get("folder_xyz").setFields("id,name").execute())
                .thenReturn(folder);
        android.content.Context mockCtx = mockContext();

        boolean result = HouseholdManager.verifyAndJoin(mockDrive, mockCtx, "folder_xyz");

        assertTrue(result);
    }

    @Test
    public void verifyAndJoin_returnsFalseOn403() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockDrive.files().get(anyString()).setFields("id,name").execute())
                .thenThrow(makeHttpException(403));
        android.content.Context mockCtx = mockContext();

        boolean result = HouseholdManager.verifyAndJoin(mockDrive, mockCtx, "no_access_folder");

        assertFalse(result);
    }

    @Test
    public void verifyAndJoin_returnsFalseOn404() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockDrive.files().get(anyString()).setFields("id,name").execute())
                .thenThrow(makeHttpException(404));
        android.content.Context mockCtx = mockContext();

        boolean result = HouseholdManager.verifyAndJoin(mockDrive, mockCtx, "missing_folder");

        assertFalse(result);
    }

    @Test(expected = IOException.class)
    public void verifyAndJoin_rethrowsNon403404Error() throws Exception {
        Drive mockDrive = mock(Drive.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockDrive.files().get(anyString()).setFields("id,name").execute())
                .thenThrow(makeHttpException(500));
        android.content.Context mockCtx = mockContext();

        HouseholdManager.verifyAndJoin(mockDrive, mockCtx, "some_folder");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a {@link GoogleJsonResponseException} with the given HTTP status code. */
    private static GoogleJsonResponseException makeHttpException(int statusCode) {
        HttpResponseException.Builder builder =
                new HttpResponseException.Builder(statusCode, "error", new HttpHeaders());
        return new GoogleJsonResponseException(builder, new GoogleJsonError());
    }

    /**
     * Returns a minimal {@link android.content.Context} mock whose SharedPreferences calls
     * are silently no-op.  Used to avoid NPE from {@code PreferenceManager} in JVM tests.
     *
     * <p>{@code PreferenceManager.getDefaultSharedPreferences(ctx)} calls
     * {@code ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", 0)},
     * so we must mock both {@code getPackageName()} and {@code getSharedPreferences()}.
     */
    private static android.content.Context mockContext() {
        android.content.Context ctx = mock(android.content.Context.class);
        android.content.SharedPreferences prefs = mock(android.content.SharedPreferences.class);
        android.content.SharedPreferences.Editor editor =
                mock(android.content.SharedPreferences.Editor.class);

        when(ctx.getPackageName()).thenReturn("eu.frigo.dispensa.test");
        when(ctx.getSharedPreferences(anyString(), Mockito.anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(editor.remove(anyString())).thenReturn(editor);
        when(prefs.getString(anyString(), any())).thenReturn(null);

        return ctx;
    }
}
