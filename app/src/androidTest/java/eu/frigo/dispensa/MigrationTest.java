package eu.frigo.dispensa;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;

import eu.frigo.dispensa.data.AppDatabase;

import static org.junit.Assert.assertTrue;

/**
 * Instrumented tests verifying Room database migrations.
 */
@RunWith(AndroidJUnit4.class)
public class MigrationTest {

    private static final String TEST_DB = "migration-test";

    @Rule
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class,
            Collections.emptyList()
    );

    /**
     * Verifies that the 9→10 migration completes successfully: the {@code sync_changes}
     * and {@code sync_import_lock} helper tables are created, the twelve change-capture
     * triggers are installed, and all four synced entity tables still exist.
     */
    @Test
    public void migrate9To10() throws IOException {
        // Create the database at version 9.
        helper.createDatabase(TEST_DB, 9);

        // Apply MIGRATION_9_10 and validate the resulting schema.
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB,
                10,
                true,
                AppDatabase.MIGRATION_9_10
        );

        // Sync infrastructure tables must exist.
        assertTrue("sync_changes table must exist", tableExists(db, "sync_changes"));
        assertTrue("sync_import_lock table must exist", tableExists(db, "sync_import_lock"));

        // All four synced entity tables must still exist.
        assertTrue(tableExists(db, "products"));
        assertTrue(tableExists(db, "categories_definitions"));
        assertTrue(tableExists(db, "product_category_links"));
        assertTrue(tableExists(db, "storage_locations"));

        // The twelve change-capture triggers must have been created.
        String[] expectedTriggers = {
                "sync_products_insert", "sync_products_update", "sync_products_delete",
                "sync_categories_insert", "sync_categories_update", "sync_categories_delete",
                "sync_product_category_links_insert", "sync_product_category_links_update",
                "sync_product_category_links_delete",
                "sync_storage_locations_insert", "sync_storage_locations_update",
                "sync_storage_locations_delete"
        };
        for (String trigger : expectedTriggers) {
            assertTrue("Trigger must exist: " + trigger, triggerExists(db, trigger));
        }
    }

    private boolean tableExists(SupportSQLiteDatabase db, String tableName) {
        Cursor cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new Object[]{tableName}
        );
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    private boolean triggerExists(SupportSQLiteDatabase db, String triggerName) {
        Cursor cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND name=?",
                new Object[]{triggerName}
        );
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }
}
