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
import io.vlcn.crsqlite.CrSqliteOpenHelperFactory;

import static org.junit.Assert.assertNotNull;
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
            Collections.emptyList(),
            new CrSqliteOpenHelperFactory()
    );

    /**
     * Verifies that the 9→10 migration completes successfully and that the
     * {@code crsql_changes} virtual table (exposed by the CR-SQLite extension)
     * is accessible after the migration.
     */
    @Test
    public void migrate9To10() throws IOException {
        // Create the database at version 9.
        helper.createDatabase(TEST_DB, 9);

        // Apply MIGRATION_9_10 and open the database at version 10.
        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                TEST_DB,
                10,
                true,
                AppDatabase.MIGRATION_9_10
        );

        // Verify that the crsql_changes virtual table is accessible.
        Cursor cursor = db.query("SELECT * FROM crsql_changes LIMIT 1");
        assertNotNull("crsql_changes virtual table should be accessible", cursor);
        cursor.close();

        // Verify that all four CRDT-enabled tables still exist.
        assertTrue(tableExists(db, "products"));
        assertTrue(tableExists(db, "categories_definitions"));
        assertTrue(tableExists(db, "product_category_links"));
        assertTrue(tableExists(db, "storage_locations"));
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
}
