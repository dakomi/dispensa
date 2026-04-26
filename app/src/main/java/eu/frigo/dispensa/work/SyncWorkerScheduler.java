package eu.frigo.dispensa.work;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Utility class for scheduling and cancelling {@link SyncWorker} via WorkManager.
 *
 * <h3>Periodic sync</h3>
 * Call {@link #schedulePeriodicSync(Context)} to enqueue a periodic sync that runs at most
 * every {@value #SYNC_INTERVAL_MINUTES} minutes (the WorkManager minimum).  Uses
 * {@link ExistingPeriodicWorkPolicy#KEEP} so that an already-scheduled request is not
 * replaced on every app launch.
 *
 * <h3>Manual sync</h3>
 * Call {@link #triggerManualSync(Context)} to enqueue a one-shot sync immediately.  Uses
 * {@link ExistingWorkPolicy#REPLACE} so that repeated manual taps always start a fresh run.
 */
public class SyncWorkerScheduler {

    private static final String TAG = "SyncWorkerScheduler";

    /** WorkManager unique work name for the periodic sync job. */
    public static final String PERIODIC_WORK_TAG = "periodicSyncWork";

    /** WorkManager unique work name / tag for manual one-shot sync. */
    public static final String MANUAL_WORK_TAG = SyncWorker.TAG_MANUAL;

    /** Minimum repeat interval (minutes) — must be ≥ 15 per WorkManager constraints. */
    public static final long SYNC_INTERVAL_MINUTES = 15L;

    private SyncWorkerScheduler() {
        // utility class — not instantiable
    }

    /**
     * Schedules a recurring background sync.  Safe to call on every app startup; an existing
     * schedule is preserved ({@link ExistingPeriodicWorkPolicy#KEEP}).
     *
     * @param context any {@link Context}; the application context is used internally
     */
    public static void schedulePeriodicSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest syncRequest =
                new PeriodicWorkRequest.Builder(SyncWorker.class,
                        SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .addTag(PERIODIC_WORK_TAG)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        PERIODIC_WORK_TAG,
                        ExistingPeriodicWorkPolicy.KEEP,
                        syncRequest);

        Log.d(TAG, "Periodic sync scheduled (interval=" + SYNC_INTERVAL_MINUTES + "min).");
    }

    /**
     * Enqueues a one-shot manual sync, replacing any already-running manual sync.
     *
     * @param context any {@link Context}
     */
    public static void triggerManualSync(Context context) {
        OneTimeWorkRequest syncRequest =
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .addTag(MANUAL_WORK_TAG)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        MANUAL_WORK_TAG,
                        ExistingWorkPolicy.REPLACE,
                        syncRequest);

        Log.d(TAG, "Manual sync triggered.");
    }

    /**
     * Cancels any scheduled periodic sync.  Call when the user disables local-network sync
     * in Settings.
     *
     * @param context any {@link Context}
     */
    public static void cancelPeriodicSync(Context context) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(PERIODIC_WORK_TAG);
        Log.d(TAG, "Periodic sync cancelled.");
    }
}
