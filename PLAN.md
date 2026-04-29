# PLAN.md — Dispensa App: Integrated CRDT Sync

## Project Overview

**Dispensa** is an Android pantry management application (issue [#25](https://github.com/enricofrigo/dispensa/issues/25)). This plan implements **integrated CRDT-based sync** between devices using **SQLite triggers + a change log**, retaining the standard Android SQLite database (no external CRDT library required) while keeping upstream divergence minimal.

- **Package:** `eu.frigo.dispensa`
- **Languages:** Java (primary), Kotlin (build scripts)
- **Min SDK:** 26 (Android 8.0) | **Target SDK:** 35
- **Distribution:** Play Store (`play` flavor) + F-Droid (`fdroid` flavor)
- **Current DB version:** 9 → migrated to 10 (adds sync infrastructure)
- **CRDT mechanism:** Application-level Lamport-clock LWW via SQLite triggers + `sync_changes` table (no external dependencies)

---

## Architecture Decisions

| Area | Choice | Rationale |
|---|---|---|
| CRDT engine | SQLite triggers + `sync_changes` table (pure SQLite) | No external library needed; zero entity/DAO changes; minimal upstream diff vs. forked repo; works on any Android SQLite (API 26+) |
| Conflict resolution | Lamport-clock LWW per (table, pk) row | `clock` incremented globally on every write; equal clocks broken by `deviceId` lexicographic comparison; per-row semantics are natural for a pantry app |
| Import lock | `sync_import_lock` table (single-row flag, checked by WHEN clause in all triggers) | Prevents `INSERT OR REPLACE` during import from re-firing triggers and creating spurious change log entries |
| Device identity | UUID stored in `SharedPreferences` (`sync_device_id`), added to changes at export time | Stable, zero-permission, no network required |
| Sync transport abstraction | `SyncTransport` interface | Allows LocalNetwork and GoogleDrive transports to be swapped or extended without modifying `SyncManager` |
| Local network transport | Android `NsdManager` (mDNS) + TCP sockets | No external dependencies; works on both flavors; no server required |
| Cloud transport | Google Drive REST API v3 + `appDataFolder` | `play` flavor only; private app folder avoids broad Drive permissions; uses `DriveScopes.DRIVE_APPDATA` |
| Authentication (Drive) | Android Credential Manager (`androidx.credentials` + `googleid`) | `play` flavor only; replaces deprecated `GoogleSignIn` API (removed H2 2025); `Identity.getAuthorizationClient()` handles Drive scopes |
| Background scheduling | WorkManager `SyncWorker` | Reliable; already used in project for expiry checks |
| Change serialization | JSON (Gson, already present) | Human-readable; Gson already a dependency |
| Settings UI | `androidx.preference` | Already used in `SettingsFragment`; consistent UX |
| Language | Java | Matches all existing source files |

---

## Tables and Sync Scope

| Table | Entity | Sync? |
|---|---|---|
| `products` | `Product` | ✅ Yes — core data |
| `categories_definitions` | `CategoryDefinition` | ✅ Yes — user-defined tags |
| `product_category_links` | `ProductCategoryLink` | ✅ Yes — junction table (composite PK: `product_id_fk`, `category_id_fk`) |
| `storage_locations` | `StorageLocation` | ✅ Yes — user-customisable locations |
| `openfoodfact_cache` | `OpenFoodFactCacheEntity` | ❌ No — local API cache, repopulated on demand |

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                          UI Layer                               │
│  SettingsFragment ──► Sync prefs (enable/disable, manual sync)  │
│  ProductListFragment (unchanged — reads Room as before)         │
└────────────────────────────┬────────────────────────────────────┘
                             │ triggers / observes
┌────────────────────────────▼────────────────────────────────────┐
│                       ViewModel Layer                           │
│  ProductViewModel │ AddProductViewModel │ LocationViewModel      │
│  (unchanged — Room DAOs stay identical)                         │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      Repository Layer                           │
│  Repository ──► AppDatabase (standard Room, no custom factory)  │
└──────┬─────────────────────────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────────────────────────┐
│              AppDatabase (standard Android SQLite)              │
│  MIGRATION_9_10 creates:                                        │
│    sync_changes (tbl, pk_val, op, row_json, clock) PK(tbl,pk)   │
│    sync_import_lock (locked)                                    │
│    12 AFTER INSERT/UPDATE/DELETE triggers on all 4 sync tables  │
│    → populate sync_changes with Lamport clock automatically     │
└──────┬──────────────────────────────────────────────────────────┘
       │ exportChanges() / importChanges()
┌──────▼──────────────────────────────────────────────────────────┐
│                  SyncManager (transport-agnostic)               │
│  - exportChanges(lastClock) → JSON blob (+ local deviceId)      │
│  - importChanges(blob) → LWW conflict check → INSERT OR REPLACE │
│  - persistLastSyncVersion(v) → SharedPreferences                │
└──────┬──────────────────────────────────────────────────────────┘
       │ SyncTransport interface
   ┌───┴────────────────────────┐
   ▼                            ▼
LocalNetworkSyncTransport   GoogleDriveSyncTransport
(both flavors)              (play flavor only)
NsdManager + TCP sockets    Drive REST API v3 (appDataFolder)
   │                            │
   └────────────┬───────────────┘
                ▼
         SyncWorker (WorkManager)
         scheduled periodic + manual trigger
```

---

## Phased Session Plan

### Session 1 — Bootstrap & Planning ✅

**Goal:** Set up multi-session tracking infrastructure and document the sync feature plan.

- [x] Create `.github/copilot-instructions.md`
- [x] Create `PLAN.md` with architecture overview and session plan (this file)
- [x] Create `SESSION_NOTES.md` with Session 1 section
- [x] Document existing project structure, DB schema, and sync architecture

**Tests:** No code changes — no tests required.

---

### Session 2 — Dependencies & Database Migration ✅

**Goal:** Add network permissions and bump database to version 10 with sync infrastructure scaffolding.

- [x] Add `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` permissions to `AndroidManifest.xml`
- [x] Bump `@Database(version = 9)` → `version = 10` in `AppDatabase.java`
- [x] Create Room schema file for version 10
- [x] Create `MigrationTest.java` scaffold
- [x] Add `mockito-core:5.11.0` test dependency

**Tests:** Instrumented migration test (`app/src/androidTest/`) — run on device.

---

### Session 3 — SyncManager (Transport-Agnostic Core) ✅

**Goal:** Implement `SyncManager` and the `SyncTransport` / `SyncCallback` interfaces using a trigger-based CRDT change log (no external dependencies).

- [x] Create `SyncTransport.java` (interface)
- [x] Create `SyncCallback.java` (interface)
- [x] Create `SyncChange.java` (DTO: tbl, pkVal, op, rowJson, clock, deviceId)
- [x] Create `SyncBlob.java` (JSON wrapper)
- [x] Implement `MIGRATION_9_10` in `AppDatabase.java`:
  - `sync_changes` table (PK = tbl + pk_val; stores Lamport clock + full row as JSON)
  - `sync_import_lock` table (single-row flag preventing trigger re-fire during import)
  - 12 AFTER INSERT/UPDATE/DELETE triggers on the 4 sync tables
- [x] Remove `CrSqliteOpenHelperFactory` stub and `.openHelperFactory()` call from `AppDatabase.java`
- [x] Remove unused `crsqlite` entries from `libs.versions.toml` and `build.gradle.kts`
- [x] Implement `SyncManager.java`:
  - `exportChanges(long lastSyncVersion)` — queries `sync_changes WHERE clock > ?`, appends local deviceId, returns JSON blob
  - `importChanges(byte[] blob)` — Lamport-clock LWW conflict check, per-table `INSERT OR REPLACE` with import lock
  - `getLastSyncVersion()` / `persistLastSyncVersion(long)` via `SharedPreferences`
  - `getLocalDeviceId()` — stable UUID in `SharedPreferences`
- [x] Write 16 JUnit 4 unit tests in `app/src/test/`
- [x] Update `MigrationTest.java` to verify `sync_changes`, `sync_import_lock`, and all 12 triggers

**Tests:** JUnit 4 in `app/src/test/` — **all 16 tests pass**.

---

### Session 4 — Local Network Transport

**Goal:** Implement mDNS peer discovery and TCP-based change exchange; wire into WorkManager.

- [x] Create `eu.frigo.dispensa.sync.LocalNetworkSyncTransport` (Java):
  - Register/discover Dispensa service via `NsdManager` (service type `_dispensa._tcp`)
  - On discovery: open TCP socket, push/pull JSON blobs via `SyncManager`
  - Handle `CHANGE_WIFI_MULTICAST_STATE` multicast lock
- [x] Create `eu.frigo.dispensa.work.SyncWorker` (WorkManager `Worker`):
  - Instantiate `LocalNetworkSyncTransport` and call `SyncManager` export/import
  - Schedule periodic sync (15-minute minimum interval via `PeriodicWorkRequest`)
  - Support one-shot manual trigger from Settings
- [x] Create `eu.frigo.dispensa.work.SyncWorkerScheduler` (schedulePeriodicSync / triggerManualSync / cancelPeriodicSync)
- [x] Register `SyncWorker` in `Dispensa.java` (application class), gated on `sync_local_network_enabled` preference
- [x] Add `getMaxSyncClock()` helper to `SyncManager` for post-sync version tracking
- [x] Write unit tests for `LocalNetworkSyncTransport` discovery logic (mock `NsdManager`)
- [x] Add `testOptions { unitTests { isReturnDefaultValues = true } }` to `build.gradle.kts`

**Tests:** JUnit 4 — all 29 unit tests pass.

---

### Session 5 — Google Drive Transport (`play` flavor)

**Goal:** Implement Google Drive sync transport in the `play` product flavor.

- [x] Add `playImplementation` dependencies: `google-api-services-drive`, `google-api-client-android`, `google-http-client-gson`, `play-services-auth`
- [x] Create `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` (Java):
  - Google Sign-In with `DriveScopes.DRIVE_APPDATA`
  - Upload: export JSON blob → create/update `.dispensa_sync_changes.json` in `appDataFolder`
  - Download: fetch `.dispensa_sync_changes.json` → import via `SyncManager`
  - Error handling: 401 re-auth, 404 first-sync empty treatment, 429/5xx exponential backoff (max 3 retries)
- [x] Integrate `GoogleDriveSyncTransport` into `SyncWorker` for `play` flavor
- [x] Write unit tests for upload/download logic (mock Drive client)

**Tests:** JUnit 4; manual Play flavor verification.

---

### Session 6 — Settings UI ✅

**Goal:** Surface sync controls in the existing Settings screen.

- [x] Add preference XML entries:
  - `sync_local_network_enabled` — CheckBoxPreference (default: false, both flavors)
  - `sync_drive_enabled` — CheckBoxPreference (`play` flavor only, default: false)
  - `sync_drive_account` — read-only + "Sign Out" button (`play` flavor only)
  - `sync_last_timestamp` — read-only summary
  - `sync_trigger_manual` — Preference button → triggers one-shot `SyncWorker`
- [x] Update `SettingsFragment.java` to handle preference changes and trigger sync worker
- [x] String resources for new preferences (en + it)
- [x] Create `SyncSettingsHelper` in `play/` and `fdroid/` flavors (flavor-specific Drive UI injection)

**Tests:** JUnit unit tests — all 45 play + 29 fdroid tests pass. (Espresso UI test deferred — no Espresso test runner configured in the project.)

---

### Session 7 — ProGuard & Final Integration ✅

**Goal:** Add ProGuard rules, run full integration validation, update documentation.

- [x] Add to `app/proguard-rules.pro`:
  - `eu.frigo.dispensa.sync.**` keep rule
  - Gson serialization keep rules for `SyncChange` / `SyncBlob`
- [x] Run `./gradlew assembleFdroidRelease` and `./gradlew assemblePlayRelease` — verify no R8/ProGuard errors
- [x] Run `./gradlew lint` — fix any new warnings
- [x] Run full test suite: `./gradlew testFdroidDebugUnitTest` + `./gradlew testPlayDebugUnitTest`
- [x] Update `README.md` with sync feature description
- [x] Mark all sessions complete in this file

**Tests:** Full build + lint + unit test pass on both flavors.

---

### Session 8 — Release v0.1.9.1 ✅

**Goal:** Bump version and publish release APKs via GitHub Actions.

- [x] Bump `versionCode` / `versionName` in `app/build.gradle.kts`
- [x] Add `signingConfigs.release` block reading credentials from env vars
- [x] Create `.github/workflows/release.yml` for fdroid + play release APKs

**Tests:** Release workflow verified manually.

---

### Session 9 — Google Sign-In Flow ✅

**Goal:** Add a "Sign In with Google" button and flow so users can authenticate before enabling Drive sync.

- [x] Add `sync_drive_sign_in` preference to `app/src/play/res/xml/preferences_sync_drive.xml`
- [x] Rewrite `SyncSettingsHelper` (play): sign-in button, visibility toggling, `setSignInLauncher`, `handleSignInResult`, `onDriveEnabledChanged`
- [x] Add no-op stubs to `SyncSettingsHelper` (fdroid): `setSignInLauncher`, `handleSignInResult`, `onDriveEnabledChanged`
- [x] Update `SettingsFragment`: register `ActivityResultLauncher<Intent>` in `onCreate()`, call `setSignInLauncher()` after setup, wire `sync_drive_enabled` toggle
- [x] Add string resources (en + it): `pref_sync_drive_sign_in_title/summary`, `notify_sync_signed_in`, `notify_sync_sign_in_failed`
- [x] Both flavors compile; all 74 unit tests pass

**Tests:** Compile verification on fdroid + play; all unit tests pass.

---

### Session 10 — Peer Discovery & Pairing UI

**Goal:** Surface local-network peer discovery status and add an explicit Drive connection-test button.

- [x] Add `sync_local_peers_status` read-only preference to `preferences.xml` showing discovered peer count
- [x] Add `sync_local_scan_peers` tappable preference button that runs a short NSD scan and displays results in a dialog
- [x] Add `sync_drive_test_connection` tappable preference to `preferences_sync_drive.xml` (play) that verifies Drive connectivity and reports success/failure
- [x] Update `SyncSettingsHelper` (play) to wire the Drive test-connection button
- [x] Update `SettingsFragment` to wire the local-scan button; show discovered peer names/IPs in an `AlertDialog`
- [x] Add string resources (en + it)

**Tests:** Compile verification on both flavors.

---

### Session 11 — Multi-Account Household Drive Sync ✅

**Goal:** Implement shared-folder Drive sync so multiple Google accounts can sync the same pantry (Pathway 1 from the Session 10 analysis).

- [x] Create `HouseholdManager` (play) — `createHousehold`, `grantAccess`, `verifyAndJoin`, `generateJoinDeepLink`, `buildDrive`, SharedPreferences helpers
- [x] Add `HouseholdDriveOperations` inner class to `GoogleDriveSyncTransport` — per-device files in shared folder, merged peer download, `DRIVE_FILE` scope
- [x] Add household-mode constructor to `GoogleDriveSyncTransport(Context, Account, folderId, deviceId)`
- [x] Update `DriveTransportFactory` — route to household transport when `HouseholdManager.getHouseholdFolderId()` is non-null
- [x] Update `SyncSettingsHelper` (play) — `DRIVE_FILE` scope in sign-in options; create/join/leave household dialogs; `handleHouseholdDeepLink()`; `refreshSignInState()` shows household status
- [x] Add no-op `handleHouseholdDeepLink()` to `SyncSettingsHelper` (fdroid)
- [x] Add 4 household prefs to `preferences_sync_drive.xml` (play): status, create, join, leave
- [x] Add deep-link intent-filter (`dispensa://household?folderId=…`) to `SettingsActivity` in `AndroidManifest.xml`
- [x] Update `SettingsActivity` to parse deep-link and pass folderId to `SettingsFragment` as Bundle arg
- [x] Add `ARG_HOUSEHOLD_FOLDER_ID` to `SettingsFragment`; call `handleHouseholdDeepLink()` when arg is present
- [x] Add 19 string resources each for en + it
- [x] Write `HouseholdManagerTest` (9 unit tests)
- [x] Both flavors compile; all 54 unit tests pass (play + fdroid combined)
- [x] Sign-out also clears household folder ID

**Tests:** 54 unit tests pass; both flavors BUILD SUCCESSFUL.

---

### Session 12 — Sharing Permission Management ✅

**Goal:** Add a device allowlist for local sync and clarify Drive sharing model in the UI.

- [x] Create `SyncPermissionManager` (main) that maintains a persisted set of trusted device UUIDs in `SharedPreferences`
- [x] Add `senderDeviceId` field to `SyncBlob`; populate in `SyncManager.exportChanges()`; add `SyncManager.extractSenderDeviceId()` helper
- [x] Modify `LocalNetworkSyncTransport.handleIncomingConnection()` to read device ID from the blob and reject unknown devices (including those without an ID); add `SyncPermissionManager` dependency
- [x] Add `ManageSyncDevicesFragment` (main) listing trusted/pending devices with approve/revoke/dismiss actions
- [x] Add `sync_manage_devices` preference entry in `preferences.xml` that navigates to `ManageSyncDevicesFragment`
- [x] Drive sharing info preference superseded by Session 11's `KEY_HOUSEHOLD_STATUS`; skipped to avoid redundancy
- [x] Add string resources (en + it)
- [x] Write unit tests for `SyncPermissionManager` (11 tests)

**Tests:** 103 unit tests pass (fdroid + play combined).

---

### Session 13 — Debug Logging Build ✅

**Goal:** Create a file-based debug logger so the user can export a log file to help diagnose sign-in and Drive sync issues.

- [x] Create `eu.frigo.dispensa.util.DebugLogger` — singleton file logger (writes to `dispensa_debug.log` in `filesDir`; 1 MB rotation; also calls `android.util.Log`)
- [x] Init `DebugLogger` in `Dispensa.java` Application `onCreate()`
- [x] Update `file_paths.xml` — add `<files-path>` entry so `FileProvider` can share the log file
- [x] Add `pref_cat_debug` preference category to `preferences.xml` with two prefs: `pref_debug_export_log` and `pref_debug_clear_log`
- [x] Wire both prefs in `SettingsFragment` — export shares via `ACTION_SEND` + `FileProvider`; clear calls `DebugLogger.clear()`
- [x] Add DebugLogger calls to `SyncSettingsHelper` (play) — sign-in launch/result, Drive toggle, household create/join, sign-out, test connection
- [x] Add DebugLogger calls to `GoogleDriveSyncTransport` — push/pull outcomes
- [x] Add DebugLogger calls to `DriveTransportFactory` — routing decisions (disabled / no account / solo / household)
- [x] Add DebugLogger calls to `HouseholdManager` — createHousehold, grantAccess, verifyAndJoin, clearHouseholdFolderId
- [x] Add DebugLogger calls to `LocalNetworkSyncTransport` — start/stop, push, handleIncomingConnection, NSD registration/discovery callbacks, resolve
- [x] Add DebugLogger calls to `SyncManager` — exportChanges, importChanges
- [x] Add DebugLogger calls to `SyncWorker` — full sync cycle (local + Drive)
- [x] Add DebugLogger calls to `SyncPermissionManager` — trust/revoke/markPending/dismissPending
- [x] Add string resources (en + it)
- [x] Both flavors compile; all unit tests pass

**Tests:** All unit tests pass (fdroid + play); both flavors BUILD SUCCESSFUL.

---

### Session 14 — Google Sign-In Troubleshooting ✅

**Goal:** Analyse debug logs from the user, identify why Google Sign-In was silently failing, and fix the auth flow.

- [x] Analyse two exported debug logs:
  - **Sign-in button log:** `launchSignIn` succeeded, but the process immediately restarted (`=== Dispensa debug log opened ===` appeared 154 ms after `launcher.launch()`) — indicating an unhandled exception caused a crash before the result callback was reached.
  - **Checkbox log:** `handleSignInResult` was called with `resultCode=0` (`RESULT_CANCELED`) despite the user selecting an account — sign-in returned silently with no feedback.
- [x] Research root cause via Google developer documentation: confirmed that `play-services-auth` 21.x **requires Drive scopes to be requested via a separate `Identity.getAuthorizationClient().authorize()` call** after sign-in; bundling `DRIVE_APPDATA`/`DRIVE_FILE` inside `GoogleSignInOptions` now silently returns `RESULT_CANCELED`.
- [x] Fix `SyncSettingsHelper` (play) — short-term defensive fixes:
  - `handleSignInResult`: extract and log `ApiException` status code when `resultCode != RESULT_OK` but `getData() != null`; show user-facing Toast on failure (was silent).
  - `launchSignIn`: wrap `launcher.launch()` in `try/catch(IllegalStateException)` to log and show a Toast instead of crashing.
- [x] Migrate `SyncSettingsHelper` (play) to the two-step auth flow:
  - `launchSignIn`: stripped `DRIVE_APPDATA`/`DRIVE_FILE` from `GoogleSignInOptions`; now requests email only.
  - Added `launchDriveAuthorization()`: calls `Identity.getAuthorizationClient().authorize()` with `DRIVE_APPDATA` + `DRIVE_FILE`; if scopes already granted, enables sync immediately; if consent is needed, launches the consent screen via `googleDriveAuthLauncher`.
  - `handleSignInResult` success path: refreshes UI then calls `launchDriveAuthorization` (no longer enables Drive sync directly).
  - Added `handleAuthorizationResult()`: processes consent-screen result; stores `AuthorizationResult`; calls `completeDriveAuthorization()` on success or `onDriveAuthorizationFailed()` on failure/cancel.
  - Added `completeDriveAuthorization()`: enables `sync_drive_enabled` pref and shows confirmation Toast.
  - Added `onDriveAuthorizationFailed()`: reverts the Drive sync toggle and shows error Toast.
  - `onDriveEnabledChanged`: when account is already present, now calls `launchDriveAuthorization` (handles re-auth when toggle is re-enabled).
- [x] Update `SyncSettingsHelper` (fdroid): updated no-op stub signatures to match new method signatures.
- [x] Update `SettingsFragment`: added `googleDriveAuthLauncher` field (`ActivityResultLauncher<IntentSenderRequest>` via `StartIntentSenderForResult`); registered in `onCreate()`; updated `handleSignInResult` and `onDriveEnabledChanged` call sites.
- [x] Both flavors compile; all unit tests pass.

**Tests:** Both flavors BUILD SUCCESSFUL; all unit tests pass.

---

### Session 16 — Drive API Crash Fixes + Stability Hardening

**Goal:** Fix crashes in "Test Drive connection" and "Create a household" that appear after a successful sign-in / Drive authorization.

- [x] Analyse exported debug log to identify root causes
- [x] Fix `GoogleDriveSyncTransport.pull()` — add `catch (Exception e)` so any non-`IOException` (e.g. `RuntimeException` from `GoogleAccountCredential` / `play-services-auth 21.x`) is caught, logged, and forwarded to the callback instead of crashing the thread
- [x] Fix `GoogleDriveSyncTransport.push()` — same defensive catch
- [x] Fix `SyncSettingsHelper.testDriveConnection()` — wrap executor lambda body in try-catch so no exception can escape to the thread's `UncaughtExceptionHandler`
- [x] Fix `SyncSettingsHelper.createHousehold()` — guard success `mainHandler.post()` with `fragment.isAdded()` before calling `refreshSignInState(fragment)`; wrap `showDeepLinkDialog()` in try-catch to prevent `WindowManager$BadTokenException` if the Activity has been recreated
- [x] Fix `SyncSettingsHelper.joinHousehold()` — same `fragment.isAdded()` guard in success callback
- [x] Update SESSION_NOTES.md and PLAN.md

**Tests:** Play flavor BUILD SUCCESSFUL; all unit tests pass.

---

### Session 18 — Empty Household Folder: Scheduler Bug + Sync-on-Change + Context-Aware Sync Button

**Goal:** Fix the root cause of empty household folder (SyncWorker never scheduled for Drive-only users), add sync-on-change after pantry item mutations, and add a context-aware manual sync button.

- [x] Fix `Dispensa.java` `scheduleSyncIfEnabled()` — now also checks `sync_drive_enabled`; schedules periodic sync when either local-network OR Drive sync is enabled
- [x] Fix `SettingsFragment.onSharedPreferenceChanged()` for `sync_drive_enabled` — start periodic sync when Drive is enabled, stop it (when local is also off) when Drive is disabled
- [x] Fix `SettingsFragment.onSharedPreferenceChanged()` for `sync_local_network_enabled` — only cancel periodic sync when both local AND Drive are disabled
- [x] Feature: trigger `SyncWorkerScheduler.triggerManualSync()` after every product insert/delete/update in `Repository.java` (covers `insert`, `delete`, `update`, `insertProductWithApiTags`, `updateProductWithApiTags`)
- [x] Feature: context-aware "Sync now" button — `updateManualSyncSummary()` sets the summary to reflect active sync modes (local / Drive / both / none); button is disabled when no sync is enabled
- [x] Add 4 new string resources (en + it): `pref_sync_trigger_manual_summary_local/drive/both/none`
- [x] Household deep-link dialog: copy-to-clipboard button + QR code (ZXing core 3.5.3 play dep)
- [x] Household folder friendly name stored in SharedPreferences and shown in status preference
- [x] Notification when a new untrusted device is first added to pending list

**Tests:** Both flavors BUILD SUCCESSFUL; all 26 unit tests pass.
