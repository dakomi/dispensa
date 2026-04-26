# PLAN.md — Dispensa App

## Project Overview

**Dispensa** is an Android pantry management application that helps users track what they have at home, avoid food waste, and plan purchases better. It integrates with the Open Food Facts API to auto-populate product information from barcodes and supports customizable storage locations with expiry-date notifications.

- **Package:** `eu.frigo.dispensa`
- **Languages:** Java (primary), Kotlin (build scripts)
- **Min SDK:** 26 (Android 8.0) | **Target SDK:** 35
- **Distribution:** Play Store + F-Droid (separate product flavors)
- **Current version:** 0.1.9 (versionCode 19)

---

## Architecture Decisions

| Area | Choice | Rationale |
|---|---|---|
| Language | Java | Existing codebase; consistent with all current source files |
| Architecture | MVVM (ViewModel + Repository + Room) | Standard Android pattern; already in use |
| Local DB | Room (SQLite) | Type-safe queries, LiveData/RxJava integration |
| Networking | Retrofit + Gson/Moshi | Already integrated for Open Food Facts API |
| Navigation | Jetpack Navigation Component | Already wired for fragment navigation |
| Background work | WorkManager | Reliable scheduling for expiry notifications |
| Barcode scanning | ZXing (F-Droid) / ML Kit (Play) | Different flavors for open-source vs Play compliance |
| Image loading | Glide | Lightweight, already a dependency |
| Testing | JUnit 4 + Espresso | Standard Android test stack already in gradle |

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                             │
│  MainActivity ──► ProductListFragment ──► AddProduct*       │
│                ──► ManageLocationsFragment                  │
│                ──► SettingsFragment                         │
└────────────────────────┬────────────────────────────────────┘
                         │ observes LiveData / calls methods
┌────────────────────────▼────────────────────────────────────┐
│                    ViewModel Layer                          │
│  ProductViewModel │ AddProductViewModel │ LocationViewModel  │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                   Repository Layer                          │
│  Repository ──► AppDatabase (Room)                          │
│             ──► OpenFoodFactCacheManager                    │
│             ──► BackupManager                               │
└──────┬────────────────────────────┬──────────────────────────┘
       │                            │
┌──────▼──────┐            ┌────────▼────────────────────────┐
│  Room DAOs  │            │  Retrofit / OpenFoodFacts API   │
│  Product    │            │  (network → cache → UI)         │
│  Location   │            └─────────────────────────────────┘
│  Category   │
│  OFFCache   │
└─────────────┘

Background:
WorkManager ──► ExpiryCheckWorker ──► NotificationManager
```

---

## Phased Session Plan

### Session 1 — Bootstrap & Planning ✅

**Goal:** Set up multi-session tracking infrastructure and document the existing codebase.

- [x] Create `.github/copilot-instructions.md`
- [x] Create `PLAN.md` with architecture overview and session plan
- [x] Create `SESSION_NOTES.md` with Session 1 section
- [x] Document existing project structure and architecture

**Tests:** No code changes — no tests required.

---

### Session 2 — Code Quality & Unit Tests

**Goal:** Add unit tests for core utility and data classes that currently have no test coverage.

- [ ] Write unit tests for `ExpiryDateParser` (date parsing edge cases)
- [ ] Write unit tests for `DateConverter` (Room type converter)
- [ ] Write unit tests for `BackupManager` serialization/deserialization
- [ ] Fix any lint warnings in modified files
- [ ] Ensure `./gradlew testFdroidDebugUnitTest` passes

**Tests:** JUnit 4 tests in `app/src/test/`.

---

### Session 3 — Product Search & Filtering

**Goal:** Improve product discovery with in-app search and category filtering.

- [ ] Add a search bar to `ProductListFragment` that filters by product name
- [ ] Add category filter chips above the product list
- [ ] Update `ProductDao` with query methods for name search and category filter
- [ ] Update `ProductViewModel` to expose filtered `LiveData`
- [ ] Add UI tests for search and filter interactions

**Tests:** Espresso UI tests in `app/src/androidTest/`.

---

### Session 4 — Expiry Notification Improvements

**Goal:** Make expiry notifications more actionable and configurable.

- [ ] Allow users to set per-product notification lead time (e.g., 3 days before expiry)
- [ ] Add a "Products expiring soon" summary screen
- [ ] Update `ExpiryCheckWorker` to respect per-product settings
- [ ] Add setting to enable/disable notifications globally (already partly in Settings)
- [ ] Write unit tests for the updated worker logic

**Tests:** JUnit 4 for worker logic; manual verification for notifications.

---

### Session 5 — Backup & Restore UX

**Goal:** Make backup and restore more discoverable and reliable.

- [ ] Add explicit "Backup now" and "Restore" buttons to Settings
- [ ] Show last backup timestamp in Settings
- [ ] Validate backup file format before restoring (prevent corrupt restore)
- [ ] Write unit tests for backup validation logic
- [ ] Add error snackbar feedback on backup/restore failure

**Tests:** JUnit 4 for validation; manual verification for file picker flow.

---

### Session 6 — Accessibility & Polish

**Goal:** Improve accessibility and visual consistency across the app.

- [ ] Add content descriptions to all icon-only buttons
- [ ] Ensure minimum touch target size (48 dp) for all interactive elements
- [ ] Verify high-contrast / large-font usability
- [ ] Review and update string resources for completeness (en + it)
- [ ] Run Android lint and fix all accessibility-related warnings

**Tests:** Automated lint pass (`./gradlew lint`).
