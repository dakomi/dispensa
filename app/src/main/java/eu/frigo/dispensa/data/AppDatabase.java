package eu.frigo.dispensa.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.frigo.dispensa.data.category.CategoryDefinition;
import eu.frigo.dispensa.data.category.CategoryDefinitionDao;
import eu.frigo.dispensa.data.category.ProductCategoryLink;
import eu.frigo.dispensa.data.category.ProductCategoryLinkDao;
import eu.frigo.dispensa.data.product.Product;
import eu.frigo.dispensa.data.product.ProductDao;
import eu.frigo.dispensa.data.storage.PredefinedData;
import eu.frigo.dispensa.data.storage.StorageLocation;
import eu.frigo.dispensa.data.storage.StorageLocationDao;

import eu.frigo.dispensa.data.openfoodfacts.OpenFoodFactCacheDao;
import eu.frigo.dispensa.data.openfoodfacts.OpenFoodFactCacheEntity;

@Database(entities = {Product.class, CategoryDefinition.class,
        ProductCategoryLink.class, StorageLocation.class, OpenFoodFactCacheEntity.class },
        version = 10)
public abstract class AppDatabase extends RoomDatabase {

    public abstract ProductDao productDao();
    public abstract CategoryDefinitionDao categoryDefinitionDao();
    public abstract ProductCategoryLinkDao productCategoryLinkDao();
    public abstract StorageLocationDao storageLocationDao();
    public abstract OpenFoodFactCacheDao openFoodFactCacheDao();

