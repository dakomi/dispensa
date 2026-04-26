package io.vlcn.crsqlite;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;

/**
 * Placeholder implementation of {@code CrSqliteOpenHelperFactory}.
 *
 * <p><strong>This is a compile-time stub.</strong>  The real
 * {@code io.vlcn:crsqlite-android} library (which ships a custom SQLite build with the
 * CR-SQLite extension compiled in) is not yet available from a public Maven repository.
 * When the real library is added as a Gradle dependency its class will shadow this stub
 * and the full CRDT functionality will be activated.
 *
 * <p>Without the real library the {@code crsql_as_crr()} calls in
 * {@code AppDatabase.MIGRATION_9_10} and the {@code crsql_changes} virtual table used by
 * {@code SyncManager} will throw at runtime.  All non-sync features of the app remain
 * unaffected.
 */
public class CrSqliteOpenHelperFactory implements SupportSQLiteOpenHelper.Factory {

    private static final String TAG = "CrSqliteFactory";

    private final SupportSQLiteOpenHelper.Factory delegate = new FrameworkSQLiteOpenHelperFactory();

    @NonNull
    @Override
    public SupportSQLiteOpenHelper create(
            @NonNull SupportSQLiteOpenHelper.Configuration configuration) {
        Log.w(TAG, "CrSqliteOpenHelperFactory stub active — CR-SQLite extension NOT loaded. "
                + "Sync features will not work until the real io.vlcn:crsqlite-android "
                + "library is available.");
        return delegate.create(configuration);
    }
}
