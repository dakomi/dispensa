package eu.frigo.dispensa.sync;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import eu.frigo.dispensa.util.DebugLogger;

/**
 * Manages the allowlist of trusted device UUIDs for local-network sync.
 *
 * <p>When an incoming TCP connection arrives with a {@link SyncBlob#senderDeviceId},
 * {@link LocalNetworkSyncTransport} consults this manager:
 * <ul>
 *   <li><b>Trusted</b> — sync proceeds normally.</li>
 *   <li><b>Unknown / no ID</b> — the sender is rejected.  If a device UUID was supplied
 *       it is added to the pending set so the user can approve it from
 *       {@link eu.frigo.dispensa.ui.ManageSyncDevicesFragment}.  Devices that send no
 *       UUID at all (older clients) are rejected silently until they upgrade.</li>
 * </ul>
 *
 * <p>Data is persisted in a dedicated {@code SharedPreferences} file
 * ({@value #PREFS_FILE}) so it survives app restarts.
 */
public class SyncPermissionManager {

    static final String PREFS_FILE = "sync_permissions";
    private static final String PREF_TRUSTED = "trusted_device_ids";
    private static final String PREF_PENDING = "pending_device_ids";

    private final SharedPreferences prefs;

    /** Production constructor — uses a private SharedPreferences file. */
    public SyncPermissionManager(Context context) {
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE));
    }

    /** Package-private constructor for unit tests. */
    SyncPermissionManager(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // ── Trust queries ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given device UUID is in the trusted set.
     */
    public boolean isTrusted(String deviceId) {
        if (deviceId == null) return false;
        return getTrustedDeviceIds().contains(deviceId);
    }

    // ── Trust management ──────────────────────────────────────────────────────

    /**
     * Adds {@code deviceId} to the trusted set and removes it from pending.
     */
    public void trust(String deviceId) {
        if (deviceId == null) return;
        Set<String> trusted = new HashSet<>(getTrustedDeviceIds());
        Set<String> pending = new HashSet<>(getPendingDeviceIds());
        trusted.add(deviceId);
        pending.remove(deviceId);
        prefs.edit()
                .putStringSet(PREF_TRUSTED, trusted)
                .putStringSet(PREF_PENDING, pending)
                .apply();
    }

    /**
     * Removes {@code deviceId} from the trusted set.
     * The device is not re-added to pending; it will be marked pending again
     * on its next connection attempt.
     */
    public void revoke(String deviceId) {
        if (deviceId == null) return;
        Set<String> trusted = new HashSet<>(getTrustedDeviceIds());
        trusted.remove(deviceId);
        prefs.edit().putStringSet(PREF_TRUSTED, trusted).apply();
    }

    /**
     * Adds {@code deviceId} to the pending set if it is not already trusted.
     * Called by {@link LocalNetworkSyncTransport} when an unknown device connects.
     */
    public void markPending(String deviceId) {
        if (deviceId == null || isTrusted(deviceId)) return;
        Set<String> pending = new HashSet<>(getPendingDeviceIds());
        pending.add(deviceId);
        prefs.edit().putStringSet(PREF_PENDING, pending).apply();
    }

    /**
     * Removes {@code deviceId} from the pending set without trusting it.
     * Can be used to dismiss a spurious or unwanted connection attempt.
     */
    public void dismissPending(String deviceId) {
        if (deviceId == null) return;
        Set<String> pending = new HashSet<>(getPendingDeviceIds());
        pending.remove(deviceId);
        prefs.edit().putStringSet(PREF_PENDING, pending).apply();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable snapshot of trusted device UUIDs.
     */
    public Set<String> getTrustedDeviceIds() {
        Set<String> stored = prefs.getStringSet(PREF_TRUSTED, null);
        return stored != null
                ? Collections.unmodifiableSet(new HashSet<>(stored))
                : Collections.emptySet();
    }

    /**
     * Returns an unmodifiable snapshot of pending (unknown) device UUIDs.
     */
    public Set<String> getPendingDeviceIds() {
        Set<String> stored = prefs.getStringSet(PREF_PENDING, null);
        return stored != null
                ? Collections.unmodifiableSet(new HashSet<>(stored))
                : Collections.emptySet();
    }
}
