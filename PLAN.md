# PLAN.md — Dispensa App: Integrated CRDT Sync

## Project Overview

**Dispensa** is an Android pantry management application (issue [#25](https://github.com/enricofrigo/dispensa/issues/25)). This plan implements **integrated CRDT-based sync** between devices using **CR-SQLite** (`io.vlcn:crsqlite-android`), replacing the original Syncthing suggestion with a built-in solution that requires no external app.

- **Package:** `eu.frigo.dispensa`
- **Languages:** Java (primary), Kotlin (build scripts)
- **Min SDK:** 26 (Android 8.0) | **Target SDK:** 35
- **Distribution:** Play Store (`play` flavor) + F-Droid (`fdroid` flavor)
- **Current DB version:** 9 → will migrate to 10 to enable CRDTs
- **CRDT library:** CR-SQLite (MIT licensed, F-Droid compatible)

---

## Architecture Decisions

| Area | Choice | Rationale |
|---|---|---|
| CRDT engine | CR-SQLite (`io.vlcn:crsqlite-android`) | Zero data model changes; works at SQLite layer below Room; MIT licensed; F-Droid compatible |
| Conflict resolution | CR-SQLite built-in LWW (Lamport clocks) | Per-column last-write-wins is the right semantics for product scalar fields; no custom logic needed |
| Sync transport abstraction | `SyncTransport` interface | Allows LocalNetwork and GoogleDrive transports to be swapped or extended without modifying `SyncManager` |
| Local network transport | Android `NsdManager` (mDNS) + TCP sockets | No external dependencies; works on both flavors; no server required |
| Cloud transport | Google Drive REST API v3 + `appDataFolder` | `play` flavor only; private app folder avoids broad Drive permissions; uses `DriveScopes.DRIVE_APPDATA` |
| Authentication (Drive) | Google Sign-In (`play-services-auth`) | `play` flavor only; standard Android pattern |
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
│  Repository ──► AppDatabase (Room + CrSqliteOpenHelperFactory)  │
└──────┬─────────────────────────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────────────────────────┐
│              AppDatabase (SQLite + CR-SQLite extension)         │
│  crsql_changes virtual table ◄─── MIGRATION_9_10 enables CRDTs │
│  on: products, categories_definitions,                          │
│      product_category_links, storage_locations                  │
└──────┬──────────────────────────────────────────────────────────┘
       │ exportChanges() / importChanges()
┌──────▼──────────────────────────────────────────────────────────┐
│                     SyncManager (transport-agnostic)            │
│  - exportChanges(lastVersion) → JSON blob                       │
│  - importChanges(blob) → INSERT INTO crsql_changes              │
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

### Session 2 — Dependencies & Database Migration

**Goal:** Wire in CR-SQLite and create the Room migration that enables CRDT on the four sync tables.

- [ ] Check CR-SQLite `0.1.0-alpha04` for known vulnerabilities
- [ ] Add `io.vlcn:crsqlite-android:0.1.0-alpha04` to `gradle/libs.versions.toml` and `app/build.gradle.kts`
- [ ] Add `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` permissions to `AndroidManifest.xml`
- [ ] Configure `AppDatabase.java` to use `CrSqliteOpenHelperFactory` and bump `@Database` version to 10
- [ ] Implement `MIGRATION_9_10` calling `crsql_as_crr` on `products`, `categories_definitions`, `product_category_links`, `storage_locations`
- [ ] Verify `fdroid` flavor builds and `./gradlew testFdroidDebugUnitTest` passes

**Tests:** Instrumented migration test (`app/src/androidTest/`) confirming version 9→10 succeeds and `crsql_changes` virtual table is accessible.

---

### Session 3 — SyncManager (Transport-Agnostic Core)

**Goal:** Implement `SyncManager` and the `SyncTransport` / `SyncCallback` interfaces.

- [ ] Create `app/src/main/java/eu/frigo/dispensa/sync/SyncTransport.java` (interface)
- [ ] Create `app/src/main/java/eu/frigo/dispensa/sync/SyncCallback.java` (interface)
- [ ] Create `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java`:
  - `exportChanges(long lastSyncVersion)` → JSON blob (`byte[]`)
  - `importChanges(byte[] blob)` → apply via `INSERT INTO crsql_changes`
  - `getLastSyncVersion()` / `persistLastSyncVersion(long version)` via `SharedPreferences`
  - Bootstrap path: `lastSyncVersion == 0` exports full change log (`db_version > 0`)
- [ ] Write JUnit 4 unit tests for `SyncManager` serialisation round-trip (mock `SupportSQLiteDatabase`)

**Tests:** JUnit 4 in `app/src/test/`.

---

### Session 4 — Local Network Transport

**Goal:** Implement mDNS peer discovery and TCP-based change exchange; wire into WorkManager.

- [ ] Create `eu.frigo.dispensa.sync.LocalNetworkSyncTransport` (Java):
  - Register/discover Dispensa service via `NsdManager` (service type `_dispensa._tcp`)
  - On discovery: open TCP socket, push/pull JSON blobs via `SyncManager`
  - Handle `CHANGE_WIFI_MULTICAST_STATE` multicast lock
- [ ] Create `eu.frigo.dispensa.sync.SyncWorker` (WorkManager `Worker`):
  - Instantiate `LocalNetworkSyncTransport` and call `SyncManager` push/pull
  - Schedule periodic sync (15-minute minimum interval via `PeriodicWorkRequest`)
  - Support one-shot manual trigger from Settings
- [ ] Register `SyncWorker` in `Dispensa.java` (application class)
- [ ] Write unit tests for `LocalNetworkSyncTransport` discovery logic (mock `NsdManager`)

**Tests:** JUnit 4 + manual on-device verification.

---

### Session 5 — Google Drive Transport (`play` flavor)

**Goal:** Implement Google Drive sync transport in the `play` product flavor.

- [ ] Add `playImplementation` dependencies: `google-api-services-drive`, `google-api-client-android`, `google-oauth-client-jetty`, `play-services-auth`
- [ ] Create `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` (Java):
  - Google Sign-In with `DriveScopes.DRIVE_APPDATA`
  - Upload: export JSON blob → create/update `.dispensa_sync_changes.json` in `appDataFolder`
  - Download: fetch `.dispensa_sync_changes.json` → import via `SyncManager`
  - Error handling: 401 re-auth, 404 first-sync empty treatment, 429/5xx exponential backoff (max 3 retries)
- [ ] Integrate `GoogleDriveSyncTransport` into `SyncWorker` for `play` flavor (via build-time selection or injection)
- [ ] Write unit tests for upload/download logic (mock Drive client)

**Tests:** JUnit 4; manual Play flavor verification.

---

### Session 6 — Settings UI

**Goal:** Surface sync controls in the existing Settings screen.

- [ ] Add preference XML entries to `app/src/main/res/xml/` (or existing preferences file):
  - `sync_local_network_enabled` — CheckBoxPreference (default: false, both flavors)
  - `sync_drive_enabled` — CheckBoxPreference (`play` flavor only, default: false)
  - `sync_drive_account` — read-only + "Sign Out" button (`play` flavor only)
  - `sync_last_timestamp` — read-only summary
  - `sync_trigger_manual` — Preference button → triggers one-shot `SyncWorker`
- [ ] Update `SettingsFragment.java` to handle preference changes and trigger sync worker
- [ ] Update `SyncWorker` to read preferences before deciding which transports to activate
- [ ] String resources for new preferences (en + it)

**Tests:** Espresso UI test confirming preferences are displayed and manual sync button triggers the worker.

---

### Session 7 — ProGuard & Final Integration

**Goal:** Add ProGuard rules, run full integration validation, update documentation.

- [ ] Add to `app/proguard-rules.pro`:
  - CR-SQLite native methods keep rule
  - Google Drive / API client keep rules (stripped automatically in `fdroid` by R8)
  - `eu.frigo.dispensa.sync.**` keep rule
- [ ] Run `./gradlew assembleFdroidRelease` and `./gradlew assemblePlayRelease` — verify no R8/ProGuard errors
- [ ] Run `./gradlew lint` — fix any new warnings
- [ ] Run full test suite: `./gradlew testFdroidDebugUnitTest` + `./gradlew testPlayDebugUnitTest`
- [ ] Update `README.md` with sync feature description
- [ ] Mark all sessions complete in this file

**Tests:** Full build + lint + unit test pass on both flavors.
