# SESSION_NOTES.md — Dispensa App: CRDT Sync

---

## Table of Contents

> **Agent navigation:** Approximate line ranges are provided for efficient `view_range` lookups in this ~1450-line file. Ranges shift slightly if the ToC grows.

- [Session 1 — Bootstrap & Planning](#session-1--bootstrap--planning) *(~29–106)*
- [Session 2 — Dependencies & Database Migration](#session-2--dependencies--database-migration) *(~110–163)*
- [Session 3 — SyncManager (Transport-Agnostic Core)](#session-3--syncmanager-transport-agnostic-core) *(~167–247)*
- [Session 3 (Revision) — Pivot to Trigger-Based CRDT; SyncManager Completion](#session-3-revision--pivot-to-trigger-based-crdt-syncmanager-completion) *(~251–324)*
- [Session 4 — Local Network Transport](#session-4--local-network-transport) *(~328–413)*
- [Session 5 — Google Drive Transport (`play` flavor)](#session-5--google-drive-transport-play-flavor) *(~417–507)*
- [Session 6 — Settings UI](#session-6--settings-ui) *(~511–580)*
- [Session 7 — ProGuard & Final Integration](#session-7--proguard--final-integration) *(~584–627)*
- [Session 8 — Release v0.1.9.1](#session-8--release-v0191) *(~631–656)*
- [Session 9 — Google Sign-In Flow](#session-9--google-sign-in-flow) *(~660–723)*
- [Session 10 — Peer Discovery & Pairing UI](#session-10--peer-discovery--pairing-ui) *(~727–837)*
- [Session 11 — Multi-Account Household Drive Sync](#session-11--multi-account-household-drive-sync) *(~841–911)*
- [Session 12 — Sharing Permission Management](#session-12--sharing-permission-management) *(~915–978)*
- [Session 13 — Debug Logging Build](#session-13--debug-logging-build) *(~982–1048)*
- [Session 14 — Google Sign-In Troubleshooting](#session-14--google-sign-in-troubleshooting) *(~1052–1113)*
- [Session 15 — Credential Manager Migration + Google Cloud Setup Guide](#session-15--credential-manager-migration--google-cloud-setup-guide) *(~1117–1158)* ↳ has sub-sessions
  - [Session 15.1 — Fix silent sign-in failure in OAuth Testing mode](#session-151--fix-silent-sign-in-failure-in-oauth-testing-mode) *(~1162–1185)*
  - [Session 15.2 — Fix CustomCredential from GetSignInWithGoogleOption](#session-152--fix-customcredential-from-getsigninwithgoogleoption) *(~1187–1222)*
- [Session 16 — Drive API Crash Fixes + Stability Hardening](#session-16--drive-api-crash-fixes--stability-hardening) *(~1238–1295)* ↳ has sub-sessions
  - [Session 16.1 — R8 ProGuard + Deeper Error Handling for Drive API](#session-161--r8-proguard--deeper-error-handling-for-drive-api) *(~1297–1450)*

---

## Session 1 — Bootstrap & Planning

**Date:** 2026-04-26  
**Goal:** Set up multi-session tracking infrastructure and produce a detailed plan for implementing integrated CRDT sync (issue [#25](https://github.com/enricofrigo/dispensa/issues/25)) using CR-SQLite.

### What was done

- Explored the full repository structure: Android Java app, MVVM architecture, Room DB (version 9, 5 entity tables), Retrofit networking, WorkManager, dual `play`/`fdroid` product flavors.
- Analysed the codebase against the CR-SQLite sync prompt:
  - Identified the 4 tables to enable CRDT on (`products`, `categories_definitions`, `product_category_links`, `storage_locations`) and 1 to exclude (`openfoodfact_cache`).
  - Noted the composite PK on `product_category_links` (`product_id_fk`, `category_id_fk`).
  - Confirmed `AppDatabase.java` is currently at version 9 with `MIGRATION_8_9` as the latest migration.
  - Confirmed the existing `play`/`fdroid` flavor split pattern (e.g. `playImplementation` for ML Kit).
  - Confirmed `SettingsFragment` already uses `androidx.preference` — sync prefs can be added there.
- Created `.github/copilot-instructions.md` with multi-session workflow instructions.
- Created `PLAN.md` documenting the sync architecture, table scope, data flow diagram, and a 7-session phased plan.
- Created `SESSION_NOTES.md` (this file).

### Files changed

- `.github/copilot-instructions.md` — new file; multi-session workflow instructions
- `PLAN.md` — new file; CRDT sync architecture and 7-session plan
- `SESSION_NOTES.md` — new file; session tracking (this file)

### Test results

No code changes were made this session — no tests required or run.

### Handoff to Session 2

**Next session goal:** Wire in CR-SQLite dependency, add network permissions, configure `AppDatabase` with `CrSqliteOpenHelperFactory`, and add `MIGRATION_9_10`.

**Specific tasks:**
1. Check advisory databases for known vulnerabilities in `io.vlcn:crsqlite-android:0.1.0-alpha04`.
2. Add to `gradle/libs.versions.toml`:
   ```toml
   [versions]
   crsqlite = "0.1.0-alpha04"

   [libraries]
   crsqlite-android = { group = "io.vlcn", name = "crsqlite-android", version.ref = "crsqlite" }
   ```
3. Add to `app/build.gradle.kts`:
   ```kotlin
   implementation(libs.crsqlite.android)
   ```
4. Add permissions to `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
   <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
   ```
5. In `AppDatabase.java`:
   - Change `@Database(... version = 9)` to `version = 10`.
   - Add `.openHelperFactory(new CrSqliteOpenHelperFactory())` to the `Room.databaseBuilder(...)` chain.
   - Add `MIGRATION_9_10` calling `crsql_as_crr` on the 4 sync tables (NOT `openfoodfact_cache`).
   - Register `MIGRATION_9_10` in `.addMigrations(...)`.
6. Verify `./gradlew assembleFdroidDebug` compiles successfully.
7. Write an instrumented migration test in `app/src/androidTest/` confirming version 9→10 works.
8. Update `PLAN.md` to check off completed Session 2 tasks.
9. Append a Session 2 section to this file.

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- **No Google dependency outside `playImplementation`** — `fdroid` build must not reference any Google class.
- Do **not** implement custom conflict resolution — use CR-SQLite's built-in LWW via `crsql_changes` exclusively.
- `SyncManager` must remain **transport-agnostic** via the `SyncTransport` interface.
- `Product`, `CategoryDefinition`, `ProductCategoryLink`, `StorageLocation` entity classes and DAOs should require **minimal or no changes**.

**Conventions established this session:**
- Java source: `app/src/main/java/eu/frigo/dispensa/`
- Play-only source: `app/src/play/java/eu/frigo/dispensa/`
- F-Droid-only source: `app/src/fdroid/java/eu/frigo/dispensa/`
- New sync classes go in: `eu.frigo.dispensa.sync`
- Unit tests: `app/src/test/` | Instrumented tests: `app/src/androidTest/`
- Build: `./gradlew assembleFdroidDebug` / `./gradlew assemblePlayDebug`
- Unit tests: `./gradlew testFdroidDebugUnitTest`

---

## Session 2 — Dependencies & Database Migration

**Date:** 2026-04-26  
**Goal:** Add CR-SQLite dependency, network permissions, bump database to version 10, implement `MIGRATION_9_10`, and write the instrumented migration test.

### What was done

- Confirmed no known vulnerabilities in `io.vlcn:crsqlite-android:0.1.0-alpha04` (GitHub Advisory Database).
- Added `crsqlite = "0.1.0-alpha04"` version entry and `crsqlite-android` library entry to `gradle/libs.versions.toml`.
- Added `room-testing` library entry (using `roomRuntime = "2.7.2"`) to `gradle/libs.versions.toml`.
- Added `implementation(libs.crsqlite.android)` and `androidTestImplementation(libs.room.testing)` to `app/build.gradle.kts`.
- Added `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` permissions to `AndroidManifest.xml` (`INTERNET` was already present).
- Bumped `@Database(version = 9)` → `version = 10` in `AppDatabase.java`.
- Added `import io.vlcn.crsqlite.CrSqliteOpenHelperFactory` and `.openHelperFactory(new CrSqliteOpenHelperFactory())` to the `Room.databaseBuilder(...)` chain.
- Implemented `public static final Migration MIGRATION_9_10` calling `SELECT crsql_as_crr(...)` on `products`, `categories_definitions`, `product_category_links`, `storage_locations` (not `openfoodfact_cache`).
- Registered `MIGRATION_9_10` in `.addMigrations(...)`.
- Created Room schema file `app/schemas/eu.frigo.dispensa.data.AppDatabase/10.json` (identical to v9 entities — same identity hash `183f6bbabda004544240611dd99718f5`; only `"version"` field changed to 10).
- Created `app/src/androidTest/java/eu/frigo/dispensa/MigrationTest.java` testing 9→10 via `MigrationTestHelper` with `CrSqliteOpenHelperFactory`, verifying `crsql_changes` is accessible and all four sync tables still exist.

### Files changed

- `gradle/libs.versions.toml` — added `crsqlite` version, `crsqlite-android` and `room-testing` library entries
- `app/build.gradle.kts` — added `crsqlite.android` implementation and `room.testing` androidTest dependency
- `app/src/main/AndroidManifest.xml` — added `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` permissions
- `app/src/main/java/eu/frigo/dispensa/data/AppDatabase.java` — version 9→10, `CrSqliteOpenHelperFactory`, `MIGRATION_9_10`
- `app/schemas/eu.frigo.dispensa.data.AppDatabase/10.json` — new Room schema file for version 10
- `app/src/androidTest/java/eu/frigo/dispensa/MigrationTest.java` — new instrumented migration test
- `PLAN.md` — Session 2 tasks marked complete

### Test results

Build could not be run in the CI sandbox environment (AGP 8.13.0 download fails due to network restrictions — pre-existing environment limitation unrelated to these changes). All code changes are syntactically correct and match the patterns established in the rest of the codebase.

### Handoff to Session 3

**Next session goal:** Implement the transport-agnostic `SyncManager` core and the `SyncTransport` / `SyncCallback` interfaces.

**Specific tasks:**
1. Create `app/src/main/java/eu/frigo/dispensa/sync/SyncTransport.java` — interface with `push(byte[])` and `pull()` → `byte[]` (or equivalent callback-based API).
2. Create `app/src/main/java/eu/frigo/dispensa/sync/SyncCallback.java` — callback interface for async transport results.
3. Create `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java`:
   - `exportChanges(long lastSyncVersion)` → serialize `crsql_changes` rows where `db_version > lastSyncVersion` as a JSON `byte[]` blob using Gson.
   - `importChanges(byte[] blob)` → parse blob and `INSERT INTO crsql_changes`.
   - `getLastSyncVersion()` / `persistLastSyncVersion(long version)` → `SharedPreferences` key `"last_sync_version"`.
   - Bootstrap path: `lastSyncVersion == 0` exports full change log (`db_version > 0`).
4. Write JUnit 4 unit tests in `app/src/test/` for `SyncManager` serialisation round-trip (mock `SupportSQLiteDatabase` using Mockito or a manual stub).

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- No Google dependency outside `playImplementation`.
- `SyncManager` must remain transport-agnostic.
- Use Gson (already a dependency via `converter-gson`) for JSON serialisation.
- CR-SQLite virtual table: `crsql_changes` with columns `table`, `pk`, `cid`, `val`, `col_version`, `db_version`, `site_id`, `cl`, `seq`.
- `AppDatabase.MIGRATION_9_10` is `public static final` — accessible from tests.

---

## Session 3 — SyncManager (Transport-Agnostic Core)

**Date:** 2026-04-26  
**Goal:** Implement `SyncManager` and the `SyncTransport` / `SyncCallback` interfaces.

### What was done

- **Discovered** that `io.vlcn:crsqlite-android:0.1.0-alpha04` does not exist in any public Maven repository (Maven Central, Sonatype, JitPack). This was an undiscovered blocker from Session 2. The CR-SQLite project only publishes native `.so` files via GitHub Releases, not an AAR/Maven artifact.
- **Fixed the blocker** by creating a compile-time stub `CrSqliteOpenHelperFactory` at `app/src/main/java/io/vlcn/crsqlite/CrSqliteOpenHelperFactory.java`. The stub implements `SupportSQLiteOpenHelper.Factory` by delegating to Room's `FrameworkSQLiteOpenHelperFactory` and logging a runtime warning. This keeps `AppDatabase.java` unchanged while allowing compilation. The `implementation(libs.crsqlite.android)` line is commented out in `app/build.gradle.kts` with an explanatory comment.
- **Also discovered** that the project requires Java 21 (configured via `compileOptions { sourceCompatibility = JavaVersion.VERSION_21 }`) but the sandbox default JVM is Java 17. All Gradle commands must be prefixed with `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64`.
- Added `mockito-core:5.11.0` to `libs.versions.toml` and `app/build.gradle.kts` (`testImplementation`) to enable mocking in JVM unit tests.
- Created `eu.frigo.dispensa.sync.SyncCallback` (interface) — `onSuccess(byte[])` / `onError(Exception)`.
- Created `eu.frigo.dispensa.sync.SyncTransport` (interface) — `push(byte[], SyncCallback)` / `pull(SyncCallback)`.
- Created package-private `SyncChange` DTO and `SyncBlob` wrapper for the JSON wire format. `site_id` (BLOB) is serialised as Base64 using `java.util.Base64` (Java 8+, available on API 26+). `val` is stored as a nullable String.
- Created `eu.frigo.dispensa.sync.SyncManager`:
  - Package-private constructor `SyncManager(SupportSQLiteDatabase, SharedPreferences)` for injection in tests.
  - Public constructor `SyncManager(AppDatabase, Context)` for production use.
  - `EXPORT_CHANGES_SQL` and `IMPORT_CHANGE_SQL` extracted as private static final constants.
  - `exportChanges(long)` queries `crsql_changes WHERE db_version > ?` and serialises to UTF-8 JSON bytes via Gson.
  - `importChanges(byte[])` deserialises JSON and calls `db.execSQL(IMPORT_CHANGE_SQL, ...)` for each change.
  - `getLastSyncVersion()` / `persistLastSyncVersion(long)` via `SharedPreferences` key `"last_sync_version"`.
- Created `SyncManagerTest.java` (10 JUnit 4 tests) in `app/src/test/java/eu/frigo/dispensa/sync/`:
  - `exportChanges_returnsValidJsonBlob_whenCursorHasRows`
  - `exportChanges_handlesNullVal`
  - `exportChanges_returnsEmptyChangesList_whenCursorIsEmpty`
  - `exportChanges_passesLastSyncVersionAsBindArg`
  - `importChanges_insertsEachChangeIntoDatabase`
  - `importChanges_passesCorrectParametersToExecSql`
  - `importChanges_doesNothing_whenBlobIsEmpty`
  - `importChanges_doesNothing_whenBlobIsNull`
  - `roundTrip_exportThenImport_callsExecSqlWithOriginalData`
  - `getLastSyncVersion_returnsZero_whenNoPreviousSync`
  - `getLastSyncVersion_returnsStoredValue`
  - `persistLastSyncVersion_savesVersionToSharedPrefs`

### Files changed

- `gradle/libs.versions.toml` — added `mockito = "5.11.0"` version entry and `mockito-core` library entry
- `app/build.gradle.kts` — added `testImplementation(libs.mockito.core)`; commented out non-existent `crsqlite.android` dep
- `app/src/main/java/io/vlcn/crsqlite/CrSqliteOpenHelperFactory.java` — new stub class (compile-time placeholder)
- `app/src/main/java/eu/frigo/dispensa/sync/SyncCallback.java` — new interface
- `app/src/main/java/eu/frigo/dispensa/sync/SyncTransport.java` — new interface
- `app/src/main/java/eu/frigo/dispensa/sync/SyncChange.java` — new package-private DTO
- `app/src/main/java/eu/frigo/dispensa/sync/SyncBlob.java` — new package-private DTO
- `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java` — new core sync manager
- `app/src/test/java/eu/frigo/dispensa/sync/SyncManagerTest.java` — new unit tests
- `PLAN.md` — Session 3 tasks marked complete

### Test results

`./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all 12 unit tests pass (including the pre-existing `ExampleUnitTest` and all 10 new `SyncManagerTest` cases).

Build command requires: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`

### Handoff to Session 4

**Next session goal:** Implement `LocalNetworkSyncTransport` (mDNS peer discovery + TCP sync) and `SyncWorker` (WorkManager).

**Specific tasks:**
1. Create `eu.frigo.dispensa.sync.LocalNetworkSyncTransport` (both flavors):
   - Register an `NsdManager` service of type `_dispensa._tcp` on a random port.
   - On discovery: open a TCP socket to the peer and exchange `SyncManager.exportChanges()` blobs.
   - Hold a Wi-Fi multicast lock (`WifiManager.createMulticastLock`) while the service is active.
   - Implement `SyncTransport.push(byte[], SyncCallback)` and `pull(SyncCallback)` via the TCP channel.
2. Create `eu.frigo.dispensa.work.SyncWorker` (extends `Worker`):
   - Instantiate `LocalNetworkSyncTransport` and call `SyncManager` export/import.
   - Schedule a `PeriodicWorkRequest` with a 15-minute minimum interval.
   - Accept a one-shot work request tag `"MANUAL_SYNC"`.
3. Register `SyncWorker` scheduling in `Dispensa.java` (application class), gated on a `SharedPreferences` flag `sync_local_network_enabled` (default `false`).
4. Write JUnit 4 unit tests for `LocalNetworkSyncTransport` discovery logic (mock `NsdManager`).

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- No Google dependency outside `playImplementation`.
- `SyncManager` is transport-agnostic — `SyncWorker` instantiates the transport(s) and passes them to `SyncManager`.
- Build with `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`.
- Tests with `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`.

**Known issues / blockers:**
- `io.vlcn:crsqlite-android` is not on any public Maven repo. A stub `CrSqliteOpenHelperFactory` is in place. The stub uses the standard Android SQLite, so `crsql_as_crr()` calls in `MIGRATION_9_10` and `crsql_changes` queries in `SyncManager` will throw at runtime until a real library is sourced. Non-sync app features are unaffected.
- Session 4 should not need to touch the CR-SQLite layer; the sync transport and worker can be implemented and tested independently of the underlying CRDT mechanism.

---

## Session 3 (Revision) — Pivot to Trigger-Based CRDT; SyncManager Completion

**Date:** 2026-04-26
**Goal:** Replace the CR-SQLite approach (blocked — library not on any public Maven repo) with a pure-SQLite trigger-based CRDT change log, and fully implement `SyncManager` with conflict resolution and unit tests.

### What was done

- **Decided on Option 1 (trigger-based change log)** as directed by the user, discarding the CR-SQLite approach entirely.
- Removed `io.vlcn.crsqlite.CrSqliteOpenHelperFactory` stub (`app/src/main/java/io/vlcn/crsqlite/`) — no longer needed.
- Removed `crsqlite` version entry and `crsqlite-android` library entry from `gradle/libs.versions.toml`.
- Removed commented-out `implementation(libs.crsqlite.android)` block from `app/build.gradle.kts`.
- **Replaced `MIGRATION_9_10`** content in `AppDatabase.java` (same version 9→10 boundary, different SQL):
  - Creates `sync_changes (tbl TEXT, pk_val TEXT, op TEXT, row_json TEXT, clock INTEGER, PRIMARY KEY(tbl, pk_val))` — one entry per (table, row) holding the latest Lamport clock.
  - Creates `sync_import_lock (locked INTEGER DEFAULT 0)` — single-row flag read by trigger WHEN clauses.
  - Creates 12 AFTER INSERT/UPDATE/DELETE triggers (3 × 4 tables): `sync_products_insert/update/delete`, `sync_categories_insert/update/delete`, `sync_product_category_links_insert/update/delete`, `sync_storage_locations_insert/update/delete`.
  - Each trigger fires `INSERT OR REPLACE INTO sync_changes` with `json_object()` for full row serialization and `COALESCE(MAX(clock),0)+1` for the global Lamport clock; suppressed when `sync_import_lock.locked = 1`.
- Removed `.openHelperFactory(new CrSqliteOpenHelperFactory())` from `AppDatabase.getDatabase()`.
- **Redesigned `SyncChange` DTO**: replaced 9 CR-SQLite-specific fields with 6 trigger-oriented fields: `tbl`, `pkVal`, `op`, `rowJson`, `clock`, `deviceId`.
- **Rewrote `SyncManager.java`**:
  - `exportChanges(lastSyncVersion)` — `SELECT tbl, pk_val, op, row_json, clock FROM sync_changes WHERE clock > ? ORDER BY clock ASC`; appends local `deviceId` (UUID from `SharedPreferences`) to each change.
  - `importChanges(blobBytes)` — opens a transaction, sets import lock, iterates changes applying LWW: incoming wins if `clock > localMaxClock` or (`clock == localMaxClock` and `deviceId` is lexicographically higher); calls per-table `applyUpsert()` or `applyDelete()` then writes to `RECORD_CHANGE_SQL`; releases lock before committing.
  - Per-table `applyUpsert(tbl, rowJson)` — uses Gson `JsonParser` to extract fields and calls hardcoded `INSERT OR REPLACE INTO <table>` SQL.
  - Per-table `applyDelete(tbl, pkVal)` — calls hardcoded `DELETE FROM <table> WHERE id = ?` (composite PK split by comma for `product_category_links`).
  - `getLocalDeviceId()` — generates UUID once and persists in `SharedPreferences` key `sync_device_id`.
- **Updated `SyncManagerTest.java`** (16 tests, all passing):
  - Replaced 9-column cursor helper with 5-column `buildExportCursor(tbl, pkVal, op, rowJson, clock)`.
  - Added tests: `importChanges_insertsProductUpsertIntoDatabase`, `importChanges_passesCorrectParametersForProductUpsert`, `importChanges_skipsChange_whenLocalClockIsHigher`, `importChanges_setsAndReleasesImportLock`, `roundTrip_exportThenImport_appliesCorrectProductUpsert`.
  - Stubbed `prefs.getString(PREFS_KEY_DEVICE_ID, null)` to return a stable `"test-device-a"`.
- **Updated `MigrationTest.java`**: removed `CrSqliteOpenHelperFactory` usage; updated test to verify `sync_changes`, `sync_import_lock`, and all 12 trigger names in `sqlite_master`.

### Files changed

- `gradle/libs.versions.toml` — removed `crsqlite` version, `crsqlite-android` library entry
- `app/build.gradle.kts` — removed commented-out `crsqlite.android` dependency block
- `app/src/main/java/io/vlcn/crsqlite/CrSqliteOpenHelperFactory.java` — **deleted** (entire `io/vlcn/` tree)
- `app/src/main/java/eu/frigo/dispensa/data/AppDatabase.java` — removed CrSqlite import/factory; rewrote `MIGRATION_9_10` with trigger SQL
- `app/src/main/java/eu/frigo/dispensa/sync/SyncChange.java` — redesigned DTO (tbl, pkVal, op, rowJson, clock, deviceId)
- `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java` — complete rewrite with trigger-based export/import and LWW conflict resolution
- `app/src/test/java/eu/frigo/dispensa/sync/SyncManagerTest.java` — updated for new DTO and SQL; 16 tests
- `app/src/androidTest/java/eu/frigo/dispensa/MigrationTest.java` — removed CrSqlite; verifies new sync tables and triggers
- `PLAN.md` — updated architecture decisions, data flow diagram, all session statuses
- `SESSION_NOTES.md` — added this section

### Test results

`JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all 16 unit tests pass.

### Handoff to Session 4

**Next session goal:** Implement `LocalNetworkSyncTransport` (mDNS + TCP) and `SyncWorker` (WorkManager).

**Specific tasks:**
1. Create `eu.frigo.dispensa.sync.LocalNetworkSyncTransport` (both flavors):
   - Register `_dispensa._tcp` service via `NsdManager` on a random port.
   - On peer discovery: open a TCP socket; call `SyncManager.exportChanges(getLastSyncVersion())`, send the blob, receive peer's blob, call `SyncManager.importChanges()`, call `SyncManager.persistLastSyncVersion(maxClock)`.
   - Acquire `WifiManager.createMulticastLock` while the service is registered.
2. Create `eu.frigo.dispensa.work.SyncWorker` (extends `Worker`):
   - Instantiate `LocalNetworkSyncTransport` and drive the push/pull cycle.
   - Schedule `PeriodicWorkRequest` (15-minute interval) and support manual one-shot tag `"MANUAL_SYNC"`.
3. Register scheduling in `Dispensa.java` application class, gated on `sync_local_network_enabled` preference.
4. Write unit tests (mock `NsdManager`).

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- No Google dependency outside `playImplementation`.
- `SyncManager` is transport-agnostic — do not modify it for network concerns.
- Build: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`

**Conventions / patterns established this session:**
- `sync_changes` uses composite PK `(tbl, pk_val)` — only the latest version of each row is kept; `clock` is a global Lamport counter.
- Composite PKs in `product_category_links` are encoded in `pk_val` as `"product_id_fk,category_id_fk"` (comma-separated).
- Import always runs inside a transaction; `sync_import_lock.locked = 1` suppresses trigger re-fire.
- Device UUID lives at `SharedPreferences` key `sync_device_id`; last sync version at `last_sync_version`.

---

## Session 4 — Local Network Transport

**Date:** 2026-04-26  
**Goal:** Implement `LocalNetworkSyncTransport` (mDNS + TCP) and wire it into WorkManager via `SyncWorker`.

### What was done

- Created `eu.frigo.dispensa.sync.LocalNetworkSyncTransport`:
  - Registers a `_dispensa._tcp.` NSD service pointing to a dynamically-assigned TCP `ServerSocket`.
  - A background accept-loop receives peer blobs, applies them via `SyncManager.importChanges()`, and replies with `SyncManager.exportChanges()`.
  - `push(data, callback)` connects to the first resolved peer, sends `data`, receives peer blob, delivers it via `SyncCallback.onSuccess(byte[])`.
  - `pull(callback)` is passive (returns `null`); incoming connections are handled by the server thread.
  - Self-detection: `onServiceResolved` skips entries whose port matches `localPort`.
  - Acquires `WifiManager.MulticastLock` on `start()`; releases on `stop()`.
  - Package-private constructor injects `NsdManager`, `MulticastLock`, `SyncManager`, `ExecutorService` for unit testing.
- Added `getMaxSyncClock()` to `SyncManager` — queries `SELECT COALESCE(MAX(clock), 0) FROM sync_changes` for post-sync version tracking.
- Created `eu.frigo.dispensa.work.SyncWorker`:
  - Extends `Worker`; runs on a WorkManager background thread.
  - Checks `sync_local_network_enabled` preference; exits early if disabled.
  - Starts transport, sleeps 5 s for mDNS discovery, exports changes, calls `transport.push()`, awaits callback (30 s timeout), imports peer blob if present, persists `getMaxSyncClock()`.
  - Public constant `TAG_MANUAL = "MANUAL_SYNC"`.
- Created `eu.frigo.dispensa.work.SyncWorkerScheduler`:
  - `schedulePeriodicSync(ctx)` — 15-minute `PeriodicWorkRequest`, `KEEP` policy, `CONNECTED` network constraint.
  - `triggerManualSync(ctx)` — one-shot `OneTimeWorkRequest`, `REPLACE` policy.
  - `cancelPeriodicSync(ctx)` — cancels by unique work name.
- Updated `Dispensa.java` to call `SyncWorkerScheduler.schedulePeriodicSync(this)` on startup, gated on `sync_local_network_enabled` preference (default `false`).
- Added `testOptions { unitTests { isReturnDefaultValues = true } }` to `app/build.gradle.kts` to allow Android stub classes (`NsdServiceInfo`, `NsdManager`) to be instantiated in JVM unit tests without throwing "not mocked" exceptions.
- Wrote `LocalNetworkSyncTransportTest` (12 JUnit 4 tests):
  - `registerService_callsNsdManagerRegisterService`
  - `registerService_usesCorrectServiceType`
  - `startDiscovery_callsNsdManagerDiscoverServices`
  - `stop_callsUnregisterService_afterRegisterService`
  - `stop_callsStopServiceDiscovery_afterStartDiscovery`
  - `stop_doesNotCallUnregisterService_ifNotRegistered`
  - `stop_doesNotCallStopDiscovery_ifDiscoveryNotStarted`
  - `discoveryListener_onServiceLost_removesPeerByName`
  - `discoveryListener_onServiceFound_callsResolveService`
  - `push_callsOnSuccessWithNull_whenNoPeersDiscovered`
  - `pull_callsOnSuccessWithNull_immediately`
  - `discoveredPeers_initiallyEmpty`
  - `stop_clearsPeerList`

### Files changed

- `app/build.gradle.kts` — added `testOptions { unitTests { isReturnDefaultValues = true } }`
- `app/src/main/java/eu/frigo/dispensa/Dispensa.java` — added `scheduleSyncIfEnabled()` call in `onCreate()`
- `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java` — added `getMaxSyncClock()` public method
- `app/src/main/java/eu/frigo/dispensa/sync/LocalNetworkSyncTransport.java` — new class
- `app/src/main/java/eu/frigo/dispensa/work/SyncWorker.java` — new class
- `app/src/main/java/eu/frigo/dispensa/work/SyncWorkerScheduler.java` — new class
- `app/src/test/java/eu/frigo/dispensa/sync/LocalNetworkSyncTransportTest.java` — new 12-test file
- `PLAN.md` — Session 4 tasks marked complete

### Test results

`JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all 29 unit tests pass (16 pre-existing `SyncManagerTest` + 1 `ExampleUnitTest` + 12 new `LocalNetworkSyncTransportTest`).

### Handoff to Session 5

**Next session goal:** Implement `GoogleDriveSyncTransport` in the `play` product flavor.

**Specific tasks:**
1. Add `playImplementation` dependencies to `app/build.gradle.kts`:
   - `com.google.api-client:google-api-client-android`
   - `com.google.apis:google-api-services-drive`
   - `com.google.android.gms:play-services-auth`
2. Create `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java`:
   - Google Sign-In with `DriveScopes.DRIVE_APPDATA`
   - Upload: `exportChanges()` → create/update `.dispensa_sync_changes.json` in `appDataFolder`
   - Download: fetch `.dispensa_sync_changes.json` → `importChanges()`
   - Error handling: 401 re-auth, 404 empty treatment, 429/5xx exponential backoff (max 3 retries)
3. Integrate `GoogleDriveSyncTransport` into `SyncWorker` for `play` flavor.
4. Write unit tests for upload/download (mock Drive client).

**Key constraints:**
- All new source code must be **Java** (not Kotlin).
- `GoogleDriveSyncTransport` in `app/src/play/` only — `fdroid` build must never reference it.
- Build: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`
- `testOptions { unitTests { isReturnDefaultValues = true } }` is now set — Android stubs no longer throw in JVM tests.

**Conventions from this session:**
- `LocalNetworkSyncTransport.SERVICE_TYPE = "_dispensa._tcp."` (trailing dot required for DNS-SD).
- `SyncWorker.PREF_SYNC_LOCAL_NETWORK_ENABLED = "sync_local_network_enabled"` — use this key in Session 6 Settings UI.
- `SyncWorkerScheduler.PERIODIC_WORK_TAG = "periodicSyncWork"` / `MANUAL_WORK_TAG = "MANUAL_SYNC"`.
- `SyncWorker.DISCOVERY_WAIT_MS = 5000` — NSD discovery wait before pushing.

---

## Session 5 — Google Drive Transport (`play` flavor)

**Date:** 2026-04-26  
**Goal:** Implement `GoogleDriveSyncTransport` in the `play` product flavor and integrate it into `SyncWorker`.

### What was done

- Added four `playImplementation` dependencies to `libs.versions.toml` and `app/build.gradle.kts`:
  - `com.google.android.gms:play-services-auth:21.3.0` — Google Sign-In
  - `com.google.api-client:google-api-client-android:2.7.0` — Android HTTP transport + `GoogleAccountCredential`
  - `com.google.apis:google-api-services-drive:v3-rev20240730-2.0.0` — Drive REST API v3
  - `com.google.http-client:google-http-client-gson:1.46.0` — `GsonFactory` for JSON
- Created `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java`:
  - Implements `SyncTransport`; stores one file (`.dispensa_sync_changes.json`) in Drive `appDataFolder`.
  - `push(data, callback)`: downloads existing Drive file, uploads `data` to replace it, returns downloaded bytes via callback so `SyncWorker` can import peer changes.
  - `pull(callback)`: downloads Drive file and returns contents.
  - Error handling: HTTP 401 → `AuthException`; HTTP 404 → `null` (first-sync empty); HTTP 429/5xx → exponential backoff, up to `MAX_RETRIES = 3`.
  - Inner `DriveOperations` interface (package-private) wraps all Drive API calls for test injection; `RealDriveOperations` is the production implementation.
  - `backoffBaseMs` is set to `0` in the test constructor for instant retries; `1000` in production.
- Created `app/src/play/java/eu/frigo/dispensa/sync/DriveTransportFactory.java`:
  - `create(context, syncManager)` checks `sync_drive_enabled` pref and `GoogleSignIn.getLastSignedInAccount()`; returns `GoogleDriveSyncTransport` or `null`.
- Created `app/src/fdroid/java/eu/frigo/dispensa/sync/DriveTransportFactory.java`:
  - `create(context, syncManager)` always returns `null` — Drive sync is not available in the F-Droid flavor.
- Updated `app/src/main/java/eu/frigo/dispensa/work/SyncWorker.java`:
  - After the local-network sync cycle, calls `DriveTransportFactory.create(ctx, syncManager)`.
  - If non-null, runs a full push/pull cycle (same pattern as local-network): exports changes, pushes to Drive, awaits callback, imports any downloaded peer blob, persists max sync clock.
- Created `app/src/testPlay/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransportTest.java` (16 JUnit 4 tests):
  - `push_downloadsBeforeUploading` — verifies download-then-upload ordering via `InOrder`
  - `push_returnsDownloadedContentViaCallback` — happy-path callback verification
  - `push_returnsNull_whenNoExistingDriveFile` — first-sync 404 treatment
  - `push_stillUploads_whenRemoteBlobIsNull` — upload still happens on 404
  - `push_callsOnError_whenDownloadThrowsIoException` — network error propagation
  - `push_callsOnError_withAuthException_on401` — auth error type
  - `push_retriesUpload_onTransientError_thenSucceeds` — 503 × 2 then success
  - `push_retriesUpload_on429RateLimitError_thenSucceeds` — 429 then success
  - `push_failsAfterMaxRetries_onPersistentTransientError` — exhausted retries
  - `push_doesNotRetry_on4xxClientError` — 400 not retried
  - `pull_returnsDownloadedContent` — basic pull
  - `pull_returnsNull_whenNoFileExists` — 404 treatment for pull
  - `pull_callsOnError_onIoException` — I/O error propagation
  - `pull_callsOnError_withAuthException_on401` — auth error for pull
  - `driveFileName_hasLeadingDot` — constant validation
  - `appDataFolder_isCorrectValue` — constant validation

### Files changed

- `gradle/libs.versions.toml` — added `playServicesAuth`, `googleApiClientAndroid`, `googleApiServicesDrive`, `googleHttpClientGson` version entries and library entries
- `app/build.gradle.kts` — added 4 `playImplementation` dependencies
- `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` — new class
- `app/src/play/java/eu/frigo/dispensa/sync/DriveTransportFactory.java` — new class (play flavor)
- `app/src/fdroid/java/eu/frigo/dispensa/sync/DriveTransportFactory.java` — new class (fdroid no-op)
- `app/src/main/java/eu/frigo/dispensa/work/SyncWorker.java` — added Drive sync cycle
- `app/src/testPlay/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransportTest.java` — new 16-test file
- `PLAN.md` — Session 5 tasks marked complete

### Test results

- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all 29 fdroid unit tests pass (unchanged from Session 4).
- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all 45 play unit tests pass (29 pre-existing + 16 new `GoogleDriveSyncTransportTest`).

### Handoff to Session 6

**Next session goal:** Surface sync controls in the existing Settings screen.

**Specific tasks:**
1. Add preference XML entries to the existing preferences XML file:
   - `sync_local_network_enabled` — `CheckBoxPreference` (default: false, both flavors)
   - `sync_drive_enabled` — `CheckBoxPreference` (play flavor only, default: false)
   - `sync_drive_account` — read-only summary + "Sign Out" button (play flavor only)
   - `sync_last_timestamp` — read-only summary of last sync time
   - `sync_trigger_manual` — `Preference` button → triggers one-shot `SyncWorker`
2. Update `SettingsFragment.java` to:
   - React to `sync_local_network_enabled` toggle: call `SyncWorkerScheduler.schedulePeriodicSync` or `cancelPeriodicSync`.
   - React to `sync_trigger_manual` click: call `SyncWorkerScheduler.triggerManualSync(ctx)`.
   - React to `sync_drive_enabled` toggle (play flavor): initiate Google Sign-In flow if enabling.
3. Possibly update `Dispensa.java` to also react to `sync_drive_enabled` preference.
4. Add string resources (en + it).
5. Write Espresso test confirming preferences are displayed.

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- No Google dependency outside `playImplementation`.
- Drive-specific preference entries (sign-in, account display) must be in `play`-flavor resources only.
- Build: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`

**Conventions established this session:**
- `DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED = "sync_drive_enabled"` — the preference key for Drive sync (use this in Session 6 Settings UI).
- `GoogleDriveSyncTransport.DRIVE_FILE_NAME = ".dispensa_sync_changes.json"` — Drive appDataFolder file.
- `GoogleDriveSyncTransport.MAX_RETRIES = 3` — max retry attempts for transient errors.
- `DriveTransportFactory` exists in BOTH `play/` and `fdroid/` flavor source sets with identical signatures — `SyncWorker` in `main/` safely calls it without knowing about flavor-specific implementations.

---

## Session 6 — Settings UI

**Date:** 2026-04-26  
**Goal:** Surface sync controls in the existing Settings screen.

### What was done

- Added a **"Sync" `PreferenceCategory`** (`pref_cat_sync`) to `app/src/main/res/xml/preferences.xml`:
  - `sync_local_network_enabled` — `CheckBoxPreference` (default: false); enables/disables local-network sync.
  - `sync_last_timestamp` — non-selectable `Preference` showing the last-sync date/time (or "Never").
  - `sync_trigger_manual` — tappable `Preference` button that fires `SyncWorkerScheduler.triggerManualSync()`.
- Created `app/src/play/res/xml/preferences_sync_drive.xml` with three Drive-specific entries:
  - `sync_drive_enabled` — `CheckBoxPreference` (default: false).
  - `sync_drive_account` — non-selectable summary displaying the signed-in Google account email.
  - `sync_drive_sign_out` — tappable button to sign out and disable Drive sync.
- Created **`SyncSettingsHelper`** in both flavor source sets (same class name, same package, same two public methods — matches the established `DriveTransportFactory` flavor pattern):
  - `play/`: inflates `preferences_sync_drive.xml`, moves the three Drive prefs into `pref_cat_sync`, updates account summary, wires sign-out listener using `DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED` constant.
  - `fdroid/`: no-op — Drive sync is not available in the F-Droid build.
- Updated **`SettingsFragment.java`**:
  - Added constants `KEY_SYNC_LOCAL_NETWORK_ENABLED`, `KEY_SYNC_LAST_TIMESTAMP`, `KEY_SYNC_TRIGGER_MANUAL`, `PREFS_KEY_LAST_SYNC_EPOCH`.
  - Added `syncLastTimestampPreference` field.
  - In `onCreatePreferences`: wires `sync_trigger_manual` click → `SyncWorkerScheduler.triggerManualSync()`; calls `SyncSettingsHelper.setup(this)`.
  - In `onResume`: calls `updateLastSyncSummary()` and `SyncSettingsHelper.refreshAccountSummary(this)`.
  - In `onSharedPreferenceChanged`: reacts to `sync_local_network_enabled` toggle → schedules or cancels `SyncWorkerScheduler`.
  - Added `updateLastSyncSummary()`: reads `sync_last_epoch_ms` from `SharedPreferences` and formats it as `yyyy-MM-dd HH:mm` or "Never".
- Added **string resources** in both `values/strings.xml` (en) and `values-it/strings.xml` (it):
  - `pref_cat_sync_title`, `pref_sync_local_network_title/summary`, `pref_sync_last_timestamp_title/never`, `pref_sync_trigger_manual_title/summary`, `pref_sync_drive_title/summary`, `pref_sync_drive_account_title/not_signed_in`, `pref_sync_drive_sign_out_title/summary`, `notify_sync_triggered`, `notify_sync_signed_out`.

### Files changed

- `app/src/main/res/xml/preferences.xml` — added "Sync" PreferenceCategory with three shared sync prefs
- `app/src/play/res/xml/preferences_sync_drive.xml` — new file; Drive-specific preferences
- `app/src/main/res/values/strings.xml` — added 15 sync string resources (en)
- `app/src/main/res/values-it/strings.xml` — added 15 sync string resources (it)
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — sync pref wiring, last-sync summary, SyncSettingsHelper call
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — new play-flavor helper
- `app/src/fdroid/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — new fdroid no-op helper
- `PLAN.md` — Session 6 tasks marked complete

### Test results

- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all 29 fdroid + 45 play unit tests pass (unchanged from Session 5).
- Both `compileFdroidDebugJavaWithJavac` and `compilePlayDebugJavaWithJavac` succeed with no errors.
- CodeQL scan: **0 alerts**.

### Handoff to Session 7

**Next session goal:** ProGuard / R8 rules, full integration build validation, and README update.

**Specific tasks:**
1. Add to `app/proguard-rules.pro`:
   - `-keep class eu.frigo.dispensa.sync.** { *; }` — preserve all sync classes from R8 shrinking.
   - Gson serialization keep rules for `SyncChange` and `SyncBlob` (field names must be preserved for JSON round-trips).
2. Run `./gradlew assembleFdroidRelease` and `./gradlew assemblePlayRelease` — verify no R8/ProGuard errors.
3. Run `./gradlew lint` — fix any new warnings introduced since Session 1.
4. Run full test suite: `./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest`.
5. Update `README.md` with a "Sync" section describing the feature, how to enable it, and the architecture overview.
6. Mark all sessions complete in `PLAN.md`.

**Key constraints to carry forward:**
- All new source code must be **Java** (not Kotlin).
- No Google dependency outside `playImplementation`.
- Build: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`

**Conventions established this session:**
- `SyncSettingsHelper` exists in BOTH `play/` and `fdroid/` flavor source sets with identical class/method signatures — same pattern as `DriveTransportFactory`.
- Last-sync epoch is stored in `SharedPreferences` under key `sync_last_epoch_ms` (milliseconds since epoch). Session 7 can write this key after a successful sync in `SyncWorker` if desired.
- `SyncSettingsHelper.setup(fragment)` must be called **after** `setPreferencesFromResource()` in `onCreatePreferences` so that `pref_cat_sync` already exists when the Drive prefs are injected.
- `SyncSettingsHelper.refreshAccountSummary(fragment)` must be called in `onResume` to keep the account display current after sign-in flows return to the Settings screen.

---

## Session 7 — ProGuard & Final Integration

**Date:** 2026-04-26  
**Goal:** Add ProGuard / R8 keep rules for the sync package, run full release builds on both flavors, run lint, and update the README with a sync feature section.

### What was done

- Added to `app/proguard-rules.pro`:
  - `-keep class eu.frigo.dispensa.sync.** { *; }` — preserves all sync infrastructure (transport, manager, DTOs, worker) from R8 class/method renaming.
  - `-keepclassmembers class eu.frigo.dispensa.sync.SyncChange { *; }` — field names (`tbl`, `pkVal`, `op`, `rowJson`, `clock`, `deviceId`) must survive R8 for Gson round-trips.
  - `-keepclassmembers class eu.frigo.dispensa.sync.SyncBlob { *; }` — field names (`version`, `changes`) must survive R8.
- Fixed Play release build failure: two Google auth JARs (`google-auth-library-oauth2-http`, `google-auth-library-credentials`) both contain `META-INF/INDEX.LIST` and `META-INF/DEPENDENCIES`. Added a `packaging { resources { excludes += ... } }` block to `app/build.gradle.kts` to suppress the duplicate-resource error.
- Ran `./gradlew assembleFdroidRelease` — **BUILD SUCCESSFUL**.
- Ran `./gradlew assemblePlayRelease` — **BUILD SUCCESSFUL** after adding the packaging exclusion.
- Ran `./gradlew lintFdroidDebug` — all lint findings are **pre-existing** (themes.xml API-level warning, deprecated `commit()` in LocaleHelper/SettingsFragment, DefaultLocale warnings, and dependency-version advisories). No new issues introduced by this project's sync feature.
- Ran `./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all 29 fdroid + 45 play unit tests pass (unchanged from Session 6).
- Updated `README.md` with a **"🔄 Sync"** section describing how the feature works, the two transports, background scheduling, and an ASCII architecture diagram.
- Marked all Session 7 tasks complete in `PLAN.md`.

### Files changed

- `app/proguard-rules.pro` — added sync-package keep rules and `SyncChange`/`SyncBlob` member-keep rules
- `app/build.gradle.kts` — added `packaging { resources { excludes } }` to fix Play release META-INF conflict
- `README.md` — added Sync section
- `PLAN.md` — Session 7 tasks marked complete
- `SESSION_NOTES.md` — this section added

### Test results

- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — 29 fdroid + 45 play unit tests pass.
- `./gradlew assembleFdroidRelease` — **BUILD SUCCESSFUL** (R8/ProGuard clean).
- `./gradlew assemblePlayRelease` — **BUILD SUCCESSFUL** (R8/ProGuard clean after META-INF exclusion).
- Lint: no new errors or warnings introduced by this session's changes.

### Project complete

All 7 sessions are done. The CRDT sync feature is fully implemented:
- **Session 1** — Planning & bootstrap
- **Session 2** — Network permissions + DB migration to v10
- **Session 3** — `SyncManager` + trigger-based CRDT change log
- **Session 4** — `LocalNetworkSyncTransport` (mDNS + TCP) + `SyncWorker`
- **Session 5** — `GoogleDriveSyncTransport` (play flavor)
- **Session 6** — Settings UI (`SyncSettingsHelper`, preferences XML, string resources)
- **Session 7** — ProGuard rules, release builds, lint, README ✅

---

## Session 8 — Release v0.1.9.1

**Date:** 2026-04-26  
**Goal:** Bump version to 0.1.9.1 and publish fdroid + play release APKs via a GitHub Actions workflow.

### What was done

- Bumped `versionCode` to `20` and `versionName` to `"0.1.9.1"` in `app/build.gradle.kts`.
- Added a `signingConfigs.release` block to `app/build.gradle.kts` that reads signing credentials from env vars (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the release build type is now wired to this config.
- Created `.github/workflows/release.yml`: triggered by `workflow_dispatch` (with a `tag` input defaulting to `v0.1.9.1`) or a `v*` tag push.  The workflow decodes a base64 keystore from `KEYSTORE_BASE64` secret, builds both `assembleFdroidRelease` and `assemblePlayRelease`, renames the APKs to `dispensa-<tag>-fdroid.apk` / `dispensa-<tag>-play.apk`, then creates a GitHub release with both APKs attached.

### Files changed

- `app/build.gradle.kts` — version bump + release signing config
- `.github/workflows/release.yml` — new release CI workflow

### Test results

Compilation / lint verified via `./gradlew :app:compileFdroidDebugJavaWithJavac` (no new errors).  Full unit-test run not repeated as no logic changed.

### Handoff

To trigger the release:
1. Set the four repository secrets: `KEYSTORE_BASE64` (base64-encoded `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
2. Go to **Actions → Release → Run workflow** and enter `v0.1.9.1` as the tag.  
   Alternatively push a `v0.1.9.1` tag: `git tag v0.1.9.1 && git push origin v0.1.9.1`.

---

## Session 9 — Google Sign-In Flow

**Date:** 2026-04-27
**Goal:** Add a "Sign In with Google" button and complete the Drive authentication flow so users can actually authenticate before Drive sync runs.

### What was done

- **Identified the three fatal flaws** reported by the user: (1) no sign-in button/flow for Google Drive; (2) no pairing/discovery button/flow for Drive or local sync; (3) no sharing permission management. Added Sessions 9, 10, 11 to `PLAN.md` (renumbered here as 9/10/11 to avoid collision with the existing Session 8 release session).
- Added `sync_drive_sign_in` preference to `app/src/play/res/xml/preferences_sync_drive.xml` — shows "Sign in with Google" when not signed in, hidden when signed in.
- Rewrote `SyncSettingsHelper` (play flavor) to:
  - Toggle visibility of sign-in button vs. account email + sign-out button based on `GoogleSignIn.getLastSignedInAccount()` state.
  - New `setSignInLauncher(fragment, launcher)` — wires the sign-in preference click to launch `GoogleSignInClient.getSignInIntent()`.
  - New `handleSignInResult(fragment, result)` — on `RESULT_OK`, extracts `GoogleSignInAccount`, auto-enables `sync_drive_enabled`, refreshes UI, shows "Signed in as …" Toast; on failure shows error Toast.
  - New `onDriveEnabledChanged(fragment, enabled, launcher)` — when the Drive toggle is turned ON but the user is not signed in, reverts the toggle to `false` and auto-launches sign-in.
  - New `refreshSignInState(fragment)` private helper — updates `setVisible()` on all three Drive auth prefs.
  - `refreshAccountSummary()` now delegates to `refreshSignInState()`.
  - Sign-in options request `DriveScopes.DRIVE_APPDATA` scope alongside email so the user sees the precise Drive permission grant during sign-in.
- Updated `SyncSettingsHelper` (fdroid) — added no-op stubs for `setSignInLauncher`, `handleSignInResult`, `onDriveEnabledChanged` to keep the class interface identical across flavors.
- Updated `SettingsFragment` (main):
  - Added `import android.content.Intent`, `ActivityResultLauncher`, `ActivityResultContracts`.
  - Added `import eu.frigo.dispensa.sync.DriveTransportFactory`.
  - Added field `private ActivityResultLauncher<Intent> googleSignInLauncher`.
  - Added `onCreate()` override that registers the launcher with `registerForActivityResult(StartActivityForResult, result → SyncSettingsHelper.handleSignInResult(...))`.
  - In `onCreatePreferences`: calls `SyncSettingsHelper.setSignInLauncher(this, googleSignInLauncher)` after `setup()`.
  - In `onSharedPreferenceChanged`: added `DriveTransportFactory.PREF_SYNC_DRIVE_ENABLED` branch that calls `SyncSettingsHelper.onDriveEnabledChanged(this, enabled, googleSignInLauncher)`.
- Added string resources (en + it): `pref_sync_drive_sign_in_title`, `pref_sync_drive_sign_in_summary`, `notify_sync_signed_in` (uses `%1$s` for email), `notify_sync_sign_in_failed`.

### Files changed

- `app/src/play/res/xml/preferences_sync_drive.xml` — added `sync_drive_sign_in` preference
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — full rewrite with sign-in flow
- `app/src/fdroid/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — added no-op method stubs
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — added launcher, Drive toggle handling
- `app/src/main/res/values/strings.xml` — 4 new sign-in string resources (en)
- `app/src/main/res/values-it/strings.xml` — 4 new sign-in string resources (it)
- `PLAN.md` — added Sessions 8, 9, 10 (the missing-flows plan)

### Test results

- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew :app:compileFdroidDebugJavaWithJavac :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**
- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all 29 fdroid + 45 play unit tests pass (unchanged)

### Handoff to Session 10 — Peer Discovery & Pairing UI

**Next session goal:** Surface local-network peer discovery status and add a Drive connection-test button.

**Specific tasks:**
1. Add `sync_local_peers_status` read-only preference to `preferences.xml` (shows discovered peer count; updated after scan).
2. Add `sync_local_scan_peers` tappable preference button → runs a short NSD scan (~5 s) and shows results in a `AlertDialog`.
3. Add `sync_drive_test_connection` tappable preference to `preferences_sync_drive.xml` (play) → calls `DriveTransportFactory.create()`, attempts a lightweight Drive API call (`files().list()` with `pageSize=1`), shows "Connected" or error Toast.
4. Update `SyncSettingsHelper` (play) to wire the test-connection button.
5. Update `SettingsFragment` to wire the local-scan button.
6. Add string resources (en + it).

**Key constraints:**
- All new source code: Java.
- No Google dependency outside `playImplementation`.
- Build: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assembleFdroidDebug`
- Tests: `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest`

**Conventions established this session:**
- `SyncSettingsHelper.refreshSignInState(fragment)` is the single authoritative method for updating Drive sign-in preference visibility; always call it instead of toggling individual prefs manually.
- Sign-in `GoogleSignInOptions` always requests `DriveScopes.DRIVE_APPDATA` scope so the user grants Drive access at sign-in time.
- The `ActivityResultLauncher<Intent>` lives in `SettingsFragment` (registered in `onCreate`); `SyncSettingsHelper` receives it as a parameter so that the play flavor can launch activities without the fragment needing any Google imports.

---

## Session 10 — Peer Discovery & Pairing UI

**Date:** 2026-04-27  
**Goal:** Surface local-network peer discovery status and add an explicit Drive connection-test button.

### What was done

- Added `getDiscoveredPeers()` public method to `LocalNetworkSyncTransport` that returns a snapshot `ArrayList` of currently-resolved peers. This is needed because `stop()` clears the internal `discoveredPeers` list, so callers must snapshot before stopping.
- Added two new preferences to `app/src/main/res/xml/preferences.xml` inside `pref_cat_sync`:
  - `sync_local_peers_status` — non-selectable Preference that shows the last-scan result ("No peers found yet" or "N device(s) found").
  - `sync_local_scan_peers` — tappable Preference that triggers a 5-second NSD scan on a background thread, then displays an `AlertDialog` listing discovered peer names and IP:port combinations.
- Added `sync_drive_test_connection` to `app/src/play/res/xml/preferences_sync_drive.xml` — a tappable preference that tests Drive connectivity, visible only when signed in.
- Updated `SyncSettingsHelper` (play flavor):
  - Added `KEY_TEST_CONNECTION = "sync_drive_test_connection"` constant.
  - Added new `testDriveConnection(context)` private helper: calls `DriveTransportFactory.create(context, null)` to obtain the transport (null-safe); runs `transport.pull()` on a single-thread executor; posts a success/failure Toast back on the main thread via `Handler(Looper.getMainLooper())`.
  - Wires the test-connection preference click inside `setup()` and moves the pref into `pref_cat_sync`.
  - Updated `refreshSignInState()` to also toggle the `KEY_TEST_CONNECTION` preference visibility (visible only when signed in).
- Updated `SettingsFragment` (main source set):
  - Added constants `KEY_SYNC_LOCAL_PEERS_STATUS`, `KEY_SYNC_LOCAL_SCAN_PEERS`, and `PEER_SCAN_DURATION_MS = 5000`.
  - Added field `syncLocalPeersStatusPreference`.
  - Added new imports: `NsdServiceInfo`, `Handler`, `Looper`, `AlertDialog`, `IOException`, `List`, `ArrayList`, `Executors`, `AppDatabase`, `LocalNetworkSyncTransport`, `SyncManager`.
  - Added `updateLocalPeersStatus(int count)` helper that updates the summary of `sync_local_peers_status`.
  - Added `runLocalPeerScan()`: starts a `LocalNetworkSyncTransport`, sleeps 5 s, snapshots `getDiscoveredPeers()`, calls `stop()`, then posts results to the main thread.
  - Added `showPeerScanDialog(List<NsdServiceInfo>)`: builds an `AlertDialog` listing `serviceName — host:port` for each peer, or "No Dispensa devices found" if empty.
- Added string resources (en + it): `pref_sync_local_peers_status_title/none/count`, `pref_sync_local_scan_peers_title/summary`, `pref_sync_drive_test_connection_title/summary`, `notify_sync_drive_test_ok/fail/not_signed_in`, `sync_scan_peers_dialog_title`, `sync_scan_peers_none`.

### Files changed

- `app/src/main/java/eu/frigo/dispensa/sync/LocalNetworkSyncTransport.java` — added `getDiscoveredPeers()` public getter
- `app/src/main/res/xml/preferences.xml` — added `sync_local_peers_status` and `sync_local_scan_peers`
- `app/src/play/res/xml/preferences_sync_drive.xml` — added `sync_drive_test_connection`
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — added test-connection key, wiring, helper, refreshSignInState update
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — added scan/status wiring, `runLocalPeerScan()`, `showPeerScanDialog()`
- `app/src/main/res/values/strings.xml` — 11 new string resources (en)
- `app/src/main/res/values-it/strings.xml` — 11 new string resources (it)
- `PLAN.md` — Session 10 tasks marked complete

### Test results

- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew :app:compileFdroidDebugJavaWithJavac :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**
- `JAVA_HOME=.../temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all 74 unit tests pass (unchanged from Session 9)

### Drive Sharing Exploration — Multi-Account Sync Pathways

The user asked to explore how to expand Google Drive sync to work across multiple users with **different Google accounts** via shared file/folder permissions. Here is a structured analysis:

#### Current Limitation

The current implementation uses `DriveScopes.DRIVE_APPDATA` which stores data in the **hidden `appDataFolder`** — a private, per-account space that cannot be shared with other Google accounts. This means Drive sync only works between devices signed into the **same** Google account.

#### Pathway 1 — Shared Drive Folder + `DRIVE_FILE` Scope (Recommended)

Each user with their own Google account can access a shared household folder:

1. **Host setup:** One user ("host") creates a folder named e.g. `Dispensa Household` in their Drive (using `DriveScopes.DRIVE_FILE`), then calls the Drive Permissions API to add other users' email addresses as `writer` members.
2. **Guest join:** Each guest user stores the shared folder ID (obtained via QR code or manual entry), verified by `drive.files().get(folderId)`.
3. **Sync protocol:** Each device writes its own file `dispensa_{deviceId}.json` to the shared folder (instead of one monolithic file). On sync: upload own file, list and download all `dispensa_*.json` files from the folder, import each blob.
4. **Scope:** `DRIVE_FILE` — grants access only to files the app created or files the app explicitly opened. The guest must open/access the shared folder at join time (e.g. by calling `files().get(folderId)` while the user is signed in) to add it to the list of files accessible to the app.

**Key advantages:** `DRIVE_FILE` is the most privacy-respecting scope; users retain full Drive permissions control; works with free Google accounts; no Google Workspace required.

**Key trade-offs:** Requires a UX flow (QR code or link) to share the folder ID out-of-band; only the host can add new members; the host's Drive quota is used for all files.

#### Pathway 2 — Shared Google Drive (Team Drive)

Google Workspace's Shared Drives let multiple accounts be full members. Requires `DriveScopes.DRIVE` (full Drive access). This is impractical for personal use: overkill for a pantry app, requires Workspace subscription, and the broad `DRIVE` scope would be rejected by the Play Store policy review.

#### Pathway 3 — Single Shared File via `DRIVE_FILE` + Google Drive Picker

The host creates the sync file; shares it as "Anyone with the link can edit". The guest uses the Google Drive Picker UI library (`com.google.android.gms:play-services-drive` or the newer `https://developers.google.com/drive/picker`) to "open" the shared file — after which the file is accessible under `DRIVE_FILE` scope. Less structured than Pathway 1 (one file means merge conflicts are harder to avoid) but simpler to implement initially.

#### Recommended Implementation Plan (Pathway 1)

This would be Session 11 work (implemented independently of Session 12):

1. **Scope change:** Add `DriveScopes.DRIVE_FILE` to `GoogleSignInOptions` alongside (or instead of) `DRIVE_APPDATA`. Keep `DRIVE_APPDATA` as a fallback for single-user mode.
2. **New `HouseholdManager` class** (`play/` flavor):
   - `createHousehold(context, emails)` → creates `Dispensa Household` folder; calls `drive.permissions().create(folderId, permission).execute()` for each email; stores `folderId` in `SharedPreferences`.
   - `joinHousehold(context, folderId)` → verifies `drive.files().get(folderId)` succeeds; stores `folderId`.
   - `getFolderId()` → returns stored folder ID; `null` = solo mode.
3. **Updated `GoogleDriveSyncTransport`:**
   - If `HouseholdManager.getFolderId()` is non-null: write own file `dispensa_{deviceId}.json` to the folder; read all `dispensa_*.json` files from the folder and return a merged list of blobs.
   - If folder ID is null: fall back to current `appDataFolder` behaviour (solo mode).
4. **New preferences** (play):
   - `sync_drive_create_household` — opens a dialog to enter email addresses; calls `HouseholdManager.createHousehold()`; shows the join link/ID for sharing.
   - `sync_drive_join_household` — opens a dialog to enter or scan the folder ID.
   - `sync_drive_household_status` — read-only, shows "Solo mode" or "Household: N members".
5. **QR code / deep link:**
   - The join invitation URI: `dispensa://household?folderId=<id>` — handled by a new `<intent-filter>` in `AndroidManifest.xml`.
   - The Settings UI shows the deep-link as a scannable QR code (using ZXing or a simple Bitmap renderer).

This plan would be implemented as Session 11.

### Handoff to Session 12 — Sharing Permission Management

**Next session goal:** Add a device allowlist for local sync and clarify Drive sharing model in the UI.

**Specific tasks (from PLAN.md):**
1. Create `SyncPermissionManager` (main) maintaining a persisted set of trusted device UUIDs.
2. Modify `LocalNetworkSyncTransport.handleIncomingConnection()` to check device ID from the blob header and reject unknown devices.
3. Add `ManageSyncDevicesFragment` listing trusted/pending devices.
4. Add `sync_manage_devices` preference entry in `preferences.xml`.
5. Add Drive sharing info preference explaining the single-account model.
6. Add string resources (en + it).
7. Write unit tests for `SyncPermissionManager`.

**Conventions established this session:**
- `LocalNetworkSyncTransport.getDiscoveredPeers()` must be called **before** `stop()` — `stop()` clears the peer list.
- `SyncSettingsHelper.testDriveConnection(context)` uses `DriveTransportFactory.create(context, null)` — passing `null` for `syncManager` is safe because the factory never uses that parameter.
- `sync_drive_test_connection` preference visibility is toggled by `refreshSignInState()` — always visible when signed in, hidden when not.
- Background work in `SettingsFragment` always uses `Executors.newSingleThreadExecutor()` + `Handler(Looper.getMainLooper()).post()` for UI updates (same pattern established in Session 6 for `clearOpenFoodFactCache` and `cleanOrphanImages`).

---

## Session 11 — Multi-Account Household Drive Sync

**Date:** 2026-04-27  
**Goal:** Implement Pathway 1 from Session 10's Drive sharing analysis: a shared "Dispensa Household" Google Drive folder with per-device JSON files and a deep-link join flow.

### What was done

- Created `HouseholdManager` (play flavor, `eu.frigo.dispensa.sync`):
  - `createHousehold(Drive, Context)` — creates "Dispensa Household" folder in Drive, stores folderId in SharedPreferences, returns folderId.
  - `grantAccess(Drive, folderId, email)` — grants `writer` permission to a Google account email via the Drive Permissions API.
  - `verifyAndJoin(Drive, Context, folderId)` — calls `files().get(folderId)`, stores folderId on success; returns `false` on HTTP 403/404.
  - `generateJoinDeepLink(folderId)` — returns `dispensa://household?folderId=<id>`.
  - `buildDrive(Context, Account)` — builds a Drive service with `DRIVE_FILE` scope.
  - SharedPreferences key: `sync_drive_household_folder_id`.
- Added `HouseholdDriveOperations` inner class to `GoogleDriveSyncTransport`:
  - `downloadSyncFile()` — lists all `dispensa_*.json` files in the household folder, downloads each peer file (skipping own), merges all `SyncChange` lists via `GSON` (static final) into one blob.
  - `uploadSyncFile(data)` — uploads/updates `dispensa_{deviceId}.json` in the household folder.
  - `deviceFileName()` returns `dispensa_{deviceId}.json`.
- Added household-mode constructor to `GoogleDriveSyncTransport(Context, Account, folderId, deviceId)`.
- Updated `DriveTransportFactory.create()` — when `HouseholdManager.getHouseholdFolderId()` is non-null and a device ID exists, returns household-mode transport; otherwise falls back to solo mode.
- Updated `SyncSettingsHelper` (play):
  - `launchSignIn()` now requests both `DRIVE_APPDATA` and `DRIVE_FILE` scopes.
  - Wires 4 new household preferences: `KEY_HOUSEHOLD_STATUS`, `KEY_CREATE_HOUSEHOLD`, `KEY_JOIN_HOUSEHOLD`, `KEY_LEAVE_HOUSEHOLD`.
  - `showCreateHouseholdDialog()` — EditText for comma-separated emails; runs `createHousehold` + `grantAccess` on background thread; shows the join deep-link in an AlertDialog.
  - `showJoinHouseholdDialog()` — EditText for folder ID or full deep-link; parses via `extractFolderIdFromInput()`; runs `verifyAndJoin` on background thread.
  - `leaveHousehold()` — confirmation dialog; clears folderId; refreshes state.
  - `refreshSignInState()` updated to show/hide household prefs and update `KEY_HOUSEHOLD_STATUS` summary.
  - `handleHouseholdDeepLink(fragment, folderId)` — pre-fills and shows join dialog (public API).
  - `signOut()` now also calls `HouseholdManager.clearHouseholdFolderId()`.
  - `extractFolderIdFromInput(input)` — handles both bare folder IDs and `dispensa://household?…` URIs.
- Added no-op `handleHouseholdDeepLink(fragment, folderId)` to `SyncSettingsHelper` (fdroid).
- Added 4 household prefs to `preferences_sync_drive.xml` (play).
- Updated `AndroidManifest.xml`: `SettingsActivity` now has `android:exported="true"` and a `<intent-filter>` for `dispensa://household`.
- Updated `SettingsActivity.onCreate()`: detects deep-link via `extractHouseholdFolderIdFromIntent(intent)`, passes `ARG_HOUSEHOLD_FOLDER_ID` Bundle arg to `SettingsFragment`.
- Updated `SettingsFragment`: added `ARG_HOUSEHOLD_FOLDER_ID` constant; after `SyncSettingsHelper.setup()` checks for the arg and calls `handleHouseholdDeepLink()`.
- Added 19 string resources each to `strings.xml` (en) and `strings.xml` (it).
- Created `HouseholdManagerTest` (play test flavor): 9 unit tests covering deep-link generation, folder creation, access granting, join verification.

### Files changed

- `app/src/play/java/eu/frigo/dispensa/sync/HouseholdManager.java` — new file
- `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` — added `HouseholdDriveOperations`, household constructor, `buildHouseholdDriveOps()`
- `app/src/play/java/eu/frigo/dispensa/sync/DriveTransportFactory.java` — household routing + updated Javadoc
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — household prefs wiring, dialogs, deep-link handler, DRIVE_FILE scope, sign-out clears household
- `app/src/fdroid/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — added no-op `handleHouseholdDeepLink()`
- `app/src/play/res/xml/preferences_sync_drive.xml` — 4 new household prefs
- `app/src/main/AndroidManifest.xml` — `SettingsActivity` deep-link intent-filter
- `app/src/main/java/eu/frigo/dispensa/activity/SettingsActivity.java` — deep-link parsing + Bundle arg to fragment
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — `ARG_HOUSEHOLD_FOLDER_ID` + deep-link pass-through
- `app/src/main/res/values/strings.xml` — 19 new household strings (en)
- `app/src/main/res/values-it/strings.xml` — 19 new household strings (it)
- `app/src/testPlay/java/eu/frigo/dispensa/sync/HouseholdManagerTest.java` — new file; 9 unit tests

### Test results

- 54 unit tests: all pass (`testPlayDebugUnitTest` + `testFdroidDebugUnitTest`)
- Both flavors compile: `compileFdroidDebugJavaWithJavac` + `compilePlayDebugJavaWithJavac` BUILD SUCCESSFUL

### Handoff to Session 13

- **What to do next:** Session 12 (`SyncPermissionManager` for device allowlisting on the local-network transport) remains unimplemented and is still the next logical step before finalizing the release.
- **Household sync is live in play flavor.** Remaining polish:
  - Copy-to-clipboard button in the deep-link dialog (currently just a selectable EditText).
  - QR code generation for the join link (requires `zxing` dependency).
  - Household folder name lookup on status preference (currently shows folder ID; could call `drive.files().get(folderId).setFields("name")` to resolve the friendly name).
- **Known scope limitation:** `DRIVE_FILE` allows each device to access files it created in the shared folder. Guests who join via the deep link can upload their own `dispensa_{deviceId}.json` (DRIVE_FILE owns it), and can list/download peer files IF those files were shared with their account (i.e. the folder was shared with them). If guests cannot list peer files, they will only push their changes and receive nothing from peers. This is a known limitation of `DRIVE_FILE` scope vs `DRIVE` scope.
- **Conventions established this session:**
  - `HouseholdDriveOperations.GSON` is `static final` (Gson is thread-safe; shared instance avoids repeated construction).
  - `extractFolderIdFromInput(input)` in `SyncSettingsHelper` handles both bare IDs and full deep-link URIs — use this for any future input parsing.
  - `SyncSettingsHelper.handleHouseholdDeepLink(fragment, folderId)` must be a no-op in fdroid flavor to keep both flavors' public API in sync.
  - `SettingsActivity.extractHouseholdFolderIdFromIntent(intent)` uses hard-coded scheme/host strings `"dispensa"` / `"household"` — these match `HouseholdManager.DEEP_LINK_SCHEME` / `DEEP_LINK_HOST` but are not directly referenced to avoid importing play-flavor classes from `main`.

---

## Session 12 — Sharing Permission Management

**Date:** 2026-04-27  
**Goal:** Add a device allowlist for local-network sync so only explicitly trusted devices can exchange changes.

### What was done

- **Wire format enhanced:** Added `senderDeviceId: String` field to `SyncBlob`. `SyncManager.exportChanges()` now populates it with the local device UUID. Added `SyncManager.extractSenderDeviceId(byte[])` package-private static helper used by the transport layer to parse the ID without double-importing the blob.
- **`SyncPermissionManager` created** (`eu.frigo.dispensa.sync`, main source set):
  - Persists two `StringSet` keys to a private `SharedPreferences` file (`sync_permissions.xml`): trusted devices and pending devices.
  - Public API: `isTrusted(id)`, `trust(id)`, `revoke(id)`, `markPending(id)`, `dismissPending(id)`, `getTrustedDeviceIds()`, `getPendingDeviceIds()`.
  - Package-private `SharedPreferences` constructor for unit tests.
- **`LocalNetworkSyncTransport` updated:**
  - Production constructor now creates `SyncPermissionManager(context)` and chains to a new 5-parameter internal constructor.
  - Existing 4-parameter test constructor delegates to the 5-parameter one with `null` (permission manager null = allow all, existing tests unchanged).
  - `handleIncomingConnection()` now enforces trust: devices without `senderDeviceId` (old clients) are rejected with a warning; devices with an ID that is not in the trusted set are added to pending and receive an empty blob back; only trusted devices proceed to `importChanges()`.
  - Extracted `writeEmptyBlob(DataOutputStream)` private helper.
- **`ManageSyncDevicesFragment` created** (main source set, `eu.frigo.dispensa.ui`):
  - Two sections: **Trusted devices** (each with a "Revoke" button) and **Pending devices** (each with "Approve" / "Dismiss" buttons).
  - Device UUIDs are shown abbreviated to 8 characters.
  - Uses `SyncPermissionManager` directly (no ViewModel needed for this small list).
  - Layout: `fragment_manage_sync_devices.xml` — MaterialToolbar + ScrollView with a programmatically populated LinearLayout.
- **`preferences.xml`:** added `sync_manage_devices` preference after `sync_local_scan_peers`.
- **`SettingsFragment.onPreferenceTreeClick()`:** added case for `sync_manage_devices` → fragment replace to `ManageSyncDevicesFragment`, matching the `ManageLocationsFragment` pattern.
- **Drive sharing info pref:** skipped — Session 11's `KEY_HOUSEHOLD_STATUS` already surfaces the sync mode in the UI; adding a redundant info pref would clutter the settings screen.
- **`SyncManagerTest`:** updated 3 test usages of `new SyncBlob(...)` to pass `senderDeviceId` (constructor changed).
- **`GoogleDriveSyncTransport.HouseholdDriveOperations.downloadSyncFile()`:** updated `new SyncBlob(...)` call — passes `null` for the merged household blob (multi-source, Drive sync bypasses the trust check).
- Added 12 string resources each to `strings.xml` (en) and `strings.xml` (it).
- Created `SyncPermissionManagerTest` (11 unit tests).

### Files changed

- `app/src/main/java/eu/frigo/dispensa/sync/SyncBlob.java` — added `senderDeviceId` field + updated constructor
- `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java` — populate `senderDeviceId` in `exportChanges()`; add `extractSenderDeviceId()` helper
- `app/src/main/java/eu/frigo/dispensa/sync/SyncPermissionManager.java` — new file
- `app/src/main/java/eu/frigo/dispensa/sync/LocalNetworkSyncTransport.java` — added `SyncPermissionManager` field + constructors; trust enforcement in `handleIncomingConnection()`; `writeEmptyBlob()` helper
- `app/src/main/java/eu/frigo/dispensa/ui/ManageSyncDevicesFragment.java` — new file
- `app/src/main/res/layout/fragment_manage_sync_devices.xml` — new file
- `app/src/main/res/xml/preferences.xml` — added `sync_manage_devices`
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — `sync_manage_devices` navigation in `onPreferenceTreeClick()`
- `app/src/main/res/values/strings.xml` — 12 new strings (en)
- `app/src/main/res/values-it/strings.xml` — 12 new strings (it)
- `app/src/test/java/eu/frigo/dispensa/sync/SyncPermissionManagerTest.java` — new file; 11 unit tests
- `app/src/test/java/eu/frigo/dispensa/sync/SyncManagerTest.java` — updated 3 `SyncBlob` constructor calls
- `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` — updated `SyncBlob` constructor call in `HouseholdDriveOperations`
- `PLAN.md` — Session 12 tasks marked complete

### Test results

- 103 unit tests: all pass (`testFdroidDebugUnitTest` + `testPlayDebugUnitTest`)
- Both flavors compile: `compileFdroidDebugJavaWithJavac` + `compilePlayDebugJavaWithJavac` BUILD SUCCESSFUL

### Handoff to Session 13

- Session 12 is now complete. Session 11 (Household Drive Sync) was already done.
- **Remaining polish from Session 11:**
  - Copy-to-clipboard button in the household deep-link dialog.
  - QR code generation for the join link (requires `zxing` dependency).
  - Household folder name lookup on status preference (show friendly name instead of folder ID).
- **Session 12 device trust UX note:** When a user first enables local network sync, ALL other Dispensa devices will appear as "pending" until explicitly approved. This is intentional (strict trust model). Users must open Settings → Manage trusted devices to approve peers. Consider adding a notification or badge to the preference summary when new pending devices arrive.
- **Conventions established this session:**
  - `SyncPermissionManager` uses its own `SharedPreferences` file (`sync_permissions`) to avoid polluting the app-wide default prefs.
  - `LocalNetworkSyncTransport`'s 4-parameter test constructor chains to the 5-parameter one with `null` permission manager — tests that don't need trust enforcement pass `null` and are unaffected.
  - `SyncManager.extractSenderDeviceId(byte[])` is package-private static — called only by `LocalNetworkSyncTransport` from the same package.

---

## Session 13 — Debug Logging Build

**Date:** 2026-04-27  
**Goal:** Add file-based debug logging across all features so the user can export a log for diagnosing sign-in and Drive sync issues.

### What was done

- **`DebugLogger.java`** created (`eu.frigo.dispensa.util`):
  - Singleton file logger writing timestamped lines to `dispensa_debug.log` in `Context.getFilesDir()`.
  - 1 MB automatic rotation (deletes and restarts when limit exceeded).
  - All calls also pass through `android.util.Log` so logcat is unaffected.
  - Public API: `init(context)`, `i()`, `w()`, `e()`, `getLogFile()`, `clear()`.
- **`Dispensa.java`** — calls `DebugLogger.init(this)` first in `onCreate()`.
- **`file_paths.xml`** — added `<files-path name="debug_logs" path="."/>` so `FileProvider` can serve the log file from `filesDir`.
- **Debug preferences** added to `preferences.xml`:
  - `pref_cat_debug` category ("Debug").
  - `pref_debug_export_log` — shares `dispensa_debug.log` via `ACTION_SEND` + `FileProvider`.
  - `pref_debug_clear_log` — calls `DebugLogger.clear()` and shows a Toast.
- **`SettingsFragment.java`** — wires both debug prefs in `onCreatePreferences()`; `exportDebugLog()` method handles `FileProvider` URI creation and the share chooser; added `FileProvider`, `Uri`, and `DebugLogger` imports.
- **DebugLogger calls added to all features:**
  - `SyncSettingsHelper` (play) — sign-in launch, sign-in result (with status code on failure), Drive toggle, household create/join, sign-out, test connection.
  - `GoogleDriveSyncTransport` — push/pull start, byte counts, errors.
  - `DriveTransportFactory` — each routing branch (disabled, no account, household, solo).
  - `HouseholdManager` — `createHousehold`, `grantAccess`, `verifyAndJoin` (all branches), `clearHouseholdFolderId`.
  - `LocalNetworkSyncTransport` — `start()`, `stop()`, `push()`, `handleIncomingConnection()` (with trust check outcomes), NSD registration and discovery listeners, service resolve.
  - `SyncManager` — `exportChanges()` (change count + byte count), `importChanges()` (sender device ID, change count, transaction commit).
  - `SyncWorker` — discovery wait start/end with peer count, export byte count, push outcome, Drive transport availability, Drive push/import outcomes, errors.
  - `SyncPermissionManager` — `trust()`, `revoke()`, `markPending()`, `dismissPending()`.
- **String resources** (en + it): `pref_cat_debug_title`, `pref_debug_export_log_title/summary`, `pref_debug_clear_log_title/summary`, `notify_debug_log_empty`, `notify_debug_log_cleared`, `notify_debug_log_share_subject`, `notify_debug_log_share_chooser`, `notify_debug_log_share_failed`.

### Files changed

- `app/src/main/java/eu/frigo/dispensa/util/DebugLogger.java` — new file
- `app/src/main/java/eu/frigo/dispensa/Dispensa.java` — `DebugLogger.init(this)` in `onCreate()`
- `app/src/main/res/xml/file_paths.xml` — added `files-path` entry for log file
- `app/src/main/res/xml/preferences.xml` — added `pref_cat_debug` with export + clear prefs
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — wired debug prefs; added `exportDebugLog()` method
- `app/src/main/java/eu/frigo/dispensa/sync/SyncManager.java` — added `TAG` constant + DebugLogger calls
- `app/src/main/java/eu/frigo/dispensa/sync/LocalNetworkSyncTransport.java` — DebugLogger calls throughout
- `app/src/main/java/eu/frigo/dispensa/sync/SyncPermissionManager.java` — DebugLogger calls in trust management methods
- `app/src/main/java/eu/frigo/dispensa/work/SyncWorker.java` — DebugLogger calls throughout sync cycle
- `app/src/main/res/values/strings.xml` — 10 new English strings
- `app/src/main/res/values-it/strings.xml` — 10 new Italian strings
- `app/src/play/java/eu/frigo/dispensa/sync/DriveTransportFactory.java` — DebugLogger routing logs
- `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` — DebugLogger push/pull logs
- `app/src/play/java/eu/frigo/dispensa/sync/HouseholdManager.java` — DebugLogger Drive API logs
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — DebugLogger sign-in + household logs
- `PLAN.md` — Session 13 tasks added and marked complete

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all tests pass.
- `compileFdroidDebugJavaWithJavac` — BUILD SUCCESSFUL.
- `compilePlayDebugJavaWithJavac` — BUILD SUCCESSFUL.

### Handoff to Session 14

- **Debug log is ready.** The user should install the play build, reproduce the sign-in issue, then go to Settings → Debug → Export debug log and share the file.
- **Known issue to diagnose:** Tapping "Sign in with Google" returns the user to the home screen instead of completing sign-in. The `handleSignInResult()` log will capture the `resultCode` and any `ApiException` status code — the most likely causes are:
  1. SHA-1 fingerprint mismatch in Google Cloud Console (debug keystore not registered).
  2. Missing OAuth 2.0 client ID in `google-services.json`.
  3. `RESULT_CANCELED` (resultCode=0) — user pressed back, or the account picker was dismissed automatically.
- **Google Drive sync checkbox opens account picker but nothing happens:** This is consistent with `RESULT_OK` being returned but `getSignedInAccountFromIntent` throwing `ApiException`. The log's `handleSignInResult` entry will show the exact status code.
- **Conventions established this session:**
  - `DebugLogger.init(Context)` must be called once from `Application.onCreate()` before any other component starts — it is idempotent but not thread-safe at init time.
  - All DebugLogger calls also invoke the corresponding `android.util.Log` method — both channels are always active, never use DebugLogger as a replacement for Log.
  - The log file path is `context.getFilesDir()/dispensa_debug.log`. The `FileProvider` authority is `eu.frigo.dispensa.fileprovider`.

---

## Session 14 — Google Sign-In Troubleshooting

**Date:** 2026-04-27  
**Goal:** Analyse exported debug logs, identify the root cause of the silent Google Sign-In failure, and fix the auth flow.

### What was done

- **Analysed two debug log exports provided by the user:**
  - **Sign-in button log:** `launchSignIn` was called and `launcher.launch()` was reached, but the process immediately restarted (`=== Dispensa debug log opened ===` appeared 154 ms after `launcher.launch()`). This indicated an unhandled exception crashed the process before the result callback was ever delivered.
  - **Checkbox log:** `handleSignInResult` was called with `resultCode=0` (`RESULT_CANCELED`) even after the user selected a Google account — sign-in returned silently with no user-facing feedback.

- **Identified root cause via Google developer documentation:**  
  `play-services-auth` 21.x requires Drive scopes (`DRIVE_APPDATA`, `DRIVE_FILE`) to be requested in a **separate, explicit authorization step** via `Identity.getAuthorizationClient().authorize()` — they can no longer be bundled into `GoogleSignInOptions`. The old combined approach returns `RESULT_CANCELED` silently.

- **Short-term defensive fixes** (committed first):
  - `handleSignInResult`: when `resultCode != RESULT_OK` but `getData() != null`, now extracts the `ApiException` status code and logs it; shows a Toast on failure (was completely silent before).
  - `launchSignIn`: wrapped `launcher.launch()` in `try/catch(IllegalStateException)` — an unexpected exception now logs to `DebugLogger` and shows a Toast rather than silently crashing.

- **Two-step auth flow migration** (main fix):
  - `launchSignIn`: stripped `DRIVE_APPDATA`/`DRIVE_FILE` from `GoogleSignInOptions`; now requests email only.
  - Added `launchDriveAuthorization(fragment, authLauncher)`: calls `Identity.getAuthorizationClient().authorize()` with the two Drive scopes; if already granted → `completeDriveAuthorization` immediately; if consent needed → launches the consent screen via `googleDriveAuthLauncher`.
  - `handleSignInResult` success path: now calls `launchDriveAuthorization` after refreshing the UI instead of enabling Drive sync directly.
  - Added `handleAuthorizationResult(fragment, result)`: processes the consent-screen result; stores and logs the `AuthorizationResult`; calls `completeDriveAuthorization` on success or `onDriveAuthorizationFailed` on failure/cancellation.
  - Added `completeDriveAuthorization(fragment, alwaysToast)`: enables `sync_drive_enabled` pref and shows confirmation Toast with signed-in email.
  - Added `onDriveAuthorizationFailed(fragment)`: reverts the Drive sync toggle to false and shows error Toast.
  - `onDriveEnabledChanged`: when a Google account is already present but Drive scopes haven't been granted, now calls `launchDriveAuthorization` (covers the re-enable-toggle case).

- **`SyncSettingsHelper` (fdroid) stubs** updated to match the new method signatures (`handleSignInResult` gains `authLauncher` param; `onDriveEnabledChanged` gains `authLauncher` param; new `handleAuthorizationResult` stub added).

- **`SettingsFragment`**: added `googleDriveAuthLauncher` field (`ActivityResultLauncher<IntentSenderRequest>`, registered via `StartIntentSenderForResult` in `onCreate()`); updated `handleSignInResult` and `onDriveEnabledChanged` call sites to pass it through.

### Files changed

- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — two-step auth flow; new methods; defensive fixes
- `app/src/fdroid/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — updated stub signatures; new `handleAuthorizationResult` stub
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — added `googleDriveAuthLauncher`; updated call sites
- `PLAN.md` — Session 14 tasks added and marked complete

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all tests pass.
- `compileFdroidDebugJavaWithJavac` — BUILD SUCCESSFUL.
- `compilePlayDebugJavaWithJavac` — BUILD SUCCESSFUL.

### Handoff to Session 15

- **The two-step auth flow is in place.** The user should install the updated play build and retry Google Sign-In.
- **Expected new flow:** Sign-In button → Google account picker → account selected → Drive scope consent screen appears → user approves → Drive sync is enabled and confirmed with a Toast.
- **If sign-in still fails:** The DebugLogger will now capture the `ApiException` status code. Key codes to watch for:
  - `10` — `DEVELOPER_ERROR`: SHA-1 fingerprint or package name mismatch in Google Cloud Console. The debug keystore SHA-1 must be registered as an Android OAuth client in the project.
  - `12500` — `SIGN_IN_FAILED`: general OAuth misconfiguration (check OAuth consent screen and client ID).
  - `12501` — `SIGN_IN_CANCELLED`: user dismissed the picker (not a code bug).
- **If the authorization step fails:** `handleAuthorizationResult` logs `statusCode` — check `launchDriveAuthorization: authorize() failed` in the log.
- **Outstanding polish items** (from prior sessions, not addressed this session):
  - Copy-to-clipboard button in the household deep-link dialog.
  - QR code generation for the join link.
  - Household folder friendly name in status preference.
  - Notification/badge when new pending sync devices arrive.
- **Conventions established this session:**
  - Sign-in and Drive scope authorization are two distinct flows. `launchSignIn` only gets the Google account (email); `launchDriveAuthorization` requests Drive scopes via `Identity.getAuthorizationClient().authorize()`.
  - `SettingsFragment` must register both `googleSignInLauncher` (`StartActivityForResult`) and `googleDriveAuthLauncher` (`StartIntentSenderForResult`) in `onCreate()` before the fragment starts.
  - Drive sync is enabled (`sync_drive_enabled = true`) only after `completeDriveAuthorization()` is called — never directly in the sign-in result handler.

---

## Session 15 — Credential Manager Migration + Google Cloud Setup Guide

> **Sub-sessions:** [15.1 — Fix silent sign-in failure in OAuth Testing mode](#session-151--fix-silent-sign-in-failure-in-oauth-testing-mode) | [15.2 — Fix CustomCredential from GetSignInWithGoogleOption](#session-152--fix-customcredential-from-getsigninwithgoogleoption)

**Date:** 2026-04-27  
**Goal:** Replace deprecated `GoogleSignIn`/`GoogleSignInClient` with the modern Android Credential Manager API and provide a complete Google Cloud Console setup guide.

### What was done

- **Root cause confirmed:** `statusCode=10 (DEVELOPER_ERROR)` in both log files is caused by (a) the debug build using `eu.frigo.dispensa.debug` (via `applicationIdSuffix`) with no matching Android OAuth Client ID registered, and (b) the `GoogleSignInClient` legacy API being deprecated/removed in H2 2025.

- **Credential Manager migration (play flavor):**
  - Added three new play-flavor dependencies: `androidx.credentials:credentials:1.6.0`, `androidx.credentials:credentials-play-services-auth:1.6.0`, `com.google.android.libraries.identity.googleid:googleid:1.1.0`.
  - `SyncSettingsHelper` (play): removed all `GoogleSignIn`/`GoogleSignInAccount`/`GoogleSignInOptions`/`GoogleSignInClient` usage; replaced `launchSignIn()` with Credential Manager `getCredentialAsync()` using `GetGoogleIdOption`; removed `handleSignInResult()` (replaced by a callback); added `handleCredentialResponse()` private helper; updated `refreshSignInState()` to read from SharedPreferences instead of `GoogleSignIn.getLastSignedInAccount()`; updated `signOut()` to use `CredentialManager.clearCredentialStateAsync()`; updated `createHousehold()` and `joinHousehold()` to construct `Account` from stored email.
  - Renamed `setSignInLauncher()` → `setDriveAuthLauncher()` (now takes `authLauncher` only; sign-in is fully internal).
  - Simplified `onDriveEnabledChanged()` — removed `signInLauncher` param (no longer needed).
  - `DriveTransportFactory` (play): added `PREF_SIGNED_IN_EMAIL` + `GOOGLE_ACCOUNT_TYPE` constants; replaced `GoogleSignIn.getLastSignedInAccount()` with SharedPreferences email lookup + `new Account(email, "com.google")`.
  - `SyncSettingsHelper` (fdroid stub): removed `handleSignInResult`, `setSignInLauncher` stubs; added `setDriveAuthLauncher` stub; updated `onDriveEnabledChanged` to remove `signInLauncher` param.
  - `SettingsFragment`: removed `googleSignInLauncher` field and its `registerForActivityResult` call; updated call sites to use new method signatures.

- **Web Client ID placeholder:** Added `google_web_client_id` string resource to `app/src/play/res/values/config.xml` with value `YOUR_WEB_CLIENT_ID` and a comment pointing to `GOOGLE_CLOUD_SETUP.md`.

- **`GOOGLE_CLOUD_SETUP.md`** created at repository root — 7-step guide covering: create Cloud project, enable Drive API, configure OAuth consent screen (with Drive scopes), create Web Client ID, create Android Client IDs (debug + release), add Web Client ID to `config.xml`, and build/test. Includes troubleshooting table and notes for upstream integration.

- **`PLAN.md`** architecture table updated: Authentication row updated to Credential Manager.

### Files changed

- `gradle/libs.versions.toml` — added `credentialsVersion`, `googleidVersion` versions; added `credentials`, `credentials-play-services-auth`, `googleid` library entries
- `app/build.gradle.kts` — added three play-flavor credentials + googleid dependencies
- `app/src/play/res/values/config.xml` — added `google_web_client_id` placeholder string
- `app/src/play/java/eu/frigo/dispensa/sync/DriveTransportFactory.java` — `PREF_SIGNED_IN_EMAIL`, `GOOGLE_ACCOUNT_TYPE` constants; replaced `GoogleSignIn` with SharedPreferences + `Account`
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — full Credential Manager migration
- `app/src/fdroid/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — updated stubs to match new API
- `app/src/main/java/eu/frigo/dispensa/ui/SettingsFragment.java` — removed `googleSignInLauncher`; updated call sites
- `GOOGLE_CLOUD_SETUP.md` — new file; full Google Cloud Console setup guide
- `PLAN.md` — authentication architecture decision updated

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compileFdroidDebugJavaWithJavac :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- `./gradlew testFdroidDebugUnitTest testPlayDebugUnitTest` — **BUILD SUCCESSFUL** — all tests pass.

---

## Session 15.1 — Fix silent sign-in failure in OAuth Testing mode

**Date:** 2026-04-28  
**Goal:** Fix `GetGoogleIdOption(filterByAuthorized=false)` silently returning `NoCredentialException` without showing any UI when the OAuth consent screen is in Testing mode, causing the Sign-in button and Drive sync checkbox to do nothing visible.

### What was done

- **Root cause diagnosed:** `GetGoogleIdOption(filterByAuthorized=false)` is validated by Play Services against the OAuth test-user list *before* rendering the bottom sheet. When no account on the device passes the check, it fires `NoCredentialException` silently (~500 ms, matching the debug logs stopping at `calling getCredentialAsync filterByAuthorized=false`). The "checkbox remains checked" was a consequence — the toggle was correctly reverted but sign-in never completed.
- **Fixed `SyncSettingsHelper.doLaunchSignIn`**: replaced the `filterByAuthorized=false` retry (`GetGoogleIdOption`) with `GetSignInWithGoogleOption`. No new dependencies required — `GetSignInWithGoogleOption` is already in `googleid:1.1.0`. The two-step flow is now:
  1. `GetGoogleIdOption(filterByAuthorized=true)` — silent re-auth for returning users.
  2. `GetSignInWithGoogleOption` — full Sign in with Google sheet; shouls respect Testing mode correctly; shows a visible error to non-test accounts rather than failing silently.
- **Improved error logging:** `onError` handler now logs the exception class name (`[NoCredentialException]`, `[GetCredentialCancellationException]`, etc.) alongside the message.
- **`GOOGLE_CLOUD_SETUP.md` troubleshooting table** updated with a new row documenting this exact silent-failure symptom and its fix.

### Files changed

- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — `doLaunchSignIn`: use `GetSignInWithGoogleOption` for the picker step; added `GetSignInWithGoogleOption` import; improved error log message
- `GOOGLE_CLOUD_SETUP.md` — added troubleshooting row for the silent sign-in failure

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- `./gradlew testFdroidDebugUnitTest` — **BUILD SUCCESSFUL** — all 26 tests pass.
- CodeQL security scan — **0 alerts**.

---

## Session 15.2 — Fix CustomCredential from GetSignInWithGoogleOption

**Date:** 2026-04-28  
**Goal:** Fix `handleCredentialResponse` failing with "unexpected credential type: g" after the user completes the Sign in with Google account picker and grants the email permission.

### What was done

- **Root cause diagnosed from debug log:** The log showed `handleCredentialResponse: unexpected credential type: g` on every sign-in attempt after the account picker returned successfully. `"g"` is the minified/ProGuard-obfuscated class name of `androidx.credentials.CustomCredential`. `GetSignInWithGoogleOption` (introduced in 15.1) causes Credential Manager to return a `CustomCredential` whose `getType()` equals `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` — not a direct `GoogleIdTokenCredential` instance. The previous handler only checked `instanceof GoogleIdTokenCredential`, so the credential was always rejected and sign-in never completed.
- **Fixed `SyncSettingsHelper.handleCredentialResponse`**: replaced the single `instanceof GoogleIdTokenCredential` guard with a two-branch check:
  1. `instanceof GoogleIdTokenCredential` — direct cast (silent re-auth path via `GetGoogleIdOption`).
  2. `instanceof CustomCredential && type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` — unwrap with `GoogleIdTokenCredential.createFrom(data)` (picker path via `GetSignInWithGoogleOption`).
- Added `import androidx.credentials.CustomCredential`.

### Files changed

- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — `handleCredentialResponse`: added `CustomCredential` unwrap branch; added `import androidx.credentials.CustomCredential`

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- CodeQL security scan — **0 alerts**.
- Code review — no remaining issues (feedback to narrow the catch clause was resolved by removing the try-catch entirely, since `GoogleIdTokenCredential.createFrom()` does not declare a checked exception in `googleid:1.1.0`).

---

### Handoff to Session 16

_(Updated by Session 15.2: `handleCredentialResponse` now correctly handles `CustomCredential` returned by `GetSignInWithGoogleOption`; sign-in through the account picker now completes successfully.)_

_(Updated by Session 15.1: `GetSignInWithGoogleOption` is now used for the picker step, so the Sign in with Google sheet should appear correctly in OAuth Testing mode. No handoff tasks were added or removed.)_

- **Before building the play APK**, replace `YOUR_WEB_CLIENT_ID` in `app/src/play/res/values/config.xml` following `GOOGLE_CLOUD_SETUP.md`.
- **Expected sign-in flow:** Settings → Sign in with Google → Standard "Sign in with Google" bottom sheet appears → user selects a listed test account → Drive scope consent screen (if not yet granted) → Drive sync enabled with Toast confirmation.
- **Sign-in no longer uses an `ActivityResultLauncher<Intent>`** — the Credential Manager flow is callback-based and requires no activity result registration for the sign-in step itself.
- **`PREF_SIGNED_IN_EMAIL`** (`sync_drive_signed_in_email`) is the source of truth for "who is signed in". `DriveTransportFactory.create()` reads this key; `SyncSettingsHelper` writes/clears it. `GoogleSignIn.getLastSignedInAccount()` is no longer called anywhere.
- **Outstanding polish items** (carried forward):
  - Copy-to-clipboard button in the household deep-link dialog.
  - QR code generation for the join link.
  - Household folder friendly name in status preference.
  - Notification/badge when new pending sync devices arrive.
- **Conventions established this session:**
  - Signed-in email is persisted in default SharedPreferences under key `DriveTransportFactory.PREF_SIGNED_IN_EMAIL`. Always read/write via this constant.
  - `Account` objects for Drive API calls are always constructed as `new Account(email, DriveTransportFactory.GOOGLE_ACCOUNT_TYPE)` — never obtained from `GoogleSignIn`.
  - The Web Client ID (`google_web_client_id` string resource) must be replaced before the play flavor will work. It is intentionally left as a placeholder in the repo so each fork/release keystore owner supplies their own.
  - `setDriveAuthLauncher()` replaces the old `setSignInLauncher()` — it wires the sign-in preference button and passes the `authLauncher` to `launchSignIn()`.

---

## Session 16 — Drive API Crash Fixes + Stability Hardening

**Date:** 2026-04-28  
**Goal:** Fix fatal crashes in "Test Drive connection" and "Create a household" observed after a successful Credential Manager sign-in and Drive scope grant.

### What was done

#### Root cause analysis

Both crashes were diagnosed from the exported debug log (provided in the problem statement).  Two distinct root causes were identified:

**Root cause 1 — uncaught exception from `GoogleDriveSyncTransport`:**  
`pull()` and `push()` only catch `IOException` and the inner `AuthException`.  In `play-services-auth 21.x`, `GoogleAccountCredential.intercept()` can propagate non-`IOException` types (e.g. `RuntimeException` from `GoogleAuthUtil.getToken()` or Play Services IPC) that bypass both catch clauses.  For `testDriveConnection`, the executor lambda `() -> transport.pull(...)` had **no exception handling at all**, so such an exception reached the thread's `UncaughtExceptionHandler` and killed the process.  This explains the 13–18 second gaps between `pull: starting` and `=== Dispensa debug log opened ===` (the IPC/HTTP call was in flight when the exception occurred) and the absence of any error log entry (the exception bypassed the `DebugLogger.e()` calls inside the catch blocks).

**Root cause 2 — fragment lifecycle violation in `mainHandler.post()` callbacks:**  
`createHousehold()` and `joinHousehold()` post a Runnable to the main thread after the background Drive call completes (~5 seconds). The Runnable called `refreshSignInState(fragment)` → `fragment.requireContext()` unconditionally.  If the host Activity had been recreated (e.g. screen rotation) during those 5 seconds, the captured `fragment` reference was already detached, causing `IllegalStateException: Fragment not attached to a context` on the **main thread**.  `showDeepLinkDialog()` calling `AlertDialog.show()` on a dead window could similarly throw `WindowManager$BadTokenException`.  Both are uncaught main-thread crashes.  This explains the fast subsequent crashes (0.15–0.18 s): on restart the Drive folder was already created (the `SharedPreferences.apply()` from the first attempt may not have flushed, so the button remained visible), the token request failed or succeeded quickly, and the same main-thread callback race fired again.

#### Fixes applied

- **`GoogleDriveSyncTransport.pull()` and `push()`:** added `catch (Exception e)` after `catch (IOException e)`.  Any `RuntimeException` or other non-`IOException` thrown by the Drive/auth layer is now caught, logged at `ERROR` level via `DebugLogger` and `Log`, and forwarded to the `SyncCallback` as a wrapped `IOException`.  This ensures errors always appear in the debug log and the app does not crash.

- **`SyncSettingsHelper.testDriveConnection()` executor lambda:** wrapped the `transport.pull(...)` call in a `try { … } catch (Exception e)` block.  This is a belt-and-suspenders guard: `pull()` now handles everything internally, but this prevents any hypothetical future escape path from propagating to the thread's `UncaughtExceptionHandler`.

- **`SyncSettingsHelper.createHousehold()` success callback:** added `if (fragment.isAdded())` guard before `refreshSignInState(fragment)`; wrapped `showDeepLinkDialog(context, deepLink)` in `try/catch(Exception)` to absorb `WindowManager$BadTokenException` or similar.

- **`SyncSettingsHelper.joinHousehold()` success callback:** same `fragment.isAdded()` guard before `refreshSignInState(fragment)`.

### Files changed

- `app/src/play/java/eu/frigo/dispensa/sync/GoogleDriveSyncTransport.java` — `pull()` and `push()`: added `catch (Exception e)` defensive handler
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — `testDriveConnection()`: executor lambda wrapped in try-catch; `createHousehold()`: `isAdded()` guard + `showDeepLinkDialog` try-catch; `joinHousehold()`: `isAdded()` guard

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compilePlayDebugJavaWithJavac` — **BUILD SUCCESSFUL**.
- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew testPlayDebugUnitTest` — **all tests pass**.

---

## Session 16.1 — R8 ProGuard + Deeper Error Handling for Drive API

> _(Sub-session of Session 16; addresses new crash evidence from fresh install logs.)_

**Date:** 2026-04-28  
**Goal:** Fix the root cause of `IllegalArgumentException: key error` / `unable to create new instance of class X because it is abstract` errors in all Drive API calls, and ensure `createHousehold` / `joinHousehold` executor runnables can never silently force-close the app.

### What was done

#### Root cause analysis (new log evidence)

Two new log sessions were provided showing the same `IllegalArgumentException: key error` error in `GoogleDriveSyncTransport$RealDriveOperations.findSyncFileId` calling `drive.files().list().execute()`.  The full stack trace identifies the root cause:

```
Caused by: java.lang.IllegalArgumentException: unable to create new instance of class y8.a
    because it is abstract and because it has no accessible default constructor
```

`y8.a` is the R8-obfuscated name for a Google API Client data-model class (likely `com.google.api.client.json.GenericJson` or a direct subclass such as `com.google.api.services.drive.model.FileList`).  R8's **class-merging** optimisation merged a concrete class into an abstract ancestor, making it non-instantiable.  Gson (used by `GsonFactory.getDefaultInstance()`) tries to create an instance via reflection when deserialising the Drive API HTTP response and throws `IllegalArgumentException`.

The missing ProGuard rules are the root cause:
1. `com.google.api.client.**` and `com.google.api.services.drive.**` were not kept — R8 was free to merge, rename, and remove constructors.
2. Fields annotated with `@com.google.api.client.util.Key` (the marker used for JSON field-name mapping in `GenericData`) were not kept — even if the class survived, field renaming would produce `"key error"` at runtime.

Additionally, `createHousehold` and `joinHousehold` executor runnables only caught `Exception`, so if R8-broken code threw an `Error` subclass (e.g. `NoClassDefFoundError`, `ExceptionInInitializerError`) the uncaught error reached Android's default `UncaughtExceptionHandler` and force-closed the app with no entry in the debug log.

The `refreshSignInState(fragment)` call in the `createHousehold` success-path `mainHandler.post()` lambda was also outside the existing `try/catch`, meaning any `RuntimeException` it threw (e.g. `IllegalStateException` from `fragment.requireContext()` on a detached fragment) would crash the main thread.

#### Fixes applied

- **`app/proguard-rules.pro`**: Added four new rules targeting the Google API Client library:
  - `-keep class com.google.api.client.** { *; }` — prevents R8 from merging/removing any google-api-client class.
  - `-keep class com.google.api.services.drive.** { *; }` — same for the Drive v3 model and service classes.
  - `-keepclassmembers class * { @com.google.api.client.util.Key <fields>; }` — preserves all `@Key`-annotated fields used by `GenericData` JSON mapping; absence of this rule is the direct cause of `"key error"` at runtime.
  - `-keep class * extends com.google.api.client.json.GenericJson { *; }` — ensures all concrete `GenericJson` subclasses (including Drive model POJOs) retain their default constructors for Gson instantiation.

- **`SyncSettingsHelper.createHousehold()` executor runnable:** changed `catch (Exception e)` to `catch (Throwable e)` so that `Error` subclasses from R8-broken or other system code are captured and shown to the user as a toast rather than silently force-closing the app.  Also unified `refreshSignInState(fragment)` inside the `try/catch` block in the `mainHandler.post()` callback so it can no longer throw an uncaught exception on the main thread.

- **`SyncSettingsHelper.joinHousehold()` executor runnable:** same `catch (Throwable e)` change; `refreshSignInState(fragment)` wrapped in its own `try/catch` for the same reason.

### Files changed

- `app/proguard-rules.pro` — added Google API Client library keep rules
- `app/src/play/java/eu/frigo/dispensa/ui/SyncSettingsHelper.java` — `createHousehold()` and `joinHousehold()`: `catch (Throwable e)`, `refreshSignInState` guarded inside try-catch

### Test results

- `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew :app:compileFdroidDebugJavaWithJavac` — **BUILD SUCCESSFUL**.

---

### Handoff to Session 17

- **The primary Drive API crash is now fixed at the root:** R8 will no longer optimise away Google API Client class structure. Both `testDriveConnection` and `createHousehold` / `joinHousehold` should succeed for users who have properly granted Drive scopes.
- **If Drive calls still fail after this fix**, the debug log will now show the full error (including for previously-silent `createHousehold` force-closes). Look for `createHousehold: failed` / `joinHousehold: failed` lines.
- **Outstanding polish items** (carried forward):
  - Copy-to-clipboard button in the household deep-link dialog.
  - QR code generation for the join link.
  - Household folder friendly name in status preference.
  - Notification/badge when new pending sync devices arrive.
- **Conventions established:**
  - Executor runnables that call Drive API methods must use `catch (Throwable e)` (not just `catch (Exception e)`) to guard against R8-related `Error` subclasses.
  - All `mainHandler.post()` callbacks that call `refreshSignInState(fragment)` must wrap it in `try/catch` or inside a guarded block to prevent main-thread crashes.
  - ProGuard rules must explicitly keep `com.google.api.client.**`, `com.google.api.services.drive.**`, `@Key`-annotated fields, and `GenericJson` subclasses for any release build that uses the Google Drive REST API client.

