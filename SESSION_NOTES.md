# SESSION_NOTES.md — Dispensa App: CRDT Sync

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
