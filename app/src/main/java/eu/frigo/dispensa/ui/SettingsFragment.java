package eu.frigo.dispensa.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;

import eu.frigo.dispensa.R;
import eu.frigo.dispensa.data.AppDatabase;
import eu.frigo.dispensa.sync.DriveTransportFactory;
import eu.frigo.dispensa.sync.LocalNetworkSyncTransport;
import eu.frigo.dispensa.sync.SyncManager;
import eu.frigo.dispensa.util.LocaleHelper;
import eu.frigo.dispensa.work.ExpiryCheckWorkerScheduler;
import eu.frigo.dispensa.work.SyncWorker;
import eu.frigo.dispensa.work.SyncWorkerScheduler;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static String KEY_EXPIRY_DAYS_BEFORE = "pref_expiry_days_before";
    public static String KEY_LANGUAGE_PREFERENCE = "language_preference";
    public static final String KEY_NOTIFICATION_TIME_HOUR = "pref_notification_time_hour";
    public static final String KEY_NOTIFICATION_TIME_MINUTE = "pref_notification_time_minute";
    public static final String KEY_PREF_ENABLE_OFF_API = "pref_key_enable_off_api";
    public static final String KEY_PREF_DEFAULT_SHELF_LIFE = "pref_key_default_shelf_life";
    public static final String KEY_OFF_CACHE_LIMIT = "pref_off_cache_limit";
    public static final String KEY_OFF_CACHE_TTL_DAYS = "pref_off_cache_ttl_days";
    public static final String KEY_OFF_CACHE_CLEAR = "pref_off_cache_clear";
    public static final String KEY_DEFUALT_ICON = "pref_predefined_tab_icon";
    public static final String KEY_SYNC_LOCAL_NETWORK_ENABLED = SyncWorker.PREF_SYNC_LOCAL_NETWORK_ENABLED;
    public static final String KEY_SYNC_LAST_TIMESTAMP = "sync_last_timestamp";
    public static final String KEY_SYNC_TRIGGER_MANUAL = "sync_trigger_manual";
    public static final String KEY_SYNC_LOCAL_PEERS_STATUS = "sync_local_peers_status";
    public static final String KEY_SYNC_LOCAL_SCAN_PEERS = "sync_local_scan_peers";

    /** Duration (ms) to scan for NSD peers before stopping. */
    private static final long PEER_SCAN_DURATION_MS = 5_000L;

    /** SharedPreferences key where the last-sync epoch-millis is stored. */
    private static final String PREFS_KEY_LAST_SYNC_EPOCH = "sync_last_epoch_ms";

    private static final String TAG = "SettingsFragment";
    private Preference notificationTimePreference;
    private Preference syncLastTimestampPreference;
    private Preference syncLocalPeersStatusPreference;
    private ListPreference languagePreference;
    private ActivityResultLauncher<Intent> googleSignInLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register the Google Sign-In launcher before the fragment is started.
        // The result is dispatched to SyncSettingsHelper (no-op in fdroid flavor).
        googleSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> SyncSettingsHelper.handleSignInResult(this, result));
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        notificationTimePreference = findPreference(getString(R.string.pref_key_exp_time));
        if (notificationTimePreference != null) {
            updateNotificationTimeSummary();
            notificationTimePreference.setOnPreferenceClickListener(preference -> {
                showTimePickerDialog();
                return true;
            });
        }

        EditTextPreference daysBeforePref = findPreference(KEY_EXPIRY_DAYS_BEFORE);
        if (daysBeforePref != null) {
            daysBeforePref.setOnPreferenceChangeListener((preference, newValue) -> {
                try {
                    int days = Integer.parseInt((String) newValue);
                    if (days >= 0 && days <= 365) { // Esempio di range valido
                        return true;
                    }
                } catch (NumberFormatException e) {
                    Log.e("SettingsFragment", "Errore durante la conversione a numero: " + e.getMessage());
                }
                android.widget.Toast.makeText(getContext(), getString(R.string.pref_exp_days_error), android.widget.Toast.LENGTH_SHORT).show();
                return false;
            });
        }
        
        EditTextPreference defaultShelfLifePref = findPreference("pref_key_default_shelf_life");
        if (defaultShelfLifePref != null) {
            defaultShelfLifePref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue == null || ((String) newValue).trim().isEmpty()) {
                    return true;
                }
                try {
                    int days = Integer.parseInt((String) newValue);
                    if (days >= 0 && days <= 3650) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    Log.e("SettingsFragment", "Errore conversione default shelf life: " + e.getMessage());
                }
                android.widget.Toast.makeText(getContext(), getString(R.string.pref_exp_days_error), android.widget.Toast.LENGTH_SHORT).show();
                return false;
            });
        }
        languagePreference = findPreference(KEY_LANGUAGE_PREFERENCE);
        if (languagePreference != null) {
            String currentLangValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(KEY_LANGUAGE_PREFERENCE, "en");
            Log.d("locale SettingsFragment", "onCreatePreferences - Valore lingua letto (con chiave hardcoded): " + currentLangValue);
            languagePreference.setValue(currentLangValue);
            updateLanguagePreferenceSummary(currentLangValue);
        }

        Preference cleanImagesPref = findPreference("pref_clean_images");
        if (cleanImagesPref != null) {
            cleanImagesPref.setOnPreferenceClickListener(preference -> {
                cleanOrphanImages();
                return true;
            });
        }

        Preference clearCachePref = findPreference(KEY_OFF_CACHE_CLEAR);
        if (clearCachePref != null) {
            clearCachePref.setOnPreferenceClickListener(preference -> {
                clearOpenFoodFactCache();
                return true;
            });
        }

        // Sync preferences
        syncLastTimestampPreference = findPreference(KEY_SYNC_LAST_TIMESTAMP);
        updateLastSyncSummary();

        Preference manualSyncPref = findPreference(KEY_SYNC_TRIGGER_MANUAL);
        if (manualSyncPref != null) {
            manualSyncPref.setOnPreferenceClickListener(preference -> {
                SyncWorkerScheduler.triggerManualSync(requireContext());
                android.widget.Toast.makeText(requireContext(),
                        getString(R.string.notify_sync_triggered),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        syncLocalPeersStatusPreference = findPreference(KEY_SYNC_LOCAL_PEERS_STATUS);
        updateLocalPeersStatus(0);

        Preference scanPeersPref = findPreference(KEY_SYNC_LOCAL_SCAN_PEERS);
        if (scanPeersPref != null) {
            scanPeersPref.setOnPreferenceClickListener(preference -> {
                runLocalPeerScan();
                return true;
            });
        }

        // Inject play-flavor Drive preferences (no-op in fdroid flavor)
        SyncSettingsHelper.setup(this);
        SyncSettingsHelper.setSignInLauncher(this, googleSignInLauncher);
    }

    private void clearOpenFoodFactCache() {
        Context context = requireContext();
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            eu.frigo.dispensa.data.openfoodfacts.OpenFoodFactCacheManager.clearAllCache(
                context, eu.frigo.dispensa.data.AppDatabase.getDatabase(context));
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, getString(R.string.notify_cache_cleared), android.widget.Toast.LENGTH_SHORT).show()
            );
        });
    }

    private void cleanOrphanImages() {
        Context context = requireContext();
        eu.frigo.dispensa.data.Repository.cleanOrphanImages(context, count ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(context,
                getString(R.string.notify_clean_images_done, count),
                android.widget.Toast.LENGTH_SHORT).show()));
    }
    private void showTimePickerDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        int currentHour = prefs.getInt(KEY_NOTIFICATION_TIME_HOUR, 9); // Default 9 AM
        int currentMinute = prefs.getInt(KEY_NOTIFICATION_TIME_MINUTE, 0); // Default 00 minutes

        MaterialTimePicker timePicker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(getContext()) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(currentHour)
                .setMinute(currentMinute)
                .setTitleText(getString(R.string.pref_key_exp_time_title))
                .build();

        timePicker.addOnPositiveButtonClickListener(dialog -> {
            int selectedHour = timePicker.getHour();
            int selectedMinute = timePicker.getMinute();

            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_NOTIFICATION_TIME_HOUR, selectedHour);
            editor.putInt(KEY_NOTIFICATION_TIME_MINUTE, selectedMinute);
            editor.apply();
            updateNotificationTimeSummary();
            ExpiryCheckWorkerScheduler.scheduleWorker(requireContext());
        });
        timePicker.show(getParentFragmentManager(), "TIME_PICKER_TAG");
    }
    private void updateNotificationTimeSummary() {
        if (notificationTimePreference != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            int hour = prefs.getInt(KEY_NOTIFICATION_TIME_HOUR, 9);
            int minute = prefs.getInt(KEY_NOTIFICATION_TIME_MINUTE, 0);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            notificationTimePreference.setSummary(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
        }
    }

    private void updateLastSyncSummary() {
        if (syncLastTimestampPreference == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        long epochMs = prefs.getLong(PREFS_KEY_LAST_SYNC_EPOCH, 0L);
        if (epochMs == 0L) {
            syncLastTimestampPreference.setSummary(getString(R.string.pref_sync_last_timestamp_never));
        } else {
            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(epochMs));
            syncLastTimestampPreference.setSummary(formatted);
        }
    }

    private void updateLocalPeersStatus(int count) {
        if (syncLocalPeersStatusPreference == null) return;
        if (count == 0) {
            syncLocalPeersStatusPreference.setSummary(
                    getString(R.string.pref_sync_local_peers_status_none));
        } else {
            syncLocalPeersStatusPreference.setSummary(
                    getString(R.string.pref_sync_local_peers_status_count, count));
        }
    }

    /**
     * Runs a short NSD scan (~{@value #PEER_SCAN_DURATION_MS} ms) on a background thread,
     * then shows the discovered peers in an {@link AlertDialog}.
     */
    private void runLocalPeerScan() {
        Context context = requireContext();
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            SyncManager syncManager = new SyncManager(AppDatabase.getDatabase(context), context);
            LocalNetworkSyncTransport transport =
                    new LocalNetworkSyncTransport(context, syncManager);
            List<NsdServiceInfo> peers = new ArrayList<>();
            boolean interrupted = false;
            try {
                transport.start();
                Thread.sleep(PEER_SCAN_DURATION_MS);
                peers = transport.getDiscoveredPeers(); // snapshot before stop clears the list
            } catch (InterruptedException e) {
                Log.w(TAG, "Peer scan interrupted", e);
                interrupted = true;
                peers = transport.getDiscoveredPeers(); // capture any partial results
            } catch (IOException e) {
                Log.w(TAG, "Peer scan error", e);
            } finally {
                transport.stop();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            final List<NsdServiceInfo> finalPeers = peers;
            new Handler(Looper.getMainLooper()).post(() -> {
                updateLocalPeersStatus(finalPeers.size());
                showPeerScanDialog(finalPeers);
            });
        });
        executor.shutdown();
    }

    private void showPeerScanDialog(List<NsdServiceInfo> peers) {
        if (getContext() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.sync_scan_peers_dialog_title);
        if (peers.isEmpty()) {
            builder.setMessage(R.string.sync_scan_peers_none);
        } else {
            StringBuilder sb = new StringBuilder();
            for (NsdServiceInfo peer : peers) {
                if (sb.length() > 0) sb.append('\n');
                String host = peer.getHost() != null
                        ? peer.getHost().getHostAddress() : "?";
                sb.append(peer.getServiceName())
                        .append(" — ").append(host)
                        .append(':').append(peer.getPort());
            }
            builder.setMessage(sb.toString());
        }
        builder.setPositiveButton(R.string.ok, null);
        builder.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).registerOnSharedPreferenceChangeListener(this);
        updateNotificationTimeSummary();
        updateLastSyncSummary();
        SyncSettingsHelper.refreshAccountSummary(this);
        if (languagePreference != null) {
            String currentLangValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(KEY_LANGUAGE_PREFERENCE, "en");
            Log.d("locale SettingsFragment", "onResume - Valore lingua letto (con chiave hardcoded): " + currentLangValue);
            updateLanguagePreferenceSummary(currentLangValue);
        }
    }
    @Override
    public void onPause() {
        super.onPause();
        Objects.requireNonNull(getPreferenceManager().getSharedPreferences()).unregisterOnSharedPreferenceChangeListener(this);
    }
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Context context = getContext();
        if (KEY_LANGUAGE_PREFERENCE.equals(key)) {
            if (context == null) return;
            String langCode = sharedPreferences.getString(key, "en");
            Log.d("locale SettingsFragment", "onSharedPreferenceChanged lingua selezionata: " + langCode + " applicata.");
            LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(langCode);
            AppCompatDelegate.setApplicationLocales(appLocale);
            updateLanguagePreferenceSummary(langCode);
            SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit();
            editor.putString(KEY_LANGUAGE_PREFERENCE, langCode);
            editor.commit();
            LocaleHelper.setLocale(context,langCode);
            triggerRebirthWithAlarmManager(context);
        } else if (KEY_SYNC_LOCAL_NETWORK_ENABLED.equals(key)) {
            if (context == null) return;
            boolean enabled = sharedPreferences.getBoolean(key, false);
            if (enabled) {
                SyncWorkerScheduler.schedulePeriodicSync(context);
                Log.d(TAG, "Local network sync enabled — periodic work scheduled.");
            } else {
                SyncWorkerScheduler.cancelPeriodicSync(context);
                Log.d(TAG, "Local network sync disabled — periodic work cancelled.");
            }
        } else if (DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED.equals(key)) {
            if (context == null) return;
            boolean enabled = sharedPreferences.getBoolean(key, false);
            // In play flavor: auto-launches sign-in if user is not yet authenticated.
            // In fdroid flavor: no-op.
            SyncSettingsHelper.onDriveEnabledChanged(this, enabled, googleSignInLauncher);
        }
    }
    private void updateLanguagePreferenceSummary(String languageValue) {
        if (languagePreference == null) {
            Log.w("locale SettingsFragment", "updateLanguagePreferenceSummary chiamato ma languagePreference è null");
            return;
        }
        if (languageValue == null) {
            Log.w("locale SettingsFragment", "updateLanguagePreferenceSummary chiamato con languageValue null");
            return;
        }

        Log.d("locale SettingsFragment", "Aggiornamento summary per languageValue: " + languageValue);

        CharSequence[] entries = languagePreference.getEntries();
        CharSequence[] entryValues = languagePreference.getEntryValues();

        if (entries == null || entryValues == null || entries.length != entryValues.length) {
            Log.e("locale SettingsFragment", "Entries o EntryValues non validi per languagePreference.");
            languagePreference.setSummary(languageValue); // Fallback al codice lingua
            return;
        }

        boolean found = false;
        for (int i = 0; i < entryValues.length; i++) {
            if (entryValues[i].toString().equals(languageValue)) {
                languagePreference.setSummary(entries[i]);
                Log.d("locale SettingsFragment", "Summary impostato a: " + entries[i]);
                found = true;
                break;
            }
        }

        if (!found) {
            Log.w("locale SettingsFragment", "Nessuna entry corrispondente trovata per languageValue: " + languageValue + ". Uso il valore come summary.");
            languagePreference.setSummary(languageValue);
        }
    }
    public static void triggerRebirthWithAlarmManager(Context context) {
        if (context == null) {return;}
        System.exit(0);
    }
    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference.getKey() != null && preference.getKey().equals(getString(R.string.pref_key_location))) {
            getParentFragmentManager().beginTransaction()
                .replace(android.R.id.content, new ManageLocationsFragment())
                .addToBackStack(null)
                .commit();
            return false;
        }
        return false;
    }
}
