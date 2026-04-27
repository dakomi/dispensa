package eu.frigo.dispensa.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SyncPermissionManagerTest {

    /**
     * Minimal in-memory SharedPreferences for unit tests.
     * Supports {@code getStringSet}, {@code putStringSet}, and {@code apply()}.
     */
    private static class FakeSharedPreferences implements SharedPreferences {

        private final Map<String, Object> store = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return store;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = store.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = store.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = store.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = store.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = store.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = store.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return store.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>(store);

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(String key, Set<String> values) {
                    pending.put(key, values != null ? new HashSet<>(values) : null);
                    return this;
                }

                @Override
                public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(String key, float value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    pending.remove(key);
                    return this;
                }

                @Override
                public Editor clear() {
                    pending.clear();
                    return this;
                }

                @Override
                public boolean commit() {
                    store.clear();
                    store.putAll(pending);
                    return true;
                }

                @Override
                public void apply() {
                    commit();
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }

    // ── Test setup ────────────────────────────────────────────────────────────

    private SyncPermissionManager manager;

    @Before
    public void setUp() {
        manager = new SyncPermissionManager(new FakeSharedPreferences());
    }

    // ── isTrusted ─────────────────────────────────────────────────────────────

    @Test
    public void isTrusted_returnsFalse_forUnknownDevice() {
        assertFalse(manager.isTrusted("device-abc"));
    }

    @Test
    public void isTrusted_returnsFalse_forNullDeviceId() {
        assertFalse(manager.isTrusted(null));
    }

    @Test
    public void isTrusted_returnsTrue_afterTrust() {
        manager.trust("device-abc");
        assertTrue(manager.isTrusted("device-abc"));
    }

    // ── trust / revoke ────────────────────────────────────────────────────────

    @Test
    public void trust_movesDeviceFromPendingToTrusted() {
        manager.markPending("device-xyz");
        assertTrue(manager.getPendingDeviceIds().contains("device-xyz"));

        manager.trust("device-xyz");

        assertTrue(manager.isTrusted("device-xyz"));
        assertFalse(manager.getPendingDeviceIds().contains("device-xyz"));
    }

    @Test
    public void revoke_removesDeviceFromTrusted() {
        manager.trust("device-abc");
        assertTrue(manager.isTrusted("device-abc"));

        manager.revoke("device-abc");

        assertFalse(manager.isTrusted("device-abc"));
    }

    @Test
    public void revoke_doesNotAddDeviceToPending() {
        manager.trust("device-abc");
        manager.revoke("device-abc");

        assertFalse(manager.getPendingDeviceIds().contains("device-abc"));
    }

    // ── markPending / dismissPending ──────────────────────────────────────────

    @Test
    public void markPending_addsDeviceToPendingSet() {
        manager.markPending("device-new");
        assertTrue(manager.getPendingDeviceIds().contains("device-new"));
    }

    @Test
    public void markPending_doesNotAddAlreadyTrustedDevice() {
        manager.trust("device-trusted");
        manager.markPending("device-trusted");

        assertFalse(manager.getPendingDeviceIds().contains("device-trusted"));
    }

    @Test
    public void dismissPending_removesDeviceFromPendingSet() {
        manager.markPending("device-unwanted");
        manager.dismissPending("device-unwanted");

        assertFalse(manager.getPendingDeviceIds().contains("device-unwanted"));
    }

    @Test
    public void dismissPending_doesNotAffectTrustedDevices() {
        manager.trust("device-trusted");
        manager.dismissPending("device-trusted");

        assertTrue(manager.isTrusted("device-trusted"));
    }
}
