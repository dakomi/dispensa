package eu.frigo.dispensa.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;
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

    /**
     * SharedPreferences key for the user-configurable periodic sync interval (in minutes).
     * Default: {@value #SYNC_INTERVAL_DEFAULT_MINUTES} minutes.
     */
    public static final String PREF_SYNC_INTERVAL_MINUTES = "sync_interval_minutes";

    /**
     * Default periodic sync interval in minutes.
     * WorkManager's minimum repeat interval is 15 minutes; this default gives a more
     * battery-friendly 30-minute cadence while still feeling reasonably responsive.
     */
    public static final long SYNC_INTERVAL_DEFAULT_MINUTES = 30L;

    /** Minimum permitted sync interval (WorkManager platform minimum). */
    public static final long SYNC_INTERVAL_MIN_MINUTES = 15L;

    /**
     * How long (in minutes) to delay a change-triggered one-shot sync after the most recent
     * item mutation.  Because {@link ExistingWorkPolicy#REPLACE} is used, rapid item changes
     * (e.g. scanning a full grocery shop) coalesce: the timer resets on every change and
     * only one sync runs after the editing session is over.
     */
    public static final long SYNC_DEBOUNCE_DELAY_MINUTES = 2L;

    private SyncWorkerScheduler() {
        // utility class — not instantiable
    }

    /**
     * Schedules a recurring background sync using the interval stored in
     * {@link #PREF_SYNC_INTERVAL_MINUTES} (default {@value #SYNC_INTERVAL_DEFAULT_MINUTES} min).
     * Safe to call on every app startup; an existing schedule is <em>updated</em> if the
     * interval has changed, or kept as-is when unchanged.
     *
     * @param context any {@link Context}; the application context is used internally
     */
    public static void schedulePeriodicSync(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
        long intervalMinutes;
        try {
            String raw = prefs.getString(PREF_SYNC_INTERVAL_MINUTES,
                    String.valueOf(SYNC_INTERVAL_DEFAULT_MINUTES));
            intervalMinutes = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            intervalMinutes = SYNC_INTERVAL_DEFAULT_MINUTES;
        }
        // Clamp to WorkManager's minimum.
        intervalMinutes = Math.max(SYNC_INTERVAL_MIN_MINUTES, intervalMinutes);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest syncRequest =
                new PeriodicWorkRequest.Builder(SyncWorker.class,
                        intervalMinutes, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .addTag(PERIODIC_WORK_TAG)
                        .build();

        // UPDATE replaces the schedule when the user changes the interval; a KEEP policy
        // would silently keep the old interval even after the preference changes.
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        PERIODIC_WORK_TAG,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        syncRequest);

        Log.d(TAG, "Periodic sync scheduled (interval=" + intervalMinutes + "min).");
    }

    /**
     * Enqueues an immediate one-shot sync, replacing any already-running manual sync.
     * Use this for explicit user-initiated "Sync now" actions.
     *
     * @param context any {@link Context}
     */
    public static void triggerManualSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest syncRequest =
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(constraints)
                        .addTag(MANUAL_WORK_TAG)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        MANUAL_WORK_TAG,
                        ExistingWorkPolicy.REPLACE,
                        syncRequest);

        Log.d(TAG, "Manual sync triggered (immediate).");
    }

    /**
     * Enqueues a debounced one-shot sync.  The sync is delayed by
     * {@value #SYNC_DEBOUNCE_DELAY_MINUTES} minutes so that a burst of rapid item changes
     * (e.g. scanning a full grocery shop) is collapsed into a single sync run — each new
     * change replaces the previous pending request and resets the timer.
     *
     * @param context any {@link Context}
     */
    public static void triggerDebouncedSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest syncRequest =
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(constraints)
                        .setInitialDelay(SYNC_DEBOUNCE_DELAY_MINUTES, TimeUnit.MINUTES)
                        .addTag(MANUAL_WORK_TAG)
                        .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        MANUAL_WORK_TAG,
                        ExistingWorkPolicy.REPLACE,
                        syncRequest);

        Log.d(TAG, "Debounced sync queued (fires in " + SYNC_DEBOUNCE_DELAY_MINUTES + "min).");
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
