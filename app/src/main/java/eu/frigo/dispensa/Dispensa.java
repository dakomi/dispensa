package eu.frigo.dispensa;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import eu.frigo.dispensa.util.DebugLogger;
import eu.frigo.dispensa.util.ThemeHelper;
import eu.frigo.dispensa.work.ExpiryCheckWorker;
import eu.frigo.dispensa.work.ExpiryCheckWorkerScheduler;
import eu.frigo.dispensa.work.SyncWorker;
import eu.frigo.dispensa.work.SyncWorkerScheduler;

public class Dispensa extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogger.init(this);
        ThemeHelper.applyTheme(this);
        createNotificationChannel();
        ExpiryCheckWorkerScheduler.scheduleWorker(this);
        scheduleSyncIfEnabled();
    }

    private void scheduleSyncIfEnabled() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean localEnabled = prefs.getBoolean(SyncWorker.PREF_SYNC_LOCAL_NETWORK_ENABLED, false);
        boolean driveEnabled = prefs.getBoolean(
                eu.frigo.dispensa.sync.DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED, false);
        if (localEnabled || driveEnabled) {
            SyncWorkerScheduler.schedulePeriodicSync(this);
        }
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.expiry_notification_channel_name);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(ExpiryCheckWorker.CHANNEL_ID, name, importance);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }
}