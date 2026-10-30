# S3-05 — BQ3 Recipes on Android: Local Catalog, Caching and Concurrency

| Field | Value |
|---|---|
| Issue | S3-05 (Android issue #18) |
| Business question | **BQ3:** given the current user's available ingredients, dietary preferences and maximum preparation time, which recipes satisfy all three conditions? |
| Owner | Juan Pablo (`bedoya0611`) |
| Reviewer | Natalia (`NataliaEspitia`). **Review pending.** |
| Depends on | S3-02 (session), S3-03 (complete inventory), S3-04 (backend catalog, deployed and seeded), S3-09 (durable outbox, integrated; see §8) |
| Status | S3-05 merged in `main`. The S3-09 integration (§8, §9.4) is implemented locally on `main` at `ab3adbd`, **uncommitted**. |

`<pkg>` stands for `android/app/src/main/java/com/campusmeal/android`. Backend contract and cases: `CampusMealBack/docs/SPRINT3_BQ3.md`.

## 1. Delivered flow

1. **Recipes** opens from Home ("Recipes you can cook") and from Inventory (top-bar action "Recipes"). It is a pushed screen; the bottom tabs and the BQ5 Cook/Walk/Order flow are unchanged.
2. The screen renders **Room first**: the stored catalog and the account's S3-03 complete inventory are observed independently. Each input refreshes in the background when due: catalog after 24 h, inventory after 5 min, invalidation or a new Bogotá date.
3. **Filters:**
   - dietary multi-selection uses the shared `DietaryPreference` (Vegetarian, Vegan, Gluten-free);
   - the maximum preparation time uses a 5–180 min slider in steps of 5;
   - they are restored from `recipes.lastFilters.{accountId}`;
   - otherwise the diet comes from the last validated meal context (memory only), with 30 min and no restriction as defaults.
4. **List:** matching recipes in BQ3 order, each with:
   - its required ingredients and the converted quantity available versus needed;
   - the earliest expiry it relies on;
   - prep time, servings and tags.

   A valid "no match" explains the limiting conditions (missing ingredient, too little, unit not comparable, diet, time).
5. **Detail** (shares the visit's ViewModel):
   - prep time, servings and tags;
   - every ingredient with its quantity, marked "optional" where it applies, and its availability;
   - the match verdict with reasons;
   - the complete ordered steps.

   It resolves the id against the **current** catalog: a recipe removed by a refresh shows "Recipe no longer available".

## 2. Paths

| Layer | Path |
|---|---|
| API and DTOs (`GET recipes`, no query) | `<pkg>/feature/recipes/data/remote/RecipeApi.kt` |
| Contract validation | `<pkg>/feature/recipes/data/mapper/RecipeCatalogMapper.kt` |
| Domain models (`Recipe`, `RecipeIngredient`, `RecipeUnit`, `RecipeCatalog`, `RecipeCatalogSnapshot`) | `<pkg>/feature/recipes/domain/model/Recipe.kt` |
| Pure BQ3 evaluator | `<pkg>/feature/recipes/domain/RecipeEvaluator.kt` (`evaluateBq3`, `normalizeIngredientName`) |
| Repository contract and results | `<pkg>/feature/recipes/domain/RecipeRepository.kt` |
| Repository | `<pkg>/feature/recipes/data/repository/OfflineFirstRecipeRepository.kt` |
| Room entities and DAO | `<pkg>/feature/recipes/data/local/RecipeCatalogEntities.kt` |
| Room store | `<pkg>/feature/recipes/data/local/RoomRecipeCatalogStore.kt` |
| Filters (DataStore) | `<pkg>/feature/recipes/data/local/RecipeFilterStore.kt` |
| Usage admission hook | `<pkg>/feature/recipes/domain/RecipeUsageRecorder.kt` |
| ViewModel (one per visit) | `<pkg>/feature/recipes/presentation/RecipesViewModel.kt` |
| Screens | `<pkg>/feature/recipes/presentation/RecipesScreen.kt` |
| Migration v4 → v5 | `<pkg>/core/database/DatabaseMigrations.kt` (`MIGRATION_4_5`), `CampusMealDatabase.kt` |
| Exported schema | `android/app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/5.json` (generated, uncommitted) |
| DI | `<pkg>/app/AppContainer.kt`: `recipeRepository`, `recipeFilterStore` (registered in `AccountDataCleaner`), `recipeUsageRecorder` |
| Navigation | `<pkg>/navigation/CampusMealRoute.kt` (`Recipes`, `RecipeDetail(recipeId)`), `CampusMealNavHost.kt` |
| Entry points | `<pkg>/feature/home/HomeScreen.kt`, `<pkg>/feature/inventory/presentation/InventoryScreen.kt` |

## 3. Catalog contract, storage and freshness

- **Validation** of the whole response happens before anything stored is touched. The catalog is rejected (`InvalidResponse(reason)`) and the stored one kept when any of these fails:
  - `contractVersion` must be exactly 1;
  - `catalogVersion` must be non-blank and at most 40 characters;
  - `generatedAt` must be a strict UTC instant ending in `Z`;
  - 0–200 recipes with unique UUIDs;
  - name 1–150 characters; `preparationMinutes` 1–240; `servings` 1–20;
  - tags only from `DietaryPreference`, without repeats;
  - 1–20 ingredients, at least one required;
  - non-blank names; `normalizedName` and aliases already normalized; matching keys must not be shared between ingredients of one recipe;
  - finite quantity > 0; exact unit `g`/`kg`/`ml`/`L`/`unit`;
  - 1–15 non-blank steps.

  A missing field, including a missing `recipes` array, fails decoding; `aliases` alone defaults to `[]`. `recipes: []` is a valid empty catalog. Broken recipes are never dropped silently.
- **Room v5.** Two tables, replaced in **one transaction** (`RecipeCatalogDao.replace`), so there is never a mix of versions and recipes missing from the new version are removed with it:
  - `recipe_catalog_metadata` is a single row: `contractVersion`, `catalogVersion`, `generatedAt` as received, `contentHash` (SHA-256 of the validated response) and the local `fetchedAtEpochMillis`. It also exists for an empty catalog.
  - `recipe_catalog_recipes` has one row per recipe: `recipeId`, `catalogVersion`, `position` (backend order) and the validated `recipeJson`.

  Stored rows are re-validated when read; JSON decoding runs on `Dispatchers.Default`.
- **Same version.**
  - Identical hash: only `fetchedAt` is renewed (`Unchanged`).
  - Different content or `generatedAt` under the same version: `SameVersionChanged`, a contract inconsistency. The stored catalog is kept and no version is manufactured.
  - New version: `Replaced`.
- **Times.** `generatedAt` is when the catalog author produced the version; it is shown alongside the version and never drives the TTL. `fetchedAt` is the local fetch time and drives `CachePolicy.recipeCatalog` (24 h). Inventory uses its own `syncedAt`, invalidation and `revision` from S3-03 with a 5-min TTL. The screen shows both independently, and a fresh catalog never certifies the inventory.
- **Global data.** The catalog is not an `AccountScopedStore` and survives logout (plan §7.4). Only an identified session reaches the screens, and every repository call takes the session scope:
  - credentials come from that scope only;
  - commits go through `coordinator.runIfCurrent`;
  - streams emit only while the scope is current.
- **Errors keep data.** Offline/timeout (`Unreachable`), 503 (`NotAvailableYet`), other 5xx, other HTTP, undecodable or invalid responses all keep the stored catalog and its original `fetchedAt`. A 401 follows S3-02 and never deletes the catalog. An error is never shown as an empty catalog.

## 4. Evaluator rules (pure Kotlin)

`evaluateBq3(recipes, inventory, selectedDiet, maxPreparationMinutes, todayIsoDate, checkCancelled)` has no Android, Room, network or clock dependency.

1. **Input:** the S3-03 complete snapshot only, never the BQ2 subset, drafts or a single write's item. Domain rows are active by contract: the production mapping `toCompleteInventoryOrNull` drops `active: false` rows, which is checked in C10.
2. **Usable rows:** quantity finite and > 0, and a valid `expirationDate` on or after today (Bogotá, inclusive), by calendar date (`BogotaCalendar`). The stored `remainingDays` is ignored, and BQ2 is untouched.
3. **Names:** NFD, strip U+0300–U+036F, `lowercase(Locale.ROOT)`, trim, collapse whitespace. This is the same function as the backend. A name matches only if it equals `normalizedName` or an alias exactly: no fuzzy, plural, substring or substitution matching.
4. **Units:** lowercase and trim, then this table:
   - `g`: g, gr, gram, grams
   - `kg`: kg, kilo, kilos
   - `ml`: ml
   - `L`: l, lt, liter, litre, liters
   - `unit`: unit, units, u, und, unidad, unidades

   Conversion stays within one dimension: mass (1 kg = 1000 g), volume (1 L = 1000 ml), count. Unknown units (cups, loaf) and other dimensions never contribute.
5. **Quantity:** the sum of all usable comparable rows with a matching name must satisfy `total + 1e-9 ≥ required`. A non-comparable row never cancels comparable rows that already suffice. Diagnostics distinguish `MISSING`, `INSUFFICIENT` and `UNIT_NOT_COMPARABLE`.
6. **Optional ingredients** never block a match and never change tags; the detail labels them "optional".
7. **Diet:** the recipe's tags contain every selected preference; an empty selection means no restriction.
8. **Time:** `preparationMinutes ≤ maximum`, with the UI range 5–180. This is not BQ5's total time and not servings.
9. **Independence:** each recipe is evaluated against the full inventory; nothing is consumed.
10. **Order:** earliest expiry among usable comparable rows matching a **required** ingredient, then `preparationMinutes`, then name, then UUID. The last two use code-point `String` comparison, not a locale collator. Optional-only rows do not affect priority.

## 5. Concurrency, memo and invalidation

- `RecipesViewModel` combines four inputs: catalog (Room), complete inventory (Room), filters and today's Bogotá date. It keys them with `EvaluationKey`:
  - account id and session generation;
  - inventory `revision` (sync and invalidation instants);
  - contract and catalog version;
  - sorted diet and maximum minutes;
  - Bogotá date.
- `distinctUntilChangedBy(key)` → `mapLatest`: a new key cancels the running evaluation. The evaluator runs in `withContext(Dispatchers.Default)` and calls `checkCancelled` (`job.ensureActive()`) inside its loops, so abandoned work stops. A result is shown only when its key equals the key of the **current** inputs, so a delayed older result can never overwrite newer filters.
- **Memo:** an access-ordered `LinkedHashMap` holding at most **5 keys**, inside the ViewModel. The session graph is keyed by scope (S3-02), so a scope change or logout discards it, and a new generation never reuses a result. Source labels come from the live inputs, so an unchanged memoized result still shows a newly updated sync time.
- **Invalidation sources:**
  - inventory sync or a confirmed write (S3-03 invalidation changes `revision`; no second mutation pipeline);
  - catalog replacement;
  - diet or time change;
  - Bogotá midnight: one timer plus every return to the foreground, the same approach as S3-03.
- **Refresh:**
  - Each input has its own job and status. Entry or return refreshes what is due, *Refresh* forces both in parallel (observed 1 ms apart on the device), and reconnect retries a failed input once.
  - The repository coalesces overlapping catalog refreshes behind one `Mutex`: a waiter is skipped when a fetch started after its request already succeeded.
  - HTTP never runs under the session coordinator lock.
  - No polling, WorkManager, ETag or backend filtering.

## 6. States

| State | When | Shown |
|---|---|---|
| Loading | Room or the saved filters have not answered (milliseconds) | "Checking recipes…" |
| NoLocalData | Catalog metadata and/or complete-inventory snapshot never stored | "This device does not have the recipe catalog / your complete inventory (⟨last refresh problem⟩). Connect and try again", with *Try again*. A valid empty inventory or empty catalog is **not** NoLocalData. |
| Results | Both inputs present | "N recipes match" with cards. Zero matches gives a valid empty answer with its reasons. An empty catalog shows "The recipe catalog has no recipes yet." |
| Older data | Either input past its TTL, invalidated, from an earlier Bogotá date, or failed to refresh | "Based on data as of the times below." Each source line shows its own time and its own problem ("couldn't refresh (offline or unreachable)", "may be out of date", "your last change is not reflected yet", "older than 24 h") |
| Refreshing | A refresh is running | Results stay visible with "Updating from CampusMeal…" plus the shared slow-response notice. No fabricated progress. |
| Session | Unauthorized, expired or not identified | The existing S3-02 flow; the graph of another scope is never composed |

There is no bundled or hard-coded catalog: without a stored catalog the screen says what is missing.

## 7. Filters and personal data

- `recipes.lastFilters.{accountId}` (DataStore) stores `{"dietaryPreferences":[…],"maxPreparationMinutes":N}`, nothing else and no coordinates. It is written about 500 ms after the last change, only inside `runIfCurrent(scope)`. Values outside 5–180 are never stored, restored or evaluated.
- `RecipeFilterStore` is an `AccountScopedStore`: logout deletes the account's key, and activating another account deletes all other accounts' keys. Observed on the device: the key was gone after logout (§9.3, R-08).
- Results, memo and visit state live only in the ViewModel's memory.

## 8. FEATURE_USED and S3-09

The BQ3 usage event goes through the S3-09 durable outbox (`<pkg>/core/outbox/`, `docs/SPRINT3_EVENT_SYNC.md`):

- **Admission point.** `RecipeUsageRecorder` (`<pkg>/feature/recipes/domain/RecipeUsageRecorder.kt`) is bound in `AppContainer` to `OutboxRecipeUsageRecorder` (`<pkg>/feature/recipes/data/usage/OutboxRecipeUsageRecorder.kt`, added with S3-09). The adapter calls `OutboxRepository.enqueueUsage(scope, UsageFeature.RECIPES, visitId, occurredAt)` and maps `OutboxAdmission` to `UsageAdmission.Admitted`/`Rejected`. The provisional `OutboxNotIntegratedUsageRecorder` and `UsageAdmission.NotIntegrated` were removed.
- **Payload.** `{ clientEventId: visitId, eventType: FEATURE_USED, feature: RECIPES, platform: ANDROID, occurredAt }`. `occurredAt` is the UTC instant the content was shown, kept when delivery happens later. No coordinates. The optional BQ9 fields (`zoneId`, `foodCategory`, `budgetBand`) are not sent; the backend DTO marks them `@IsOptional`.
- **One event per visit.**
  - The visit id is a UUID v4 kept in `SavedStateHandle`, so it survives configuration changes and process recreation.
  - "Offered" is set once per visit, when the main content (results or a valid empty answer, offline included) is displayed. Recomposition, filter edits, refreshes, retries, detail navigation and returning from the background never repeat it.
  - The outbox inserts with `OnConflictStrategy.IGNORE` on `clientEventId`, so even a second admission of the same visit stores one row.
- **Durability.**
  - The admission is a short local Room write inside the session coordinator lock, only while the scope is active, with at most 1,000 pending rows per account.
  - It runs under `NonCancellable`, so leaving the screen right after it appears does not lose the visit.
  - `Admitted` is returned only after the insertion. The account's WorkManager chain then delivers it with that account's credentials; the backend deduplicates by `clientEventId`.
  - `Rejected` (session changed, outbox full) is logged (`CampusMealUsage`), never retried by the feature and never shown as recorded.
- There is no direct HTTP call from the feature, no in-memory queue and no use of `NoOpAnalyticsTracker`. BQ8 recommendation events are untouched.

## 9. Verification

Source: `sprint3-issue-5` at `08852b2`, dirty (uncommitted S3-05 changes). APK SHA-256: `80758e4487abf57031445d35dc25870623158dd6f40f5e73a348d59a9d223e8a`.

Device: Samsung SM-S948U1, Android 17, `adb install -r` over the S3-03 debug install (data, session and signature kept). Network: cellular (`MOBILE[LTE]`, `wifi_on=0`, no VPN, tethering, `adb reverse` or proxy), except where airplane mode is stated. API: `https://campusmeal-api.onrender.com/api/v1/`; `GET recipes` returned the deployed, seeded catalog `2026-10-27.1`. Evidence is focused UI text dumps and OkHttp request/status lines, with headers redacted and no bodies.

### 9.1 Build

| Check | Result |
|---|---|
| `.\gradlew.bat "-Pcampusmeal.apiBaseUrl=https://campusmeal-api.onrender.com/api/v1/" :app:assembleDebug` (`CAMPUSMEAL_API_BASE_URL` unset; `BuildConfig.API_BASE_URL` = that HTTPS URL) | **PASS**, no Kotlin warnings |
| `:app:lintDebug` | **PASS**: 0 errors, 17 warnings, the same set as before S3-05 (dependency/target notices and 3 `UseKtx` in `KeystoreSessionStorage`). None in S3-05 code. |
| Shared network policy | Unchanged: 15/90/10/120 s, mutation client without transparent retries, redacted logging (S3-03) |
| Migration SQL vs `5.json`: host SQLite built from `4.json` with a profile, an inventory row, a draft and metadata, then the `MIGRATION_4_5` statements | **PASS**: 8 tables match and all rows kept (host check; the device upgrade is R-01) |

### 9.2 Reference cases C01–C21 (actual Kotlin)

A temporary JVM harness, deleted afterwards, loaded the **compiled app classes**. It decoded the backend's authored catalog (`CampusMealBack/src/recipes/demo-recipe-catalog.ts`, version `2026-10-27.1`) through `RecipeCatalogResponseDto` + `validate()`, and passed every inventory row through the production `toCompleteInventoryOrNull`. It then ran `evaluateBq3` with `today = 2026-10-27`, maximum 180 and no diet unless stated. Keys: SR `d3dd01dc-1a0b-463b-a917-d938f02c5dbb`, MR `6fa3803c-e8c1-47eb-b9e1-78c9f919d445`, SO `acc8498d-c078-42b9-980a-ab8a10e4755f`, TC `3e3b73ca-137d-416c-a14a-0b195e46bc69`, CS `ec3a038a-c0b6-4455-a88c-d8ba974ec718`, CR `3c11947c-d305-4a56-96bd-f319642d3f84`.

| Case | Expected | Actual match ids (order) | Checked reason | Result |
|---|---|---|---|---|
| C01 | MR, SR | `6fa3803c…`, `d3dd01dc…` | — | **PASS** |
| C02 | [] | [] | — | **PASS** |
| C03 | [] | [] | SR rice `INSUFFICIENT` 149 < 150 | **PASS** |
| C04 | SR | `d3dd01dc…` | — | **PASS** |
| C05 | SR | `d3dd01dc…` | — | **PASS** |
| C06 | MR | `6fa3803c…` | — | **PASS** |
| C07 | [] | [] | MR milk and SR spinach `UNIT_NOT_COMPARABLE`; SO egg `MISSING` | **PASS** |
| C08 | SR | `d3dd01dc…` | — | **PASS** |
| C09 | [] | [] | SR rice `MISSING` (no fuzzy) | **PASS** |
| C10 | [] | [] | Production mapper dropped the `active: false` row (3 kept); SR rice `MISSING` | **PASS** |
| C11 | SR | `d3dd01dc…` | — | **PASS** |
| C12 | CR | `3c11947c…` | — | **PASS** |
| C13 | [] | [] | CR `dietOk = false` | **PASS** |
| C14 | SR | `d3dd01dc…` | MR `dietOk = false` | **PASS** |
| C15 | MR, SR | `6fa3803c…`, `d3dd01dc…` | — | **PASS** |
| C16 | SR | `d3dd01dc…` | — | **PASS** |
| C17 | [] | [] | SR `timeOk = false` | **PASS** |
| C18 | SO | `acc8498d…` | cheese (optional) `MISSING`, not blocking | **PASS** |
| C19 | [] | [] | valid empty | **PASS** |
| C20 | TC, SO, SR, CS | `3e3b73ca…`, `acc8498d…`, `d3dd01dc…`, `ec3a038a…` | — | **PASS** |
| C21 | SR, SO | `d3dd01dc…`, `acc8498d…` | SO priority 2026-11-10 (optional cheese ignored) | **PASS** |

Also passing:
- cooperative cancellation: the evaluator stopped when `checkCancelled` threw;
- validator rejections: unknown `contractVersion` 2, unit `l`, `generatedAt` without `Z`, duplicate id, ambiguous keys, non-normalized alias, zero quantity, missing `recipes` array (decoding fails);
- `recipes: []` accepted as a valid empty catalog.

**Harness total: 44 PASS, 0 FAIL.**

### 9.3 Device scenarios

| ID | Scenario | Observed | Result |
|---|---|---|---|
| R-01 | Upgrade with data | Before: `user_version` 4; profile 1, inventory 2, BQ2 2, metadata 2, draft "S305 Draft" (`NONE`, quantity "2"). After `install -r`: `user_version` **5**, all of them kept, catalog tables empty, session kept, no crash. | **PASS** |
| R-02 | Open from Home; real catalog; both times | `GET recipes` 200 (260 ms), catalog `2026-10-27.1`. Inventory was fresh, so not refetched. "Inventory updated Oct 28, 20:29" and "Catalog 2026-10-27.1 · fetched Oct 28, 20:30" shown separately. Valid empty: "0 recipes match … 6 miss an ingredient; 2 need more…". | **PASS** |
| R-03 | Open from Inventory; item beyond 3 days participates | Temporary *Arroz* 1 kg (expires 2026-12-15) and *Espinaca* 100 g. Result: **Milk rice, Spinach rice**, with Milk rice first (earliest required item: the personal milk, Oct 29). "Rice (uncooked): you have 1000 g (needs 100 g)". The detail showed ingredients, availability and all 3 steps. No new requests (both inputs fresh). | **PASS** |
| R-04 | Rapid filter changes and persistence | Vegan, then 8 slider taps in one shell command (180→…→20). The final UI showed 20 min and only Spinach rice, consistent with the last input. After force-stop and reopen: 20 min and Vegan restored; DataStore held exactly `{"dietaryPreferences":["VEGAN"],"maxPreparationMinutes":20}`. | **PASS** |
| R-05 | Offline after sync (airplane mode, force-stop, reopen) | List and detail were readable from Room with the correct Bogotá eligibility. *Refresh* sent `GET recipes` and `GET inventory` in parallel (1 ms apart); both failed. "Based on data as of the times below", each line with its own time and "couldn't refresh (offline or unreachable)". The catalog kept `fetched Oct 28, 20:30`. | **PASS** |
| R-06 | Reconnect; no timestamp borrowing | Back on cellular: only the stale inventory was refreshed (401, `auth/refresh` 200, `GET inventory` 200), giving "Inventory updated 20:44". The catalog stayed "fetched 20:30" (fresh, not requested). | **PASS** |
| R-07 | Edit/consume an ingredient: revision and recompute | Edited *Arroz* 1 kg → 0.1 kg (`PATCH` 200 + `GET`). Recipes recomputed: with Vegan/20 min, 0 matches (Spinach rice needs 150 g; "need more"); with no diet/30 min, **Milk rice** with "you have 100 g (needs 100 g)", so equality held through the kg→g conversion. After consuming both temporary items: 0 matches, inventory time 20:47. | **PASS** |
| R-08 | Logout | Account rows all 0; `recipes.lastFilters.*` removed; catalog kept (1 metadata row, 6 recipes) as global data | **PASS** |
| R-09 | FEATURE_USED visits (before S3-09) | One `CampusMealUsage … not integrated` line per visit; 0 extra lines for detail opening, filter changes or refresh. | **PASS** at the time; replaced by the durable checks in §9.4 |
| R-10 | Missing catalog, missing inventory, valid empty catalog, `503`, malformed or unknown version, version replacement/recipe removal on the device | These states cannot be created safely on the shared backend, and no Android-facing stub was used. Validation and replacement rules are covered by §9.2 and the code paths in §3; the device showed only valid states. | **NOT RUN** on device |
| R-11 | Account switch with a late result | Needs a second account. Covered by construction: graph keyed by scope, scope-checked commits and publication, memo inside the ViewModel. | **NOT RUN** |
| R-12 | Bogotá midnight | Phone clock not changed. The evaluator takes `todayIsoDate` explicitly (C10/C11 inclusivity), and the date ticker is shared with S3-03 (whose host check covered midnight). | **NOT RUN** on device |

### 9.4 Residual data and cleanup

- Two temporary inventory items (*Arroz*, *Espinaca*) were created on the existing test account, edited, then consumed: they are inactive rows on the backend.
- The pre-upgrade draft "S305 Draft" was local only and was discarded.
- The personal items *milk* and *tomatoes* were not modified.
- No synthetic accounts were created.
- The test account is **signed out** on the phone after R-08; sign in privately when needed.
- The catalog stays on the device as global data.
- Temporary harness files, device UI dumps and database copies were deleted.

### 9.4 S3-09 integration (2026-10-30)

Source: `main` at `ab3adbd` plus the uncommitted integration changes. APK SHA-256: `78010704ddc72e08008d684ec19f69d5b347efade62a224bda5c8dd75d820695`.

Build and network:
- Build: `assembleDebug` with the Render URL passes, with no Kotlin warnings.
- Lint: 0 errors, 18 warnings. The extra one versus §9.1 is a `UseKtx` in `RestaurantDetailScreen.kt`, which came from the team's `main`, not from this change.
- Device: SM-S948U1, Android 17, cellular (`MOBILE[LTE]`, `wifi_on=0`), Room v7.

The phone had the S3-13 release build (signed with the team release key). The user uninstalled it, the debug APK was installed for user 0 only, and the user signed in privately. Evidence comes from `outbox_events` (event ids, type, feature, `occurredAt`, status only) and OkHttp request/status lines.

| ID | Check | Observed | Result |
|---|---|---|---|
| U-01 | Online visit (Home) | Content shown → "admitted to the outbox" 53 ms later → `POST analytics/usage-events` **202** → row `9feb4525… FEATURE_USED RECIPES SENT`, `attempts=1` | **PASS** |
| U-02 | Same visit: diet chips ×2, slider ×3, *Refresh*, background and resume | Still **1** RECIPES row; no new admission | **PASS** |
| U-03 | New visit (from Inventory) | Exactly one new row `96b7a47a…`, delivered with 202 | **PASS** |
| U-04 | Offline visit (airplane mode, force-stop, reopen) | Results evaluated from Room; row `bc23914a…` admitted as `PENDING`; still `PENDING` after another force-stop | **PASS** |
| U-05 | Delivery after reconnecting | Cellular back and app reopened: `bc23914a…` became `SENT` (`attempts=1`), keeping `occurredAt 2026-10-30T12:37:32Z` (the offline time) | **PASS** |
| U-06 | Rejected admission (outbox full or session ended during admission) | Not reproducible safely on the device; covered by the `OutboxRepository.admit` rules and the `Rejected` mapping | **NOT RUN** |

Result: 3 visits and 3 `FEATURE_USED(RECIPES)` events, all delivered. The app's `SESSION_STARTED` events were also delivered; they belong to S3-09. These are real test interactions of the test account on the deployed backend.

## 10. The four strategies

| Strategy | Feature work (S3-05) | Reused shared infrastructure |
|---|---|---|
| **Local storage** | Room v5 `recipe_catalog_metadata` + `recipe_catalog_recipes` with atomic replacement and re-validation on read (`RoomRecipeCatalogStore`); per-account filters in DataStore (`RecipeFilterStore`) | S3-03 `inventory_items` and its metadata (`observeCompleteInventory`); S3-02 `AccountScopedStore` cleanup |
| **Caching** | 24-h catalog TTL driven by the local `fetchedAt` (never `generatedAt`); same-version hash check; 5-key BQ3 memo; independent source labels | `CachePolicy`, `Freshness`, S3-03 5-min inventory TTL with invalidation and `revision` |
| **Concurrency** | `mapLatest` + key check + cooperative cancellation on `Dispatchers.Default`; catalog `Mutex` with coalescing; two independent refresh jobs in parallel | S3-03 per-account inventory `Mutex`; S3-02 `runIfCurrent` and the scope-keyed graph |
| **Eventual connectivity** | Offline evaluation from stored inputs with "Based on data as of…"; NoLocalData naming the missing input; reconnect retry once | `ConnectivityObserver`, `ReconnectEffect`, shared 90/120-s timeouts and the slow-response notice |

## 11. Not claimed

Not claimed:
- R-10–R-12 below remain without device evidence;
- device evidence for R-10–R-12;
- Natalia's review;
- any deployment: nothing was deployed, and no remote database was seeded or migrated.