    private static volatile AppDatabase INSTANCE;
    private static final int NUMBER_OF_THREADS = 4;
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    static final Migration MIGRATION_6_7 = new Migration(6,7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE products ADD COLUMN opened_date INTEGER DEFAULT 0 NOT NULL");
            database.execSQL("ALTER TABLE products ADD COLUMN shelf_life_after_opening_days INTEGER DEFAULT -1 NOT NULL");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS index_products_location_internal_key ON products(storage_location)");
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `openfoodfact_cache` (`barcode` TEXT NOT NULL, `product_name` TEXT, `image_local_path` TEXT, `categories_tags` TEXT, `timestamp_ms` INTEGER NOT NULL, PRIMARY KEY(`barcode`))");
        }
    };

    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            createSyncTablesAndTriggers(database);
        }
    };

    /**
     * Creates the {@code sync_changes} and {@code sync_import_lock} tables together with
     * all 12 change-capture triggers (one per CRUD operation × 4 synced tables).
     *
     * <p>All statements use {@code CREATE TABLE IF NOT EXISTS} / {@code CREATE TRIGGER IF NOT
     * EXISTS} so this method is idempotent and safe to call on both fresh installs (via the
     * {@link RoomDatabase.Callback#onCreate} hook) and schema upgrades (via
     * {@link #MIGRATION_9_10}).
     *
     * <p><strong>Why this method exists:</strong> Room creates the database schema from entity
     * annotations when installing the app for the first time (i.e., there is no pre-existing
     * database to migrate).  Migrations are only executed when upgrading an existing database.
     * Because {@code sync_changes} and {@code sync_import_lock} are <em>not</em> Room
     * entities, they would be absent on a fresh install if this setup were limited to the
     * migration path — causing {@link eu.frigo.dispensa.sync.SyncManager#exportChanges} to
     * throw {@code SQLiteException: no such table: sync_changes} and silently kill every
     * sync cycle.
     */
    static void createSyncTablesAndTriggers(SupportSQLiteDatabase database) {
        // ── sync_changes: one row per (table, pk) holding the latest version ──────
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_changes ("
                        + "tbl TEXT NOT NULL, "
                        + "pk_val TEXT NOT NULL, "
                        + "op TEXT NOT NULL, "
                        + "row_json TEXT, "
                        + "clock INTEGER NOT NULL, "
                        + "PRIMARY KEY(tbl, pk_val))");

        // ── sync_import_lock: prevents triggers re-firing during import ───────────
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS sync_import_lock "
                        + "(locked INTEGER NOT NULL DEFAULT 0)");
        database.execSQL(
                "INSERT OR IGNORE INTO sync_import_lock (rowid, locked) VALUES (1, 0)");

        // ── products triggers ─────────────────────────────────────────────────────
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_products_insert "
                        + "AFTER INSERT ON products "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('products', CAST(NEW.id AS TEXT), 'UPSERT', "
                        + "json_object('id', NEW.id, 'barcode', NEW.barcode, "
                        + "'quantity', NEW.quantity, 'expiry_date', NEW.expiry_date, "
                        + "'product_name', NEW.product_name, 'image_url', NEW.image_url, "
                        + "'storage_location', NEW.storage_location, "
                        + "'opened_date', NEW.opened_date, "
                        + "'shelf_life_after_opening_days', NEW.shelf_life_after_opening_days), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_products_update "
                        + "AFTER UPDATE ON products "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('products', CAST(NEW.id AS TEXT), 'UPSERT', "
                        + "json_object('id', NEW.id, 'barcode', NEW.barcode, "
                        + "'quantity', NEW.quantity, 'expiry_date', NEW.expiry_date, "
                        + "'product_name', NEW.product_name, 'image_url', NEW.image_url, "
                        + "'storage_location', NEW.storage_location, "
                        + "'opened_date', NEW.opened_date, "
                        + "'shelf_life_after_opening_days', NEW.shelf_life_after_opening_days), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_products_delete "
                        + "AFTER DELETE ON products "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('products', CAST(OLD.id AS TEXT), 'DELETE', NULL, "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");

        // ── categories_definitions triggers ───────────────────────────────────────
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_categories_insert "
                        + "AFTER INSERT ON categories_definitions "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('categories_definitions', CAST(NEW.category_id AS TEXT), 'UPSERT', "
                        + "json_object('category_id', NEW.category_id, 'tag_name', NEW.tag_name, "
                        + "'display_name_it', NEW.display_name_it, 'language_code', NEW.language_code, "
                        + "'color_hex', NEW.color_hex), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_categories_update "
                        + "AFTER UPDATE ON categories_definitions "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('categories_definitions', CAST(NEW.category_id AS TEXT), 'UPSERT', "
                        + "json_object('category_id', NEW.category_id, 'tag_name', NEW.tag_name, "
                        + "'display_name_it', NEW.display_name_it, 'language_code', NEW.language_code, "
                        + "'color_hex', NEW.color_hex), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_categories_delete "
                        + "AFTER DELETE ON categories_definitions "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('categories_definitions', CAST(OLD.category_id AS TEXT), 'DELETE', NULL, "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");

        // ── product_category_links triggers ───────────────────────────────────────
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_product_category_links_insert "
                        + "AFTER INSERT ON product_category_links "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('product_category_links', "
                        + "CAST(NEW.product_id_fk AS TEXT) || ',' || CAST(NEW.category_id_fk AS TEXT), "
                        + "'UPSERT', "
                        + "json_object('product_id_fk', NEW.product_id_fk, 'category_id_fk', NEW.category_id_fk), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_product_category_links_update "
                        + "AFTER UPDATE ON product_category_links "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('product_category_links', "
                        + "CAST(NEW.product_id_fk AS TEXT) || ',' || CAST(NEW.category_id_fk AS TEXT), "
                        + "'UPSERT', "
                        + "json_object('product_id_fk', NEW.product_id_fk, 'category_id_fk', NEW.category_id_fk), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_product_category_links_delete "
                        + "AFTER DELETE ON product_category_links "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('product_category_links', "
                        + "CAST(OLD.product_id_fk AS TEXT) || ',' || CAST(OLD.category_id_fk AS TEXT), "
                        + "'DELETE', NULL, "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");

        // ── storage_locations triggers ────────────────────────────────────────────
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_storage_locations_insert "
                        + "AFTER INSERT ON storage_locations "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('storage_locations', CAST(NEW.id AS TEXT), 'UPSERT', "
                        + "json_object('id', NEW.id, 'name', NEW.name, "
                        + "'internal_key', NEW.internal_key, 'order_index', NEW.order_index, "
                        + "'is_default', NEW.is_default, 'is_predefined', NEW.is_predefined), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_storage_locations_update "
                        + "AFTER UPDATE ON storage_locations "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('storage_locations', CAST(NEW.id AS TEXT), 'UPSERT', "
                        + "json_object('id', NEW.id, 'name', NEW.name, "
                        + "'internal_key', NEW.internal_key, 'order_index', NEW.order_index, "
                        + "'is_default', NEW.is_default, 'is_predefined', NEW.is_predefined), "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
        database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS sync_storage_locations_delete "
                        + "AFTER DELETE ON storage_locations "
                        + "WHEN (SELECT locked FROM sync_import_lock LIMIT 1) = 0 "
                        + "BEGIN "
                        + "INSERT OR REPLACE INTO sync_changes(tbl, pk_val, op, row_json, clock) "
                        + "VALUES('storage_locations', CAST(OLD.id AS TEXT), 'DELETE', NULL, "
                        + "(SELECT COALESCE(MAX(clock), 0) + 1 FROM sync_changes)); "
                        + "END");
    }

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                RoomDatabase.Callback sRoomDatabaseCallback = new RoomDatabase.Callback() {
                    @UnstableApi
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        super.onCreate(db);
                        // Create sync tables and triggers that are not Room entities and
                        // therefore not included in the schema generated from annotations.
                        // MIGRATION_9_10 handles upgrades; this covers fresh installs.
                        createSyncTablesAndTriggers(db);
                        Executors.newSingleThreadExecutor().execute(() -> {
                            Log.d("AppDatabase", "Database onCreate - Prepopolamento StorageLocations");
                            StorageLocationDao dao = INSTANCE.storageLocationDao();
                            if (dao.countLocations() == 0) { // Controlla se è veramente vuoto
                                dao.insertAll(PredefinedData.getInitialStorageLocations());
                                Log.d("AppDatabase", "Prepopolamento StorageLocations completato.");
                            }
                        });
                    }

                    @UnstableApi
                    @Override
                    public void onOpen(@NonNull SupportSQLiteDatabase db) {
                        super.onOpen(db);
                        Executors.newSingleThreadExecutor().execute(() -> {
                            Log.d("AppDatabase", "Database onOpen - Verifica/Aggiornamento StorageLocations predefinite");
                            StorageLocationDao dao = INSTANCE.storageLocationDao();
                            List<StorageLocation> predefined = PredefinedData.getInitialStorageLocations();
                            for (StorageLocation loc : predefined) {
                                StorageLocation existing = dao.getLocationByInternalKeySync(loc.internalKey);
                                if (existing == null) {
                                    dao.insert(loc);
                                    Log.d("AppDatabase", "Inserita location predefinita mancante: " + loc.name);
                                }
                            }
                        });
                    }
                };

                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "dispensa_database")
                            .addMigrations(MIGRATION_6_7)
                            .addMigrations(MIGRATION_7_8)
                            .addMigrations(MIGRATION_8_9)
                            .addMigrations(MIGRATION_9_10)
                            .addCallback(sRoomDatabaseCallback)
                            //.fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}