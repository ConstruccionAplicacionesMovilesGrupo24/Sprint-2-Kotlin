# CampusMeal Sprint 3 Plan (S3-01)

| Field | Value |
|---|---|
| Issue | S3-01 — Define Sprint 3 scope, contracts, and individual contribution evidence |
| Owner | Juan Pablo (`bedoya0611`) |
| Reviewer | Natalia (`NataliaEspitia`) — **review pending** |
| Android repository | `ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin` (the inspected local clone's `origin` still points to `ConstruccionAplicacionesMovilesGrupo24/Sprint-2.git`) |
| Backend repository | `ConstruccionAplicacionesMovilesGrupo24/CampusMealBack` |
| Baseline inspected | Android `main` at `74e17f1`, backend `main` at `443a9a2` (local clones, 2026-10-21) |
| Status | Planning document. Nothing marked **Planned** or **Proposed backend** exists yet. |
| MVP decisions updated | 2026-10-22 — agreed decisions on logout, session coordination, durable events, BQ9 categories, and routing scope. Human code review and implementation remain pending. |

**Local execution rule for Claude.** Claude may inspect the repositories and edit local files for the requested issue. It must leave changes uncommitted and must not push, create/open/update pull requests (including drafts), or merge branches or pull requests. The user handles commits, pushes, and pull requests. References to PRs and integration below describe the team's later workflow.

**Labels used in this document**

- **Implemented**: verified in source at the baseline commits.
- **Planned (S3-xx)**: agreed future Android behavior, owned by that issue.
- **Proposed backend (S3-xx)**: a new backend contract that does not exist in `CampusMealBack` yet.
- **Pending validation**: needs a human action that has not happened yet, such as a review, a physical-device check, Firebase distribution, or an evaluator check.

**Path conventions.** `<pkg>` stands for `android/app/src/main/java/com/campusmeal/android`, relative to the Android repository root. Paths prefixed with `CampusMealBack:` are relative to the backend repository root. Line numbers refer to the baseline commits.

---

## 1. Scope and Business Questions

### 1.1 Project

CampusMeal helps university students choose among three options: **cooking** with the ingredients they have, **walking** to a restaurant, or **ordering** delivery. The Android client uses Kotlin, Jetpack Compose, MVVM, repositories, and manual dependency injection (`<pkg>/app/AppContainer.kt`). Networking uses Retrofit and OkHttp. Local persistence uses Room and DataStore. Asynchronous work uses Kotlin coroutines. WorkManager is declared as a dependency. The app talks only to the shared NestJS and PostgreSQL backend, using JWT sessions. Sprint 3 keeps this architecture.

Sprint 3 makes the existing features reliable under eventual connectivity, adds three new business questions (BQ3, BQ9, BQ6), and produces evidence of each member's individual work.

### 1.2 Verified implementation baseline

All 13 findings from the earlier inspection were checked against the current source.

| # | Finding | Result | Evidence |
|---|---|---|---|
| B-01 | `OfflineFirstInventoryRepository` requests the network first. It falls back to Room only on a connectivity failure or HTTP 5xx. | Confirmed | `<pkg>/feature/inventory/data/repository/OfflineFirstInventoryRepository.kt` lines 34–53, 118–122 |
| B-02 | The inventory cache holds only the expiring-items response (`GET inventory/expiring?withinDays=3`), not the complete inventory. | Confirmed | `<pkg>/feature/inventory/data/local/RoomInventoryCache.kt` (`CACHE_KEY = "inventory.expiring"`); `<pkg>/feature/inventory/data/local/ExpiringInventoryEntity.kt`; `<pkg>/feature/inventory/data/remote/InventoryApi.kt` has no `GET inventory` |
| B-03 | Inventory create, edit, and consume call the backend directly, with no queue and no persisted draft. | Confirmed | `OfflineFirstInventoryRepository.kt` lines 55–104; `<pkg>/feature/inventory/domain/model/InventoryWrite.kt` |
| B-04 | `NetworkRestaurantRepository` stores one previous search in DataStore. | Confirmed | `<pkg>/feature/restaurants/data/RestaurantRepository.kt` lines 109–112, 121, 124 (`restaurants.lastSearch`) |
| B-05 | The restaurant cache is not keyed by search parameters. Offline, it is returned for any request. | Confirmed | `RestaurantRepository.kt` lines 92–98; the cached value contains only the response (lines 37–38) |
| B-06 | Restaurant details depend on the network. | Confirmed | `RestaurantRepository.kt` lines 70–90 |
| B-07 | `NetworkMealDecisionRepository` does not cache comparisons. | Confirmed | `<pkg>/feature/decision/data/repository/MealDecisionRepository.kt` lines 19–23 |
| B-08 | `RecommendationEventReporter` retries an event with the same UUID, but pending events exist only in memory. | Confirmed: three attempts (delays of 1 s and 3 s), retried only for network errors and 5xx. The "impression already sent" set and the pending selection live in `DecisionViewModel` memory. | `<pkg>/feature/decision/data/repository/RecommendationEventReporter.kt` lines 21–27, 33, 49–64; `<pkg>/feature/decision/presentation/DecisionViewModel.kt` lines 47–48 |
| B-09 | `NetworkAuthRepository` uses a `Mutex` to coordinate token refresh. | Confirmed. It also skips the refresh when another caller has already replaced the rejected token. | `<pkg>/feature/auth/data/NetworkAuthRepository.kt` lines 95–96, 171–193 |
| B-10 | `KeystoreSessionStorage` protects session tokens. | Confirmed: an AES-GCM key in Android Keystore, with ciphertext in the private SharedPreferences file `campusmeal_secure_session`. It is wired in `AppContainer.kt` lines 101–103. The `PENDING` comment at line 100 is stale. | `<pkg>/core/session/KeystoreSessionStorage.kt` |
| B-11 | `ProfileRepository` reads `GET auth/me` without persisting the profile. | Confirmed. The mapping also drops `id` (`CurrentUser` has no id), so no stable account identifier is kept anywhere on the device. | `<pkg>/feature/profile/data/ProfileRepository.kt` lines 26, 34–48 |
| B-12 | Home already runs some independent reads concurrently. | Confirmed: profile and expiring inventory use `async` inside one `viewModelScope.launch`. | `<pkg>/feature/home/HomeViewModel.kt` lines 55–73 |
| B-13 | WorkManager is declared, but no worker is implemented. | Confirmed: the dependency is declared, and no `Worker`/`CoroutineWorker` class or WorkManager call exists in `android/app/src`. | `android/app/build.gradle.kts` line 116; `android/gradle/libs.versions.toml` (`work = "2.11.2"`) |

**Additional findings and differences** (these matter for Sprint 3 and were not in the supplied baseline):

| # | Finding | Evidence | Addressed by |
|---|---|---|---|
| D-01 | When a refresh fails for a transient reason (offline or 5xx), the interceptor returns the original 401 to the caller. The inventory and restaurant repositories react to any 401 by **deleting their caches**. As a result, a transient refresh failure destroys offline data even though the session is kept. | `<pkg>/core/session/SessionRefreshInterceptor.kt` lines 70–81; `OfflineFirstInventoryRepository.kt` lines 40–43, 94–97; `RestaurantRepository.kt` lines 59–62 | S3-02 |
| D-02 | `refreshSession` invalidates the session for **every non-5xx HTTP error**, including 408 and 429, and for an undecodable response. These are transient conditions, not credential rejections. | `NetworkAuthRepository.kt` lines 239–255 | S3-02 |
| D-03 | The interceptor skips refresh for any path containing `/auth/`. That includes `GET auth/me`, so Profile and Home read `Unauthorized` after the 15-minute access token expires, with no refresh attempt. | `SessionRefreshInterceptor.kt` lines 42–45 | S3-02 |
| D-04 | `AuthRepository.expireSession()` is never called. Feature screens show "session expired" text on a 401 while `SessionStatus` stays `AUTHENTICATED`. | Search for `expireSession`: only defined in `NetworkAuthRepository.kt` line 259 | S3-02 |
| D-05 | Account isolation works by deletion. `onSessionEnded` clears the inventory and restaurant caches on logout, on invalidation, and before storing a new login. No local record carries an account identifier. | `AppContainer.kt` lines 107–117; `NetworkAuthRepository.kt` lines 166–167, 274–277, 301–302 | S3-02 |
| D-06 | Superseded work is never cancelled. `RestaurantResultsViewModel.load` and `DecisionViewModel.compare` launch a new coroutine on every call and do not cancel the previous one, so a slower earlier response can overwrite a newer one. | `<pkg>/feature/restaurants/presentation/RestaurantResultsViewModel.kt` lines 73–142; `DecisionViewModel.kt` lines 50–68 | S3-06, S3-11 |
| D-07 | The validated meal context exists only in a Compose `remember` in the navigation host and is lost on process death. Device coordinates are memory-only by design. | `<pkg>/navigation/CampusMealNavHost.kt` line 122; `<pkg>/feature/context/domain/MealLocation.kt` line 20 | S3-06 |
| D-08 | The backend already contains walking-time provider integration, including `ValhallaRouteAdapter` selected through `RoutesModule`. Android has no external maps-app handoff (`Intent`). Null route fields on the detail endpoint do not establish absence of backend search-route estimates. Provider configuration and live execution still need validation. | `CampusMealBack:src/routes/infrastructure/valhalla-route.adapter.ts`; `CampusMealBack:src/routes/routes.module.ts`; search of `<pkg>` for `Intent`/`startActivity`; `CampusMealBack:docs/API_CONTRACT.md` §8 | S3-06 preserves backend route estimates and handles failures. External maps-app handoff is outside the MVP. |
| D-09 | `AnalyticsTracker` is a `NoOpAnalyticsTracker`, so location-context events are discarded. | `AppContainer.kt` lines 121–123; `<pkg>/core/analytics/AnalyticsTracker.kt` | S3-09 (catalog events only) |
| D-10 | The Inventory screen loads only the expiring list, so items expiring after three days are never shown. The backend already provides `GET /api/v1/inventory` with all active items. | `<pkg>/feature/inventory/presentation/InventoryViewModel.kt` line 75; `CampusMealBack:docs/API_CONTRACT.md` §9 | S3-03 |
| D-11 | A cached *empty* restaurant result is shown as `NoResults`, with no offline/cached label, because emptiness is checked before the source. | `RestaurantResultsViewModel.kt` lines 90–95 | S3-06 |
| D-12 | Backend: `analytics_events` requires a non-null recommendation foreign key, so it cannot store BQ6/BQ9 usage events. There is no recipes module. Inventory DTOs expose no version or `updatedAt`, so conditional writes are impossible. The latest backend migration is `1790430000000-CreateAnalyticsEvents.ts`. | `CampusMealBack:src/analytics/entities/analytics-event.entity.ts`; `CampusMealBack:src/database/migrations/`; `CampusMealBack:docs/API_CONTRACT.md` §9 | S3-04, S3-07, S3-10 |
| D-13 | Two separate `DietaryPreference` enums exist with the same values. | `<pkg>/feature/context/domain/MealContext.kt` line 7; `<pkg>/feature/decision/domain/model/MealDecision.kt` line 6 | Reuse the context enum in S3-05 |

**Room schema at baseline:** version **2** (`<pkg>/core/database/CampusMealDatabase.kt` line 18). Entities: `CacheMetadataEntity` and `ExpiringInventoryEntity`. Migration: `MIGRATION_1_2` (`<pkg>/core/database/DatabaseMigrations.kt`). Exported schemas: `android/app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/1.json` and `2.json`.

### 1.3 Business questions retained from Sprint 2

The Sprint 2 behavior of these features is preserved. Sprint 3 adds only the connectivity improvements listed here. The owner column names the **Sprint 3 improvement owner**, not the original author.

| BQ | Type | Question | Sprint 2 state (Implemented) | Sprint 3 connectivity improvement | Improvement owner |
|---|---|---|---|---|---|
| BQ2 | 2 | Items expiring within three days and their consumption priority. | Backend list in priority order; Room cache of the last successful result; offline fallback. | The cache becomes account-scoped with a 5-minute TTL. The sync date is shown with the list (`remainingDays` is "as of" that date). The cache is no longer deleted on a transient refresh failure. | Juan Pablo (S3-02, S3-03) |
| BQ4 | 2 | Open restaurants compatible with location, available time, budget, and dietary preferences. | Backend search; one unkeyed DataStore cache entry; network-only details. | Context-keyed search cache (5 min) and detail cache (30 min). Cancellation of superseded searches. Old opening status, prices, and route estimates labelled historical. | Natalia (S3-06) |
| BQ5 | 2 | Cook/Walk/Order comparison using estimated time, cost, and expiration priority. | Backend comparison; no cache. | New comparisons still require the backend. Past comparisons are kept as read-only history, available offline. Superseded comparisons are cancelled. | Daniel (S3-11) |
| BQ8 | 3 | Selection rate by recommendation explanation. | `POST /api/v1/analytics/events` with stable `clientEventId`; in-memory retries. | Impressions and selections are written to a durable Room outbox before delivery and sent by WorkManager with the same UUID. The endpoint and its recommendation association are unchanged. | Daniel (S3-09) |

### 1.4 New business questions committed for Sprint 3

**BQ3 — Type 2 — Juan Pablo**

“Given the current user's available ingredients, dietary preferences, and maximum preparation time, which recipes satisfy all three conditions?”

Rationale: use inventory and context to provide cooking alternatives that remain usable with locally available data.

**BQ9 — Type 4 — Natalia**

“Which anonymized meal categories, price ranges, and hours show the highest demand in each campus zone, and how could these aggregated trends support restaurant partners' menu and promotion decisions?”

Rationale: use explicit search intent and restaurant interactions to support aggregated menu and promotion decisions.

**BQ6 — Type 3 — Daniel**

“Which core CampusMeal features are used fewer than two times per month by at least 80% of monthly active users?”

Rationale: identify features that may need redesign using durable, deduplicated interaction data.

The names above identify the **Sprint 3 implementation owners**. The team's original BQ table does not establish individual authorship, and this document does not assign any.

### 1.5 Future backlog (not committed and not implemented)

| Member | Backlog BQs |
|---|---|
| Juan Pablo | BQ1, BQ11 |
| Natalia | BQ10, BQ13 |
| Daniel | BQ7, BQ12, BQ14 |

Their wording stays in the team's BQ table and is not restated here. No Sprint 3 issue implements them.

### 1.6 Out of scope for Sprint 3

- Offline replay of inventory mutations (see §3, "Inventory mutations").
- Conditional HTTP requests (`ETag`/`304`) and server push.
- Opening an external maps application through an `Intent`; the MVP retains backend walking-time estimates and their failure/historical states.
- Offline authentication, or creating new recommendations offline.
- Any client-side scoring, re-ranking, or recomputation of backend BQ2/BQ4/BQ5 values.

---

## 2. Individual Responsibilities

Every member implements their **own** Android version of each of the four strategies in their own feature code. Shared infrastructure has exactly one implementation; the "Shared infrastructure" row shows who builds it. The other members integrate it, and that integration is their individual evidence, not the shared code itself.

| | Juan Pablo (`bedoya0611`) | Natalia (`NataliaEspitia`) | Daniel (`dvargasl2`) |
|---|---|---|---|
| **BQ** | BQ3 (new); BQ2 improvement | BQ9 (new); BQ4 improvement | BQ6 (new); BQ5 history and BQ8 durability |
| **Issues** | S3-01, S3-02, S3-03, S3-04, S3-05 | S3-06, S3-07, S3-08, S3-12 | S3-09, S3-10, S3-11, S3-13 |
| **Feature responsibilities** | Session/account foundation; local profile; complete inventory and drafts; backend recipe catalog; Android recipes screen (BQ3) | Restaurant search and detail caching; concurrent searches; backend route-estimate resilience; BQ9 backend report and Android report screen; final documentation and device evidence | Durable event outbox and WorkManager sync; usage-event ingestion and BQ6 backend report; comparison history; Android BQ6 report screen; Firebase distribution |
| **Shared infrastructure he/she builds** | `AccountSessionCoordinator` (one shared Mutex) and `AccountSessionScope` (account id + generation), `ConnectivityObserver`, `CachePolicy` (TTLs), `Freshness` model, refresh-outcome changes (S3-02) | — (consumes the shared pieces) | `event_outbox` table, `OutboxRepository`, `EventSyncWorker`, delivery-failure classifier (S3-09) |
| **Local storage** | Room `account_profile`, `inventory_items`, `inventory_drafts`, `recipe_catalog`; account-scoped `expiring_inventory_items`; DataStore `recipes.lastFilters.{accountId}` | Room `restaurant_search_cache`, `restaurant_detail_cache`, `zone_demand_report_cache`; DataStore `context.lastForm.{accountId}` (no coordinates) | Room `event_outbox`, `comparison_history`, `feature_adoption_report_cache`; DataStore `usage.lastSessionStartedAt.{accountId}` |
| **Caching** | Inventory snapshot, 5-minute TTL, stale-while-revalidate; recipe catalog, 24-hour TTL, versioned; in-memory BQ3 result memo keyed by inputs | Search cache keyed by account and context with a 30-minute time window, 5-minute TTL; detail cache, 30-minute TTL; zone-demand report cache, 15-minute TTL | Feature-adoption report cache, 15-minute TTL; comparison history as a bounded historical cache (never revalidated as current) |
| **Concurrency** | Per-account `Mutex` serializing inventory refreshes and mutations; parallel catalog and inventory loading with `coroutineScope { async }`; BQ3 filtering on `Dispatchers.Default` with cancellation of superseded evaluations | Cancellation of superseded searches (`Job` per query); `queryId` check that rejects late responses; detail and cache reads in parallel; context-form debounce | A serial account-scoped WorkManager chain using `APPEND_OR_REPLACE`; claiming outbox batches in Room transactions; bounded batches; cancellation of superseded comparisons with a `queryId` check |
| **Eventual connectivity** | Offline BQ3 from cached catalog and inventory, showing source ages; drafts preserved offline; reconciliation after a mutation with an unknown outcome; refresh when connectivity returns | Historical restaurant results and details when offline; search-intent and detail-open events recorded durably via the outbox; offline report from cache | At-least-once delivery with retry/backoff; recovery after process death and force-stop; offline history; BQ8 selections recorded offline and delivered later |
| **Cross-review** | Reviews Daniel's PRs and writes the justification of Daniel's four strategies | Reviews Juan Pablo's PRs and writes the justification of Juan Pablo's four strategies | Reviews Natalia's PRs and writes the justification of Natalia's four strategies |
| **Expected evidence** | §9 records for E-01, E-02, E-03, E-08 on inventory and recipes, plus four strategy records | §9 records for E-01, E-02, E-04, E-08, E-09 on restaurants and BQ9, plus four strategy records | §9 records for E-02, E-05, E-06, E-07, E-09 on the outbox, history, and BQ6, plus four strategy records |

---

## 3. Feature Connectivity Matrix

**State vocabulary** (shared across features, built on `<pkg>/core/common/UiState.kt`):

- `Content`: current data from the backend, or from a cache that is still within its TTL.
- `Empty`: a *valid* empty answer (a successful sync that returned nothing).
- `OfflineWithCache(age)` / `Historical(age)`: cached data shown with its visible timestamp. `Historical` is used when time-sensitive values (opening status, prices, routes, recommendations) may no longer apply.
- `NoLocalData`: offline with nothing cached for this account and context. This is never shown as `Empty`.
- `AccountNotIdentified`: tokens exist but the account id is unknown, so account-scoped data cannot be read.
- `OutcomeUnknown`: the request may have reached the server, but its result was not received.
- `SessionExpired`: the backend definitively rejected the credentials.

Missing local data always produces an explicit state. Nothing is fabricated.

| Feature | Owner | Normal online | Offline with local data | Offline without local data | After interruption / failed refresh | Recovery / retry |
|---|---|---|---|---|---|---|
| Login and registration | Juan Pablo (S3-02) | `POST auth/login` / `auth/register`; tokens stored in Keystore (**Implemented**). **Planned:** then `GET auth/me`, persist `account_profile`, set the active account, and increment the session generation. | Not available: authentication requires the backend. Form text stays in memory; passwords are never persisted (**Implemented**). | Same: `ConnectionError` (**Implemented**). | A timeout or 5xx stores no session (**Implemented**). If tokens were stored but `auth/me` failed: `AccountNotIdentified`. | The user resubmits. `auth/me` is retried on the next foreground or reconnect. |
| Session restoration and refresh | Juan Pablo (S3-02) | Tokens restored from Keystore at startup; single-flight refresh on 401 and one retry (**Implemented**). | Tokens plus an identified account open the main graph in offline mode with that account's local data. The server validates on the first request. | Tokens without an identified account: `AccountNotIdentified`. No tokens: login. | A transient refresh failure (network, timeout, 408, 429, 5xx, undecodable body) **keeps the session and all local data**, and the request fails as unavailable. A definitive rejection (refresh 401/400) leads to `SessionExpired` (§7). | The next request or foreground retries the refresh. There is no background refresh loop. |
| Logout | Juan Pablo (S3-02), outbox part with Daniel (S3-09) | Complete the coordinator-protected local logout transition immediately: invalidate the generation, cancel account work, clear tokens and account-owned caches/profile/drafts/history. Preserve admitted undelivered events. Request remote revocation separately using credentials captured from the departing account (§7.4). | The same local transition works offline. Remote revocation cannot be confirmed, so the server refresh session may remain valid until expiry. | Same as with local data. | Remote failure does not delay or undo local logout. There is no pre-logout outbox flush. | Pending events are rescheduled with a new scope only after the same account authenticates and is identified again. |
| Home and profile | Juan Pablo (S3-02) | Profile and expiring list read concurrently (**Implemented**). **Planned:** `auth/me` refreshes `account_profile` on each online load. | Stored name, email, and role with "Updated ⟨time⟩"; Home expiring block from the BQ2 cache with its age. | Profile: `NoLocalData`. Home blocks show an explicit "unavailable offline" state. | A failed `auth/me` keeps the stored profile. A transient-refresh 401 no longer clears anything. | Pull to refresh or reopen the screen. |
| Inventory reads (complete and BQ2) | Juan Pablo (S3-03) | **Planned:** `GET inventory` (complete) and `GET inventory/expiring` (BQ2), stale-while-revalidate with a 5-minute TTL, snapshot replaced atomically. | Account snapshot labelled "as of ⟨time⟩". The BQ2 list is labelled with its sync date, because `remainingDays` is valid as of that date. | `NoLocalData`, never `Empty`. | A failed refresh keeps the last snapshot with its age. A refresh superseded by a mutation is discarded (§6.2). | Manual refresh, plus automatic refresh when connectivity returns while the screen is visible. |
| Inventory creation, editing, and consumption | Juan Pablo (S3-03) | The draft is saved locally on every change. Submitting requires confirmed backend success. On success, the draft is deleted and the snapshot refreshed. | The draft stays editable and saved. Submit reports "Not saved: no connection". Nothing is queued. | Same as with local data. | Failure before the request was sent (connect/DNS error): "Not saved", draft kept. Timeout after sending: `OutcomeUnknown`, draft kept, inventory **reconciled before the action can be repeated**. A consume that returns `404 INVENTORY_ITEM_NOT_FOUND` after an unknown outcome counts as already consumed. | The user resubmits manually after reconciliation. **No automatic replay.** The current contracts have no idempotency key or version for writes (D-12). |
| Context input and location | Natalia (S3-06) | Approximate fused location, or manual campus selection (**Implemented**). **Planned:** the last form (time, budget, diet, delivery, `campusId`) is persisted per account, without coordinates. | Works locally. If no location fix is available, the campus picker is used (**Implemented**). | Form defaults. | A location timeout falls back to the campus picker (**Implemented**). | Retry location. |
| Restaurant search (BQ4) | Natalia (S3-06) | A fresh cache hit (same context key, same 30-minute window, age ≤ 5 min) renders without a request. Otherwise `POST restaurants/search` runs; the previous search is cancelled and the `queryId` checked; the result is stored under its key. | The entry matching the **current context key** is shown as `Historical(age)`, with opening status, prices, and routes labelled as past values. It is never presented as current. | No matching key (or a different context): `NoLocalData` for this context. Results cached for another context are never shown. | A failed refresh shows the matching entry as historical, or `NoLocalData`. Late responses from superseded queries are dropped. | Retry reuses the same `queryId` and records no new search event. Results refresh on reconnect while visible. |
| Restaurant details | Natalia (S3-06) | `GET restaurants/:id`, cached for 30 minutes. `RESTAURANT_DETAIL_OPENED` is recorded on an intentional open (§4.2). | Cached detail. Once older than 30 minutes, it is labelled historical, and its meals and prices are shown "as of ⟨time⟩". | `NoLocalData`. | A failed refresh keeps the cached copy, labelled. | Retry. |
| Backend walking-time estimates | Natalia (S3-06) | Preserve walking estimates returned by backend restaurant search and handle its `AVAILABLE`, `PARTIAL`, or `UNAVAILABLE` provider status. Validate the deployed provider configuration. No external maps-app handoff is included in this MVP. | Previously stored estimates are labelled historical; no fresh route is claimed. | Show unavailable estimates, or partial restaurant content when provided by the backend. | Preserve available restaurant information when the provider fails and show the route-unavailable state. | Retry the backend search explicitly. |
| New meal comparisons (BQ5) | Daniel (S3-11) | `POST meal-decisions/compare`. The previous comparison is cancelled and the `queryId` checked. The result is stored in `comparison_history`, and `FEATURE_USED{MEAL_COMPARISON}` is recorded. | **Not available:** new comparisons require the backend. The screen offers history instead. | Explicit "A new comparison needs a connection." | A failed or interrupted request shows an error state with no fabricated result. | Retry the comparison. |
| Previous comparison history | Daniel (S3-11) | Room list of the account's comparisons; `FEATURE_USED{COMPARISON_HISTORY}` is recorded. | Read-only and labelled historical. Selection is disabled; "Compare again" requires the backend. | Empty-history state (a valid empty, since history is local). | Not applicable (local read). | Not applicable. |
| Recommendation impressions and selections (BQ8) | Daniel (S3-09) | Persist the event first, then deliver through `POST analytics/events` using the deterministic BQ8 `clientEventId` (§6.5). | A selection made on a **current** result is saved locally and delivered later. Show "Saved, will sync" only after the Room insert succeeds. Historical items cannot be selected. | Not applicable (requires a current comparison). | Retry admitted events with the same UUID. If the queue is full or the insert fails, explicitly report that the event could not be saved; do not display a queued/saved state. | The account-scoped WorkManager chain retries according to §6.6–§6.7. |
| Recipes (BQ3) | Juan Pablo (S3-04, S3-05) | Catalog `GET recipes` (24-hour TTL) plus the inventory snapshot, evaluated on `Dispatchers.Default`. `FEATURE_USED{RECIPES}` is recorded. | Evaluated from the cached catalog and inventory; both ages are shown. | Missing catalog or inventory: `NoLocalData`, naming which input is missing. | A failed catalog refresh keeps the previous version. Superseded evaluations are cancelled. | Manual refresh, plus refresh on reconnect. |
| BQ6 report | Daniel (S3-10, S3-11) | `ANALYST` only: `GET analytics/feature-adoption`, cached for 15 minutes. | Cached report with `generatedAt` and partial-month/late-event labels. | `NoLocalData`. | A failed refresh keeps the cache. A 403 deletes the cached report and shows "Not authorized". | Manual refresh. |
| BQ9 report | Natalia (S3-07, S3-08) | `ANALYST` only: `GET analytics/zone-demand`, cached for 15 minutes. | Cached report with `generatedAt`, the synthetic-data label, and the suppression note. | `NoLocalData`. | A failed refresh keeps the cache. A 403 deletes the cached report and shows "Not authorized". | Manual refresh. |

Policy notes:

- **Authentication requires backend connectivity.** A previously identified account may read only its own permitted local data (§7).
- **Inventory mutations** require confirmed backend success and are never replayed automatically. When a mutation's outcome is unknown, the client reconciles before the action can be repeated.
- **Restaurant cache results must match the applicable context.** Old opening status, prices, and route estimates are labelled historical.
- **Historical recommendations cannot be selected as current results without revalidation**, meaning a new comparison.
- **Valid interactions** (usage events and BQ8 events) are recorded durably and delivered later.

---

## 4. Proposed API Contracts

### 4.0 Common conventions (from `CampusMealBack:docs/API_CONTRACT.md`, unchanged)

- Base path: `/api/v1`. The Android base URL already ends in `/api/v1/` (`android/app/build.gradle.kts` line 28).
- Successful bodies are the DTO at the JSON root, with no envelope. Errors use the existing `{ statusCode, code, message, timestamp, path }` shape.
- Unknown body fields and query parameters are rejected with `400 VALIDATION_ERROR` (`whitelist` + `forbidNonWhitelisted`, `CampusMealBack:src/main.ts`).
- **Transport timestamps** are ISO-8601 UTC instants ending in `Z`, with milliseconds optional on input and always present on output.
- **Reporting calendar boundaries** (dates, months, hours) are evaluated in `America/Bogota` (UTC−5, no daylight saving time).
- Money is whole COP as a JSON integer.
- The authenticated identity always comes from the access token. No endpoint accepts a user id.
- Role-restricted endpoints return `401 INVALID_ACCESS_TOKEN` without a valid token and `403 FORBIDDEN` for the wrong role.
- Every proposed response that clients may cache carries `contractVersion` (integer, initially `1`), which is part of the client cache key.

### 4.1 `GET /api/v1/recipes`: recipe catalog (Proposed backend S3-04; Android S3-05)

| Item | Definition |
|---|---|
| Owner | Juan Pablo |
| Access | Any authenticated user (`USER` or `ANALYST`) |
| Purpose | Returns the full recipe catalog, so Android can evaluate BQ3 locally against the complete inventory and keep working offline. The backend filters nothing per user. |
| Parameters | None in contract version 1. Any query parameter returns `400 VALIDATION_ERROR`. |
| Size bound | At most 200 recipes, each with at most 20 ingredients and 15 steps. |

Response fields:

| Field | Type | Required | Rule |
|---|---|---|---|
| `contractVersion` | integer | yes | `1` |
| `catalogVersion` | string | yes | Changes whenever any recipe changes (for example `2026-10-21.1`). Part of the BQ3 cache key. |
| `generatedAt` | string (UTC instant) | yes | When the catalog was produced. |
| `recipes` | array | yes | May be `[]`. |
| `recipes[].id` | string (UUID) | yes | |
| `recipes[].name` | string | yes | 1–150 characters. |
| `recipes[].preparationMinutes` | integer | yes | 1–240. |
| `recipes[].servings` | integer | yes | 1–20. |
| `recipes[].dietaryTags` | string[] | yes | Subset of `VEGETARIAN`, `VEGAN`, `GLUTEN_FREE`, assigned by the catalog author. May be `[]`. |
| `recipes[].ingredients[].name` | string | yes | Display name. |
| `recipes[].ingredients[].normalizedName` | string | yes | Lowercase, trimmed, accents removed, single spaces. |
| `recipes[].ingredients[].aliases` | string[] | no (default `[]`) | Extra normalized names that match this ingredient. |
| `recipes[].ingredients[].quantity` | number | yes | > 0. |
| `recipes[].ingredients[].unit` | string | yes | One of `g`, `kg`, `ml`, `L`, `unit`. |
| `recipes[].ingredients[].optional` | boolean | yes | Optional ingredients do not affect whether a recipe matches. |
| `recipes[].steps` | string[] | yes | At least 1 step. |

Response example (`200 OK`):

```json
{
  "contractVersion": 1,
  "catalogVersion": "2026-10-21.1",
  "generatedAt": "2026-10-21T12:00:00.000Z",
  "recipes": [
    {
      "id": "7d2f1c3b-8a9e-4b6d-9c0f-1e2d3c4b5a69",
      "name": "Spinach omelette",
      "preparationMinutes": 15,
      "servings": 1,
      "dietaryTags": ["VEGETARIAN", "GLUTEN_FREE"],
      "ingredients": [
        { "name": "Eggs", "normalizedName": "egg", "aliases": ["eggs", "huevo", "huevos"], "quantity": 2, "unit": "unit", "optional": false },
        { "name": "Spinach", "normalizedName": "spinach", "aliases": ["espinaca"], "quantity": 50, "unit": "g", "optional": false },
        { "name": "Grated cheese", "normalizedName": "cheese", "aliases": ["queso"], "quantity": 30, "unit": "g", "optional": true }
      ],
      "steps": ["Beat the eggs.", "Cook the spinach for two minutes.", "Add the eggs and cook until set."]
    }
  ]
}
```

Empty data: `200 OK` with `"recipes": []`. Android shows a *valid* "the catalog has no recipes" state, which is different from `NoLocalData`.

Errors: `400 VALIDATION_ERROR` (any query parameter); `401 INVALID_ACCESS_TOKEN` / `USER_INACTIVE`; `500 INTERNAL_ERROR`; `503 SERVICE_UNAVAILABLE`. There is no 403, because every role may read the catalog.

#### BQ3 calculation rules (Android, S3-05)

Implemented in a pure Kotlin evaluator, run on `Dispatchers.Default`:

1. **Inputs.** The evaluator takes the account's **complete** inventory snapshot (`GET inventory`, S3-03), the catalog, the selected dietary preferences (the `context.domain.DietaryPreference` enum, D-13), the maximum preparation time (5–180 minutes), and today's date in `America/Bogota`. Preferences and time are entered on the Recipes screen, prefilled from the last validated context, and remembered in `recipes.lastFilters.{accountId}`.
2. **Usable items.** Only active items with `expirationDate ≥ today (America/Bogota)` and `quantity > 0` count. Expired and inactive items are excluded. This exclusion compares the stored calendar date; it does not change how BQ2 displays `remainingDays`.
3. **Name matching.** An inventory name is normalized (lowercase, trim, remove accents, collapse spaces). It matches an ingredient when it equals `normalizedName` or one of its `aliases`. There is no fuzzy matching.
4. **Units.** The inventory unit is normalized with a fixed alias table: `g` ← g, gr, gram, grams; `kg` ← kg, kilo, kilos; `ml` ← ml; `L` ← l, lt, liter, litre, liters; `unit` ← unit, units, u, und, unidad, unidades. Units convert only within their own group: mass (g, kg; 1 kg = 1000 g), volume (ml, L; 1 L = 1000 ml), and count (unit). An item with an unknown or incompatible unit cannot satisfy that ingredient, and the UI says so ("unit not comparable").
5. **Quantity.** A required ingredient is satisfied when the total converted quantity of all matching usable items is at least the required quantity.
6. **Diet.** A recipe qualifies only if its `dietaryTags` include **every** selected preference.
7. **Time.** `preparationMinutes ≤` the selected maximum.
8. **Output.** Only recipes that pass rules 5, 6, and 7 for all non-optional ingredients are listed. They are sorted deterministically: earliest `expirationDate` among the items used, then `preparationMinutes` ascending, then name, then id.
9. **Source age.** The screen always shows the inventory snapshot time and the catalog fetch time. If either is past its TTL, the result is labelled "Based on data as of ⟨time⟩". A missing input produces `NoLocalData(missing = CATALOG | INVENTORY)`. An evaluation with no matches is a valid `Empty`.

### 4.2 `POST /api/v1/analytics/usage-events`: usage ingestion (Proposed backend S3-10; Android S3-09)

| Item | Definition |
|---|---|
| Owner | Daniel, coordinating demand fields with Natalia |
| Access | Any authenticated user |
| Purpose | Ingests durable, deduplicated interaction events for BQ6 (feature adoption) and BQ9 (zone demand). |
| Storage | A new table, `usage_events`, with a unique `client_event_id`, `user_id` from the token, and the server-set `received_at`. The existing `analytics_events` table cannot be reused (D-12). |
| Not covered | Recommendation impressions and selections. These stay on `POST /api/v1/analytics/events` (§4.5). The usage catalog deliberately contains **no selection event**, so BQ9 has a single selection source. |

Request body: `{ "events": [ … ] }` with **1 to 50** events and at most 64 KB.

| Field | Type | Required | Rule |
|---|---|---|---|
| `clientEventId` | string (UUID v4) | yes | Generated once on the device when the event happens, stored in the outbox, and reused on every retry. |
| `schemaVersion` | integer | yes | `1`. Other values are rejected per event with `UNSUPPORTED_SCHEMA_VERSION`. |
| `eventType` | string | yes | `SESSION_STARTED`, `FEATURE_USED`, `RESTAURANT_SEARCH_SUBMITTED`, or `RESTAURANT_DETAIL_OPENED`. |
| `platform` | string | yes | `ANDROID` or `IOS`. |
| `occurredAt` | string (UTC instant) | yes | Device time of the interaction. Rejected per event with `EVENT_IN_FUTURE` if it is more than 5 minutes after `receivedAt`, or with `EVENT_TOO_OLD` if it is more than 35 days before `receivedAt`. |
| `payload` | object | yes | Validated per `eventType` (below). Unknown payload fields are rejected. |

**Event catalog (minimal, contract version 1)**

| `eventType` | When Android records it | `payload` (all fields required unless noted) |
|---|---|---|
| `SESSION_STARTED` | The app reaches the foreground with an identified account. At most once every 30 minutes per account (throttled with `usage.lastSessionStartedAt.{accountId}`). | `trigger`: `COLD_START` or `FOREGROUND` |
| `FEATURE_USED` | A core feature's main content state is displayed after a user action, once per screen visit. Offline use counts. | `feature`: `INVENTORY`, `RECIPES`, `MEAL_COMPARISON`, or `COMPARISON_HISTORY`. Restaurant features are **not** allowed here (see the next two rows), which prevents double counting. |
| `RESTAURANT_SEARCH_SUBMITTED` | The user explicitly submits a search that is actually sent after debounce. Retries, automatic refreshes, and cache hits on re-render do not create new events. | `queryId` (UUID); `zone` (allow-listed, see below); `availableMinutes` (1–240); `maximumBudget` (integer COP ≥ 0); `dietaryPreferences` (sorted, unique subset of `VEGETARIAN`, `VEGAN`, `GLUTEN_FREE`); `includeDelivery` (boolean) |
| `RESTAURANT_DETAIL_OPENED` | The user intentionally taps a restaurant. Back navigation, recomposition, retry, and prefetch do not count. | `restaurantId` (UUID); `queryId` (UUID or `null` when not opened from a search); `origin`: `SEARCH_RESULTS` or `COMPARISON`; `zone`; `displayedMinimumMealPrice` (integer COP ≥ 0 or `null`) |

**Zone allow-list** (server-validated, mirroring `CampusCatalog` in `<pkg>/feature/context/domain/MealLocation.kt` lines 75–81): `bogota-centro`, `bogota-chapinero`, `bogota-teusaquillo`, `unknown`. Adding a zone requires changing both sides. Precise coordinates are never sent. Restaurant-detail events do not assert a meal category. BQ9 meal categories come only from selected WALK/ORDER alternatives with server-created `mealCategory` metadata (§4.4); the client never supplies this category.

Request example:

```json
{
  "events": [
    {
      "clientEventId": "0b7c2f4e-5d1a-4f3b-9c8e-2a6d4e8f1b3c",
      "schemaVersion": 1,
      "eventType": "SESSION_STARTED",
      "platform": "ANDROID",
      "occurredAt": "2026-10-21T13:58:12.345Z",
      "payload": { "trigger": "COLD_START" }
    },
    {
      "clientEventId": "6f1e9a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b",
      "schemaVersion": 1,
      "eventType": "FEATURE_USED",
      "platform": "ANDROID",
      "occurredAt": "2026-10-21T13:59:40Z",
      "payload": { "feature": "INVENTORY" }
    },
    {
      "clientEventId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      "schemaVersion": 1,
      "eventType": "RESTAURANT_SEARCH_SUBMITTED",
      "platform": "ANDROID",
      "occurredAt": "2026-10-21T17:02:05Z",
      "payload": {
        "queryId": "c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f",
        "zone": "bogota-centro",
        "availableMinutes": 45,
        "maximumBudget": 20000,
        "dietaryPreferences": ["VEGETARIAN"],
        "includeDelivery": false
      }
    },
    {
      "clientEventId": "d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70",
      "schemaVersion": 1,
      "eventType": "RESTAURANT_DETAIL_OPENED",
      "platform": "ANDROID",
      "occurredAt": "2026-10-21T17:02:31Z",
      "payload": {
        "restaurantId": "559b2d1e-7c3a-4f8e-9b6d-0a1c2e3f4b5d",
        "queryId": "c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f",
        "origin": "SEARCH_RESULTS",
        "zone": "bogota-centro",
        "displayedMinimumMealPrice": 14500
      }
    }
  ]
}
```

Response (`200 OK`). There is one result per event, in request order:

```json
{
  "results": [
    { "clientEventId": "0b7c2f4e-5d1a-4f3b-9c8e-2a6d4e8f1b3c", "status": "ACCEPTED" },
    { "clientEventId": "6f1e9a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b", "status": "DUPLICATE" },
    { "clientEventId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "status": "ACCEPTED" },
    { "clientEventId": "d4e5f6a7-b8c9-4d0e-9f1a-2b3c4d5e6f70", "status": "ACCEPTED" }
  ]
}
```

| `status` | Meaning | Android outbox action |
|---|---|---|
| `ACCEPTED` | Stored now. | Delete the row. |
| `DUPLICATE` | Same `clientEventId` already stored for the same user. The first stored version wins. | Delete the row (delivery already succeeded). |
| `REJECTED` | Permanent per-event failure. `code` is one of `VALIDATION_ERROR`, `UNSUPPORTED_SCHEMA_VERSION`, `EVENT_IN_FUTURE`, `EVENT_TOO_OLD`, or `CLIENT_EVENT_ID_CONFLICT` (the same id was stored for a different user). | Mark `FAILED_PERMANENT`; never resend. |

The detail event in the example uses `zone: "bogota-centro"`, which is allowed, so it is accepted. To illustrate a per-event zone rejection separately, replace only that payload value with `"invalid-zone"`: the corresponding result is `REJECTED`, with `code: "VALIDATION_ERROR"` and a message listing the allowed zones.

Empty data: an empty `events` array is not allowed (`400`). The client never sends an empty batch.

Request-level errors (nothing is stored):

- `400 VALIDATION_ERROR`: `events` missing, empty, or longer than 50, or an element without a valid `clientEventId`.
- `400 INVALID_JSON`.
- `401 INVALID_ACCESS_TOKEN` / `USER_INACTIVE`.
- `413 PAYLOAD_TOO_LARGE`.
- `429 TOO_MANY_REQUESTS`.
- `500` / `503`.

On a request-level `400`, the worker resends the same events one at a time. A single-event `400` is permanent (§6.7).

Timestamps: `occurredAt` comes from the device clock (UTC). The server sets `receivedAt`. All reports attribute an event to the period of its `occurredAt`, evaluated in `America/Bogota`.

### 4.3 `GET /api/v1/analytics/feature-adoption?month=YYYY-MM`: BQ6 (Proposed backend S3-10; Android S3-11)

| Item | Definition |
|---|---|
| Owner | Daniel |
| Access | `ANALYST` only (`USER` → `403 FORBIDDEN`) |
| Purpose | For each core feature, the share of monthly active users who used it fewer than two times in the month. |
| Parameters | `month` (required): `YYYY-MM`, a calendar month in `America/Bogota`. It may not be after the current Bogota month. |

**Core feature catalog** and how each use is counted (all from `usage_events`):

| `feature` | Counted from |
|---|---|
| `INVENTORY` | `FEATURE_USED` with `feature = INVENTORY` |
| `RECIPES` | `FEATURE_USED` with `feature = RECIPES` |
| `RESTAURANT_SEARCH` | `RESTAURANT_SEARCH_SUBMITTED` |
| `RESTAURANT_DETAIL` | `RESTAURANT_DETAIL_OPENED` |
| `MEAL_COMPARISON` | `FEATURE_USED` with `feature = MEAL_COMPARISON` |
| `COMPARISON_HISTORY` | `FEATURE_USED` with `feature = COMPARISON_HISTORY` |

BQ6 does not read `analytics_events` (BQ8), and BQ8 does not read `usage_events`.

Response example (`200 OK`, a complete month that can still receive late events):

```json
{
  "contractVersion": 1,
  "month": "2026-09",
  "timezone": "America/Bogota",
  "periodStart": "2026-09-01",
  "periodEnd": "2026-09-30",
  "partialMonth": false,
  "lateEventsPossible": true,
  "generatedAt": "2026-10-21T15:00:00.000Z",
  "lowUseThreshold": 2,
  "flagProportion": 0.8,
  "monthlyActiveUsers": 10,
  "containsSyntheticData": false,
  "features": [
    { "feature": "COMPARISON_HISTORY", "usersWithZeroUses": 7, "lowUseUsers": 9, "lowUseProportion": 0.9, "flagged": true },
    { "feature": "RECIPES", "usersWithZeroUses": 5, "lowUseUsers": 8, "lowUseProportion": 0.8, "flagged": true },
    { "feature": "RESTAURANT_DETAIL", "usersWithZeroUses": 3, "lowUseUsers": 6, "lowUseProportion": 0.6, "flagged": false },
    { "feature": "MEAL_COMPARISON", "usersWithZeroUses": 2, "lowUseUsers": 4, "lowUseProportion": 0.4, "flagged": false },
    { "feature": "RESTAURANT_SEARCH", "usersWithZeroUses": 1, "lowUseUsers": 3, "lowUseProportion": 0.3, "flagged": false },
    { "feature": "INVENTORY", "usersWithZeroUses": 0, "lowUseUsers": 2, "lowUseProportion": 0.2, "flagged": false }
  ]
}
```

Empty-data example (`200 OK`, MAU = 0). Every catalog feature is still listed:

```json
{
  "contractVersion": 1,
  "month": "2026-08",
  "timezone": "America/Bogota",
  "periodStart": "2026-08-01",
  "periodEnd": "2026-08-31",
  "partialMonth": false,
  "lateEventsPossible": false,
  "generatedAt": "2026-10-21T15:00:00.000Z",
  "lowUseThreshold": 2,
  "flagProportion": 0.8,
  "monthlyActiveUsers": 0,
  "containsSyntheticData": false,
  "features": [
    { "feature": "COMPARISON_HISTORY", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false },
    { "feature": "INVENTORY", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false },
    { "feature": "MEAL_COMPARISON", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false },
    { "feature": "RECIPES", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false },
    { "feature": "RESTAURANT_DETAIL", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false },
    { "feature": "RESTAURANT_SEARCH", "usersWithZeroUses": 0, "lowUseUsers": 0, "lowUseProportion": null, "flagged": false }
  ]
}
```

Errors:

- `400 VALIDATION_ERROR`: missing or malformed `month` (for example `2026-9` or `2026-13`), a month after the current Bogota month, or any other query parameter.
- `401 INVALID_ACCESS_TOKEN`.
- `403 FORBIDDEN`: role is not `ANALYST`.
- `500` / `503`.

#### BQ6 calculation rules

1. **MAU**: the number of distinct users with at least one stored `usage_events` row of **any** type (including `SESSION_STARTED`) whose `occurredAt` falls in the month, in `America/Bogota`.
2. **Uses**: per user and feature, the number of counted events (table above) in the month. Duplicates do not exist, because `clientEventId` is unique.
3. **Low-use users**: MAU users with **0 or 1** uses of the feature. MAU users with zero uses **are included** and are also reported separately as `usersWithZeroUses`.
4. **Proportion**: `lowUseProportion = lowUseUsers / MAU`, rounded to 4 decimal places.
5. **Flag**: `flagged = lowUseProportion ≥ 0.80`.
6. **MAU = 0**: `lowUseProportion` is `null` and `flagged` is `false` for every feature. It is never reported as 0 or 1.
7. **Partial month**: `partialMonth = true` when `month` is the current Bogota month.
8. **Late events** are attributed to the month of their `occurredAt`, not their `receivedAt`. Because ingestion accepts events up to 35 days old, `lateEventsPossible = true` until 35 days after `periodEnd`. Earlier reports for that month may still change.
9. **Order**: `lowUseProportion` descending (`null` last), then `feature` name.
10. `containsSyntheticData = true` if any counted row is marked synthetic (§4.4, rule 7).

### 4.4 `GET /api/v1/analytics/zone-demand?from=YYYY-MM-DD&to=YYYY-MM-DD`: BQ9 (Proposed backend S3-07; Android S3-08)

| Item | Definition |
|---|---|
| Owner | Natalia |
| Access | `ANALYST` only (`USER` → `403 FORBIDDEN`) |
| Purpose | Aggregated, anonymized demand indicators per campus zone: meal categories, price ranges, and hours, to support restaurant partners' menu and promotion decisions. |
| Parameters | `from`, `to` (both required): inclusive calendar dates in `America/Bogota`. `from ≤ to`. The range may span at most 92 days. |

**Sources.** Three interaction kinds are always counted separately:

- **Searches**: `RESTAURANT_SEARCH_SUBMITTED` events. They provide the general zone, searched budget, and hour. They carry no category, and the current search form gains no new category filter for this MVP. Categories merely displayed in results are not counted as expressed demand.
- **Detail openings**: `RESTAURANT_DETAIL_OPENED` events. They provide intentional interest in a restaurant, the displayed minimum meal price when known, the general zone, and hour. They do not identify which meal the user wanted and are not attributed to meal-category rows.
- **Selections**: existing BQ8 `RECOMMENDATION_SELECTED` rows with `selectedAlternative` `WALK` or `ORDER`. The selected cost is the alternative's `estimatedCost` (including any components already included in that estimate). The meal category comes only from a `mealCategory` snapshot on that selected alternative. A category label in `restaurants.category` is not treated as a meal category.

**Proposed backend additions owned by S3-07:**

1. Add a nullable `mealCategory` to catalog meals, with a small documented vocabulary and at least three categories in the labelled demo seed. Existing meals without a known category remain null; examples include `Rice dishes`, `Salads`, and `Pasta`.
2. When generating a new WALK/ORDER recommendation alternative, copy the selected meal's `mealCategory` into `recommendation_alternatives.metadata.mealCategory`. Keep this as a historical snapshot; a later catalog edit must not recategorize an earlier selection. This requires an additive change to recommendation generation while preserving BQ5 scoring, ordering, and existing response fields.
3. Add nullable `zone` to `recommendation_runs`, derived server-side from the request's `campusId` using the allow-list. Older runs use `unknown`. Missing meal-category metadata on old or incomplete alternatives is grouped under `UNKNOWN`.

The `categories` breakdown is therefore **selection-based only**: each row reports `category`, `selections`, `distinctUsers`, and `suppressed`. Search and detail-opening totals continue to appear in the budget/price/hour breakdowns where applicable, but not as meal-category demand. Category data is sufficient to answer that part of BQ9 using known meal selections, and unknowns remain explicit.

Response example (`200 OK`):

```json
{
  "contractVersion": 1,
  "from": "2026-10-01",
  "to": "2026-10-21",
  "timezone": "America/Bogota",
  "generatedAt": "2026-10-21T15:00:00.000Z",
  "minDistinctUsersPerCell": 5,
  "containsSyntheticData": true,
  "interpretation": "INTEREST_INDICATORS_NOT_VERIFIED_PURCHASES",
  "suppressedCells": 4,
  "zones": [
    {
      "zone": "bogota-centro",
      "totals": { "searches": 48, "detailOpenings": 31, "selections": 12, "distinctUsers": 14, "suppressed": false },
      "categories": [
        { "category": "Rice dishes", "selections": 6, "distinctUsers": 5, "suppressed": false },
        { "category": "Salads", "selections": 5, "distinctUsers": 5, "suppressed": false },
        { "category": "UNKNOWN", "selections": null, "distinctUsers": null, "suppressed": true }
      ],
      "searchedBudgetRanges": [
        { "range": "10001-15000", "searches": 21, "distinctUsers": 9, "suppressed": false },
        { "range": "15001-20000", "searches": 18, "distinctUsers": 7, "suppressed": false },
        { "range": "30001+", "searches": null, "distinctUsers": null, "suppressed": true }
      ],
      "interactionPriceRanges": [
        { "range": "10001-15000", "detailOpenings": 17, "selections": 7, "distinctUsers": 9, "suppressed": false }
      ],
      "hours": [
        { "hour": 12, "searches": 19, "detailOpenings": 13, "selections": 5, "distinctUsers": 10, "suppressed": false },
        { "hour": 18, "searches": null, "detailOpenings": null, "selections": null, "distinctUsers": null, "suppressed": true }
      ]
    },
    {
      "zone": "unknown",
      "totals": { "searches": null, "detailOpenings": null, "selections": null, "distinctUsers": null, "suppressed": true },
      "categories": [],
      "searchedBudgetRanges": [],
      "interactionPriceRanges": [],
      "hours": []
    }
  ]
}
```

Empty data: `200 OK` with `"zones": []`, `"suppressedCells": 0`, and `"containsSyntheticData": false`. Android shows a valid "No demand data for this range" state.

Errors:

- `400 VALIDATION_ERROR`: missing or malformed dates, `from > to`, a range over 92 days, or an unknown parameter.
- `401 INVALID_ACCESS_TOKEN`.
- `403 FORBIDDEN`.
- `500` / `503`.

#### BQ9 calculation rules

1. **Kinds stay separate.** Searches, detail openings, and selections are always reported separately and never summed into a single "demand" number. Meal-category rows contain selections only, using the selected alternative's stored `mealCategory`. Their `distinctUsers` counts only selectors in that category. Search categories and restaurant categories are not substituted.
2. **The searched budget is not the selected price.** `searchedBudgetRanges` buckets the search `maximumBudget`. `interactionPriceRanges` buckets the opened price (`detailOpenings` column) and the selected price (`selections` column). Ranges in COP: `0-10000`, `10001-15000`, `15001-20000`, `20001-30000`, `30001+`.
3. **General zones only.** Zones come from the allow-list. Precise coordinates are never stored or used. `hour` is 0–23, the Bogota hour of `occurredAt`.
4. **Missing data is explicit.** A selection without a known meal category is reported as `"UNKNOWN"`, and a missing zone as `"unknown"`. Neither is dropped silently. A restaurant-detail opening contributes no meal category because no meal was selected.
5. **Minimum cell size.** A cell (zone totals, or a category, range, or hour row) whose `distinctUsers` is below `minDistinctUsersPerCell` (configurable, default **5**) keeps its label, has its counts set to `null`, and has `suppressed: true`. Zones whose totals are suppressed list no breakdown rows. `suppressedCells` counts all suppressed cells.
6. **Aggregates only.** No user id, event id, or individual row is ever returned.
7. **Synthetic data.** Demonstration seed scripts mark their rows with a server-only `is_synthetic` column, which clients can never set. `containsSyntheticData = true` whenever such rows are counted. Android must then show "Includes synthetic demonstration data".
8. **Interpretation.** The figures are **interest indicators**, not verified purchases. Android shows this sentence with every BQ9 report.
9. **Order.** Zones by `totals.distinctUsers` descending (suppressed zones last). Rows by `distinctUsers` descending, then label; suppressed rows last.

### 4.5 Preserved endpoint: `POST /api/v1/analytics/events` (BQ8, Implemented, unchanged)

The request (`clientEventId`, `recommendationId`, `eventType` = `RECOMMENDATION_IMPRESSION` | `RECOMMENDATION_SELECTED`, `selectedAlternative`, `platform`, `occurredAt`) and its responses (`202`, `400`, `401`, `404 RECOMMENDATION_NOT_FOUND`) stay exactly as documented in `CampusMealBack:docs/API_CONTRACT.md` §10 and used by `<pkg>/feature/decision/data/remote/RecommendationEventsApi.kt`. Sprint 3 changes Android persistence/retry behavior and uses a deterministic UUID v3 for BQ8 `clientEventId` (§6.5). The BQ8 contract accepts UUIDs, including this version; confirm this against its existing DTO during S3-09 integration rather than narrowing it to UUID v4. Events keep their recommendation association, and the explanation category is still read server-side. `GET /api/v1/analytics/explanation-selection` is unchanged.

---

## 5. Local Storage and Cache Policies

### 5.1 Where data lives

| Store | What | Rule |
|---|---|---|
| Android Keystore + `campusmeal_secure_session` (`KeystoreSessionStorage`, **Implemented**) | Access and refresh tokens only | Never copied to Room, DataStore, logs, or WorkManager input data. |
| Room `campusmeal.db` | Account-owned structured data, caches, drafts, and the outbox | Every account-owned row has an `accountId` column. Reads filter by the active account. |
| DataStore `campusmeal_preferences` | Small, non-secret preferences | Account-specific keys are suffixed with `.{accountId}`. |
| Memory only | Device coordinates, passwords, superseded results | Never persisted (**Implemented** for coordinates and passwords). |

### 5.2 Room data sets

| Table | Owner (issue) | Contents | Account-scoped |
|---|---|---|---|
| `cache_metadata` (exists) | Shared (S3-02 updates its keys) | `cacheKey` → `lastSyncedAtEpochMillis`. Marks that a sync happened, so a valid empty result is distinguishable from no cache. | Through the key (`…:{accountId}`) |
| `account_profile` | Juan Pablo (S3-02) | `accountId` (PK, from `GET auth/me` `id`), `name`, `email`, `role`, `fetchedAt` | Yes |
| `expiring_inventory_items` (exists) | Juan Pablo (S3-02 adds `accountId`) | BQ2 result in backend priority order | Yes |
| `inventory_items` | Juan Pablo (S3-03) | Complete active inventory snapshot from `GET inventory` | Yes |
| `inventory_drafts` | Juan Pablo (S3-03) | `draftId`, `accountId`, `targetItemId` (null for create), fields, `updatedAt`, `lastSubmitOutcome` | Yes |
| `recipe_catalog` | Juan Pablo (S3-05) | One row per recipe with `catalogVersion` and JSON columns for ingredients, tags, and steps | No (not personal data; requires an authenticated session to read) |
| `restaurant_search_cache` | Natalia (S3-06) | `accountId`, `contextKey`, `windowStart`, `queryId`, `responseJson`, `fetchedAt`, `contractVersion` | Yes |
| `restaurant_detail_cache` | Natalia (S3-06) | `accountId`, `restaurantId`, `responseJson`, `fetchedAt` | Yes |
| `zone_demand_report_cache` | Natalia (S3-08) | `accountId`, `reportKey`, `responseJson`, `fetchedAt` | Yes |
| `event_outbox` | Daniel (S3-09) | Durable admitted events; nullable `recommendationId`; deterministic BQ8 IDs; capacity 1,000 rows/account; see §6.5 | Yes |
| `comparison_history` | Daniel (S3-11) | `accountId`, `recommendationId` (PK), `createdAt`, context summary (`campusId`, zone, minutes, budget, diet, delivery; **no coordinates**), alternatives JSON, explanation, reasons | Yes |
| `feature_adoption_report_cache` | Daniel (S3-11) | `accountId`, `reportKey`, `responseJson`, `fetchedAt` | Yes |

### 5.3 DataStore preferences

| Key | Owner | Value |
|---|---|---|
| `session.activeAccountId` | Juan Pablo (S3-02) | UUID of the identified account, or absent |
| `session.generation` | Juan Pablo (S3-02) | `Long`, incremented on every login, logout, and invalidation |
| `recipes.lastFilters.{accountId}` | Juan Pablo (S3-05) | `{ dietaryPreferences, maxPreparationMinutes }` |
| `context.lastForm.{accountId}` | Natalia (S3-06) | `{ availableMinutes, maximumBudget, dietaryPreferences, includeDelivery, campusId }`, with **no coordinates** |
| `usage.lastSessionStartedAt.{accountId}` | Daniel (S3-09) | Epoch millis, used for `SESSION_STARTED` throttling |
| `restaurants.lastSearch` (exists) | Natalia (S3-06) | **Legacy key, removed** by S3-06 on first launch; replaced by `restaurant_search_cache` |

### 5.4 Cache keys, TTLs, invalidation, and retention

The TTLs are configurable planning values, defined once in a shared `CachePolicy` object (S3-02). They are not hard-coded in features.

| Data set | Key | TTL | Invalidation | Visible timestamp | Retention limit |
|---|---|---|---|---|---|
| Complete inventory | `inventory.items:{accountId}` | 5 min | Replaced on every successful `GET inventory`. Marked stale after any confirmed mutation. Deleted on logout. | "Updated ⟨time⟩" | One snapshot per account |
| BQ2 expiring list | `inventory.expiring:{accountId}` | 5 min | Same as complete inventory | "As of ⟨Bogota date, time⟩" | One snapshot per account |
| Inventory drafts | `draftId` | None | Deleted on confirmed success or explicit discard | "Draft saved ⟨time⟩" | 1 create draft and 1 draft per item; purged after 7 days |
| Recipe catalog | `recipes.catalog` + `catalogVersion` | 24 h | Replaced atomically when a newer version arrives | "Catalog ⟨version⟩, updated ⟨time⟩" | One version |
| BQ3 result (memory) | `{accountId}\|{inventorySyncedAt}\|{catalogVersion}\|{sortedDiet}\|{maxMinutes}\|{bogotaDate}` | Lives while the inputs are unchanged | Any input change | Shows both input ages | Last 5 keys |
| Restaurant search | `contextKey` = `v1\|{accountId}\|{campusId}\|{availableMinutes}\|{maximumBudget}\|{sortedDiet}\|{includeDelivery}`, plus `windowStart` (`requestedAt` rounded down to 30 minutes in Bogota) | 5 min, and only within the same window | Newer result for the same key and window; logout | "Results from ⟨time⟩". Shown as historical once the TTL is exceeded or the window differs. | 20 entries per account (least recently fetched evicted); entries older than 24 h deleted |
| Restaurant detail | `{accountId}\|{restaurantId}` | 30 min | Newer fetch; logout | "Details as of ⟨time⟩" | 50 per account; older than 7 days deleted |
| Comparison history | `{accountId}\|{recommendationId}` | Not revalidated (always historical) | Logout | "Compared on ⟨date, time⟩" | 30 per account; older than 30 days deleted |
| BQ6 report | `v{contractVersion}\|{accountId}\|feature-adoption\|{month}` | 15 min | Newer fetch; 403; logout | "Generated ⟨generatedAt⟩" plus partial/late labels | 5 per report type per account |
| BQ9 report | `v{contractVersion}\|{accountId}\|zone-demand\|{from}\|{to}` | 15 min | Newer fetch; 403; logout | "Generated ⟨generatedAt⟩" plus synthetic/suppression labels | 5 per report type per account |
| Account profile | `accountId` | Refreshed on each online Home/Profile load | Logout | "Updated ⟨time⟩" when offline | One per account |

Rules shared by every cache:

- **No cache versus valid empty.** As in `RoomInventoryCache` today, a `cache_metadata` row (or a `fetchedAt` column) proves that a sync happened. A row with an empty result is `Empty`; no row at all is `NoLocalData`.
- **Freshness.** Within the TTL, cached data is `Content`. Past the TTL it is shown immediately with its age while a refresh runs (stale-while-revalidate). When offline, it is labelled with its age. Restaurant and comparison data past their TTL are `Historical`.
- **Restaurant searches without a campus** (a device location outside every known campus) are **not persisted**, because the key would need location data. They remain memory-only.
- **Purging** runs at app start and after each worker run. It follows each data set's retention policy; it never evicts `PENDING` or `IN_FLIGHT` outbox rows to make room. Expired events and final failures follow §6.5.
- **No precise GPS coordinates** are stored in any persisted cache, analytics event, or report.

### 5.5 Room schema version and migration coordination

The current schema is **version 2** (see §1.2). Sprint 3 will need several migrations: S3-02, S3-03, S3-05, S3-06, S3-08, S3-09, and S3-11 each add or change tables. To avoid conflicts:

1. **Integrate first.** Before adding a migration, merge or rebase the latest `main` and read the current `version` in `CampusMealDatabase.kt`.
2. **One bump per PR.** Use `current + 1`, add `MIGRATION_N_{N+1}` to `DatabaseMigrations.ALL`, add the entities, and commit the generated `android/app/schemas/.../{N+1}.json`.
3. **Announce.** State the claimed version in the PR description (for example "Room v3 → S3-02"). If another PR merges the same number first, rebase and renumber before merging. Numbers are assigned by merge order, not reserved.
4. **Never rewrite history.** Merged migrations and schema JSON files are never edited. `fallbackToDestructiveMigration` is never used (as `DatabaseMigrations.kt` already states).
5. **Cache-only tables** may be recreated by an explicit migration. For example, S3-02 rebuilds `expiring_inventory_items` with `accountId` and deletes the ownerless rows. The PR must say that this discards cached rows only.
6. **Verify the upgrade.** Each migration PR records an upgrade check from the previous `main` APK with data present (§9). Automated migration tests belong to the implementing issue.

The expected order is S3-02, then S3-09, S3-03, S3-06, S3-05, S3-11, and S3-08. This is a guideline, not a reservation.

The backend follows the same principle. New TypeORM migrations in S3-04, S3-10, and S3-07 use timestamps later than `1790430000000`. Because S3-07 depends on S3-10, its migration is always created after S3-10's.

---

## 6. Concurrency and Event Synchronization

### 6.1 Responsibilities

| Mechanism | Rule | Where |
|---|---|---|
| Structured coroutines | All UI work runs in `viewModelScope`. Parallel reads use `coroutineScope { async }`, so if one fails, its siblings are cancelled and nothing leaks. Global scopes are not used. | All features. Existing example: `HomeViewModel.kt` lines 55–73. |
| `Dispatchers.Default` | CPU filtering and sorting: BQ3 evaluation, grouping large inventory lists, and parsing cached JSON blobs. Room and DataStore already use their own I/O executors. Retrofit suspend calls are main-safe. | S3-03, S3-05, S3-06 |
| Cancelling superseded work | Each screen keeps a single `Job` for its current search, comparison, or evaluation, and cancels it before starting a new one (or uses `collectLatest`/`mapLatest`). The context form debounces changes (~800 ms) before sending. | S3-05 (BQ3), S3-06 (search), S3-11 (comparison) |
| Query identifiers | Each submitted search, comparison, or BQ3 evaluation gets a `queryId` (UUID). The ViewModel accepts a result only if its `queryId` equals `latestQueryId`. Repositories also check the account generation (§7.3) before writing. This protects against reversed response order, even when cancellation fails to stop an in-flight HTTP call. | S3-05, S3-06, S3-11 |
| Inventory serialization | See §6.2. | S3-03 |
| Refresh coordination (**Implemented**) | `refreshMutex` plus the rejected-token comparison guarantee a single in-flight refresh. S3-02 keeps this logic and changes only the outcome classification (§7.5). | `NetworkAuthRepository.kt` lines 95–96, 171–193 |
| Unique account-scoped work | See §6.6. | S3-09 |

### 6.2 Inventory refresh and mutation serialization (S3-03)

- A per-account `Mutex` in the inventory repository guards every refresh and every mutation.
- A mutation holds the lock while it sends the request. On success, it refreshes the snapshot **inside the same lock**, so no earlier refresh can overwrite the post-mutation state.
- A refresh requested while a mutation is running waits for the lock and then reads the latest server state.
- Double taps on Save or Consume are ignored while a submission for the same draft or item is in flight.
- `ApiResult.NetworkUnavailable(cause)` is split into two cases. A connect or DNS failure means "not sent", so the draft is kept. A `SocketTimeoutException` after the request was written means `OutcomeUnknown`, which forces a reconciliation refresh before the action can be repeated.

### 6.3 Restaurant and comparison concurrency (S3-06, S3-11)

- Superseded searches and comparisons are cancelled, and every response is checked against `latestQueryId` (fixes D-06).
- A cache write for a superseded but valid response is allowed, because it is stored under its own context key. A screen update is not.
- Opening a detail screen reads the cache and starts the network request in parallel. The cached copy renders first, labelled; the network result replaces it only if it arrives for the same `restaurantId` and account generation.

### 6.4 Valid interactions are durable

Every catalog event (§4.2) and every BQ8 impression or selection must be **admitted to the Room outbox before any delivery attempt**. The UI does not wait for network delivery, but a BQ8 selection may show "Saved, will sync" only after the local transaction succeeds. At capacity or on a local write failure, explicitly report that the event could not be saved and retain the current screen for a user-initiated retry. An unadmitted event is never reported as queued. Delivery guarantees apply to admitted events, within the endpoint's accepted age window.

### 6.5 Outbox design (S3-09)

`event_outbox` columns:

- `localId` (PK).
- `clientEventId` (UUID, unique).
- `accountId`.
- `recommendationId` (nullable UUID): required for `RECOMMENDATION`, null for `USAGE`.
- `channel`: `USAGE` → `POST analytics/usage-events`; `RECOMMENDATION` → `POST analytics/events`.
- `eventType`, `schemaVersion`.
- `payloadJson`: the exact body fields, with no tokens and no coordinates.
- `occurredAt`, `createdAt`.
- `state`: `PENDING`, `IN_FLIGHT`, or `FAILED_PERMANENT`.
- `attemptCount`, `lastErrorCode`, `failedAt` (nullable, set when entering a permanent-failure state).

Rules:

- **Usage-event identity.** Generate a random UUID v4 once per actual usage interaction, before insertion. Every retry sends that UUID unchanged. Retry, recomposition, and automatic refresh do not create another interaction.
- **BQ8 identity after acknowledgment.** Compute `clientEventId` deterministically with Java `UUID.nameUUIDFromBytes` (UUID v3), using UTF-8 bytes of `campusmeal:bq8:v1|{accountId}|{recommendationId}|{eventType}`. UUID fields use their canonical lowercase string representation; `eventType` uses its existing uppercase wire value. This format is fixed for the MVP. Consequently, a repeated impression or selection has the same ID even after its acknowledged outbox row has been deleted. The preserved BQ8 DTO must accept UUID v3 (§4.5); do not use this method for the usage endpoint, which requires UUID v4.
- **BQ8 uniqueness.** A unique index on (`accountId`, `recommendationId`, `eventType`) prevents duplicate pending BQ8 rows. Use insert-if-absent; preserve the first payload and original `occurredAt`. `selectedAlternative` is not part of the identity: the app accepts one selection per recommendation. Disable changing the choice while its event is pending or after acceptance. Deterministic identity preserves server deduplication after local acknowledgment/deletion, without another persistent deduplication table.
- **Delivery semantics.** Delivery attempts are **at least once**, subject to session availability, execution constraints, and the channel's accepted event-age window. Server deduplication prevents repeated storage of the same ID. An event is acknowledged only after `202` for BQ8 or `ACCEPTED`/`DUPLICATE` for usage. No claim of universal exactly-once execution is made.
- **Bounded requests.** Send `USAGE` rows in batches of at most 50, oldest first. Send `RECOMMENDATION` rows individually because that endpoint is not batched. A run continues in bounded request groups while eligible rows remain and its account/generation is current; 50 is a batch bound, not a reason to leave the remaining queue unscheduled.
- **Claiming.** Under the shared session coordinator, verify the current scope and move eligible rows to `IN_FLIGHT` in a Room transaction. At the start of a valid worker run, reset interrupted `IN_FLIGHT` rows for that account to `PENDING`. Apply acknowledgments, retries, and permanent-failure updates only if the worker's scope is still current. Network calls happen outside the coordinator lock. If a canceled old request reached the server, a later retry uses the same ID and is deduplicated.
- **Pending retention.** Do not delete `PENDING` or `IN_FLIGHT` events after seven days. Keep admitted events until acknowledgment or definitive rejection. Usage events older than the contract's 35-day limit become `FAILED_PERMANENT` with `EVENT_TOO_OLD`; a pending event for another channel follows that endpoint's documented age policy, without inventing an extra TTL.
- **Final-failure retention.** Keep `FAILED_PERMANENT` rows for seven days from `failedAt`, then remove them. Local diagnostics expose their failure reason. Confirmed events are removed immediately.
- **Capacity.** Limit the outbox to 1,000 rows per account. Check capacity and insert atomically. Remove eligible permanent-failure diagnostic rows first when room is needed; never evict admitted pending/in-flight events. If the account still has 1,000 pending/in-flight rows, return `EventQueueFull` for the new event and explicitly indicate it could not be saved. Existing IDs reuse their stored event without consuming another slot. A new BQ8 event rejected at capacity can be retried by the user with the same deterministic ID.
- **Account ownership.** Outbox rows retain their original `accountId` across logout. A later login by that same account schedules them using the **new current session generation**. Old event ownership is never rewritten to another account.

### 6.6 WorkManager scheduling (S3-09)

- `EventSyncWorker` is a `CoroutineWorker` scheduled in a **unique serial chain** named `event-outbox-sync:{accountId}`, with `ExistingWorkPolicy.APPEND_OR_REPLACE`, tag `account:{accountId}`, constraint `NetworkType.CONNECTED`, and exponential backoff starting at 30 s.
- Input data contains only `accountId` and the current `generation` captured at scheduling. Tokens are never stored in WorkManager input or event payloads. The worker captures authorization for that verified scope through the coordinator immediately before sending; it cannot substitute another account's credentials during a refresh or retry (§7.3).
- After each successful event insertion, append an execution to that account's chain. Also schedule on app start/foreground when an identified account has pending events, and after that same account logs in again. A duplicate event already pending may also trigger scheduling without adding another outbox row.
- Appending during an existing run leaves a subsequent execution in the chain, so an event inserted as a worker finishes has a scheduled consumer. Extra executions that find an empty queue finish successfully. Failed/canceled predecessor chains are replaced according to the selected policy rather than preventing a new login's work from being scheduled.
- A valid run resets interrupted claims, drains eligible events in request groups, and checks the active account/generation before each claim, authorization capture, and local result update. It returns `Result.retry()` for retryable failures. Permanent per-event failures are recorded and draining continues. A stale scope stops without sending or changing another generation's rows.
- A normal login by the original account schedules with its new scope. Pending records remain owned by account rather than being tied permanently to the generation in which they were created. Logout cancels the previous chain; a newly identified session schedules its own execution.
- **No immediate execution is promised.** Android still controls when work runs under its constraints. The MVP guarantees scheduling of a later consumer for an admitted new event, not instantaneous delivery. Process-death and force-stop behavior is documented in §6.8.

### 6.7 Failure classification

| Outcome | Examples | Outbox action | Worker result |
|---|---|---|---|
| Success | `202` (BQ8); `ACCEPTED` or `DUPLICATE` (usage) | Delete the row | Continue |
| Retryable transport | `IOException`, timeout, refresh temporarily unavailable (§7.5) | Back to `PENDING`, `attemptCount + 1` | `Result.retry()` |
| Retryable server | `408`, `429`, `500`–`599` | Back to `PENDING`, `attemptCount + 1` | `Result.retry()` |
| Permanent validation | Per-event `REJECTED`; single-event `400`; `404 RECOMMENDATION_NOT_FOUND`; `422`; unexpected `403` | `FAILED_PERMANENT` with `lastErrorCode`; never resent | Continue |
| Request-level `400` on a batch | One malformed element | Resend the same rows one at a time to isolate it | Continue |
| `413` on a batch | Body too large | Halve the batch size and resend | Continue |
| Session-related | Definitively rejected credentials; account/generation mismatch; no tokens | Leave admitted rows owned by their original account and pending. A stale worker does not update rows under a new scope. | Stop successfully. A new identified login of that same account schedules with the current generation through §6.6. |

### 6.8 Process death and force-stop

- **Process death** (the system kills the app): outbox rows are already in Room, and WorkManager has persisted its scheduled work in its own database. The work runs again when its constraints are met, and a run interrupted mid-batch resets its `IN_FLIGHT` rows to `PENDING` and resends them with the same UUIDs.
- **Force-stop** (the user stops the app from Settings): Android cancels the app's scheduled jobs and alarms, and they do not run until the user opens the app again. On the next launch, WorkManager reschedules the persisted work, and the app also re-enqueues on start. Delivery resumes **after reopening**, not before.

---

## 7. Account Isolation and Session Lifecycle

### 7.1 Stable account identifier (S3-02)

- After every successful login or registration, the app calls `GET /api/v1/auth/me` and stores `id` (UUID) as `accountId` in `account_profile`, together with `name`, `email`, and `role`. It then sets `session.activeAccountId` and increments `session.generation`. Tokens are never decoded to read the id, as the contract requires.
- Until `accountId` is known, account-scoped features show `AccountNotIdentified` with a retry action, and no local data is read.
- At startup with stored tokens, the stored `activeAccountId` is used, which allows offline reading. The next successful `auth/me` confirms it. If `auth/me` returns a **different** id, it is treated as an account change (§7.3).
- Once a login/registration is identified, publish its new scope through the shared coordinator and schedule that account's admitted pending events with the new generation. A preserved event's account ownership remains unchanged.

### 7.2 Ownership of local records

Every account-owned Room row has `accountId`, and every account-specific DataStore key ends in `.{accountId}`. DAO queries always filter by the active `accountId`. Rows of a different account are never returned, even when offline. The recipe catalog (§5.2) is the only non-scoped data set.

### 7.3 Account and session generation checks

- Create **one `AccountSessionCoordinator` instance** in `AppContainer`, shared by authentication, repositories, and workers. It owns a single `Mutex` and the in-memory active `AccountSessionScope(accountId, generation)`. DataStore persists `session.activeAccountId`/`session.generation` for restoration; after restoring them, initialize the coordinator before enabling account-scoped reads.
- Capture the scope when an operation starts. For an account-scoped write, acquire the coordinator's Mutex, compare the captured scope with the active scope, and perform the local write only when they still match. For Room, the check and Room transaction remain inside this critical section. Account-specific DataStore edits use the same coordinator lock. A mismatch discards the result.
- Every login publication, logout, account change, and definitive invalidation uses that same coordinator. Local state transitions and writes are serialized by it. Room and DataStore remain separate stores: the plan does **not** claim a transaction across them. On restart, absence of tokens/identified account keeps account-owned data unreadable; persisted scope is reconciled before exposing it.
- **No network call runs while this Mutex is held.** Capture the expected account/generation and its authorization together, release the lock, then perform HTTP. A token-refresh retry rechecks the same expected account/generation and obtains credentials only for that scope. If the account changed, abort the retry; never attach account B's token to a request/event that started under A. Local results are checked again before being applied.
- ViewModels belong to the authenticated navigation graph. When `SessionStatus` leaves `AUTHENTICATED`, the graph is popped and their scopes are canceled. This is complemented by the coordinator checks; cancellation alone is not used as the account-isolation guarantee.
- Workers perform the same checks before claiming events, capturing authorization, and applying delivery results (§6.5–§6.6). An HTTP request already sent under the old account may still finish; its response cannot change the new session's local state.

### 7.4 Logout and account change

Explicit logout completes locally without waiting for the outbox or the remote logout response. Perform the local transition through the shared coordinator before another login is published:

1. Capture the departing account id and its logout credentials in memory for a best-effort revocation request. Credentials are never placed in Room, an event, or WorkManager input.
2. Increment `session.generation` and clear the active account scope/identifier. This invalidates in-flight local results and prevents further sends under that scope.
3. Request cancellation of `event-outbox-sync:{accountId}` and account-tagged work; cancel authenticated UI work. Invalidate stale callbacks through §7.3 even if cancellation has not yet completed.
4. Clear local tokens and delete that account's caches, drafts, profile, comparison history, report caches, and account-specific form/filter preferences. Preserve its admitted undelivered outbox rows.
5. Complete local logout and release the coordinator. Outside the lock, attempt `POST auth/logout` using **only the captured departing-account credentials** when connectivity permits. Its result never clears or changes a later account's session. Remote revocation failure does not delay local logout; the remote refresh session may remain valid until expiry.

There is **no pre-logout outbox flush** and no five-second network wait. Pending events remain owned by the original account under §6.5. They can be delivered only after that same account authenticates and is identified again, using a newly scheduled worker with its current generation. Admission/expiry/capacity rules remain explicit; logout itself does not drop admitted pending events.

When a different account logs in, its reads/writes use only its own scope. Old account read-cache rows are purged to bound storage, while outbox ownership is preserved. The recipe catalog is non-personal and may remain shared. Neither an old worker nor an interceptor retry can switch an old request to the new account's credentials.

### 7.5 Refresh failures: transient versus definitive

Required S3-02 changes to the current behavior (D-01 to D-04):

1. `refreshSession` returns `Refreshed`, `TemporarilyUnavailable`, or `Rejected` instead of a `Boolean`.
   - `Rejected` covers only `401` (`INVALID_REFRESH_TOKEN`, `REFRESH_SESSION_REVOKED`, `USER_INACTIVE`) and `400` (an unusable stored token).
   - `TemporarilyUnavailable` covers network errors, timeouts, `408`, `429`, `5xx`, and undecodable bodies. Today, `NetworkAuthRepository.kt` lines 239–255 invalidate the session on 408, 429, and undecodable responses.
2. **`SessionRefreshInterceptor` must not pass the original 401 through after a `TemporarilyUnavailable` refresh** (today, lines 79–81 return it). Instead it throws a dedicated `IOException` subclass (`SessionRefreshUnavailableException`). `apiCall` then maps it to `ApiResult.NetworkUnavailable`, so callers use their offline path.
3. The exclusion at `SessionRefreshInterceptor.kt` lines 42–45 is narrowed to `auth/login`, `auth/register`, `auth/refresh`, and `auth/logout`, so `GET auth/me` is refreshed like any protected call.
4. Repositories **stop clearing caches on 401** (`OfflineFirstInventoryRepository.kt` lines 40–43 and 94–97; `RestaurantRepository.kt` lines 59–62). Cache lifetime is owned by the session lifecycle (§7.4 and §7.6).
5. The `runBlocking` calls inside the interceptor (lines 70–77, 83–87) stay for the prototype. They run on OkHttp's thread, not the main thread, and are serialized by `refreshMutex`.

The result: a transient refresh failure **keeps the session and all local data**, and the screen shows its offline state.

### 7.6 Definitive credential rejection

On `Rejected`, the existing `invalidateSession()` clears tokens and sets `SessionStatus.EXPIRED` (Session Expired screen, **Implemented**). From Sprint 3 on, run invalidation through the shared coordinator: increment the generation, clear the active scope/identifier and tokens, and cancel account work. Unlike explicit logout, definitive expiry may preserve account-owned drafts and caches for reauthentication. They are unreadable without a newly identified session. Specifically:

- Workers for that account stop; admitted undelivered rows stay account-owned and pending, without accepting stale result updates.
- Account-scoped rows stay on disk but are unreadable until the **same** `accountId` signs in again. This keeps the user's unsent drafts and events.
- If a different account signs in, §7.4 applies.
- Repeated 401 responses after a successful refresh mean the backend rejects the new token. They are routed to `expireSession()`, which nothing calls today (D-04).

### 7.7 Roles

The `role` cached in `account_profile` only controls **presentation**: whether the BQ6 and BQ9 report entries are visible. The backend remains authoritative. A `403 FORBIDDEN` from a report endpoint deletes that account's cached report, hides the entry, and shows "Not authorized". A cached `ANALYST` role never makes a cached report readable after a 403.

---

## 8. Implementation Order

| Code | Owner | Repository | Dependencies | Scope |
|---|---|---|---|---|
| S3-01 | Juan Pablo | Android | None | This plan: scope, contracts, policies, and evidence format. |
| S3-02 | Juan Pablo | Android | S3-01 | Session, local profile, and account isolation: `account_profile`, shared `AccountSessionCoordinator`, account id/generation, immediate local logout (§7.4), refresh-outcome changes (§7.5), cache scoping, `CachePolicy`, `ConnectivityObserver`. |
| S3-03 | Juan Pablo | Android | S3-01, S3-02 | Complete inventory (`GET inventory`) and drafts: `inventory_items`, `inventory_drafts`, mutation serialization, outcome-unknown reconciliation. |
| S3-04 | Juan Pablo | Backend | S3-01 | Backend recipe catalog: `GET /api/v1/recipes`, migration, seeds. |
| S3-05 | Juan Pablo | Android | S3-02, S3-03, S3-04, S3-09 | Android BQ3: catalog cache, evaluator on `Dispatchers.Default`, recipes screen, `FEATURE_USED{RECIPES}`. |
| S3-06 | Natalia | Android | S3-01, S3-02, S3-09 | Restaurant caching and concurrent searches: keyed search and detail caches, cancellation, `queryId`, historical labels, backend route-estimate resilience, search and detail events. Maps-app handoff is deferred. |
| S3-07 | Natalia | Backend | S3-01, S3-10 | Backend BQ9: `GET /api/v1/analytics/zone-demand`, selection-based meal categories, nullable catalog `mealCategory` and its snapshot in WALK/ORDER metadata, cell suppression, synthetic marking, `recommendation_runs.zone`. |
| S3-08 | Natalia | Android | S3-06, S3-07, S3-09 | Android BQ9: report screen, `zone_demand_report_cache`, role gating, labels. |
| S3-09 | Daniel | Android | S3-01, S3-02, S3-10 | Durable event outbox and synchronization: `event_outbox`, deterministic BQ8 UUIDs, protected pending retention/capacity, serial `APPEND_OR_REPLACE` chain, current-generation reactivation, `SESSION_STARTED`. |
| S3-10 | Daniel | Backend | S3-01 | Usage ingestion and backend BQ6: `usage_events`, `POST /api/v1/analytics/usage-events`, `GET /api/v1/analytics/feature-adoption`, zone allow-list. |
| S3-11 | Daniel | Android | S3-02, S3-09, S3-10 | Comparison history and Android BQ6: `comparison_history`, cancellation of superseded comparisons, report screen, `feature_adoption_report_cache`. |
| S3-12 | Natalia | Android | S3-05, S3-08, S3-11 | Documentation, cross-member justifications, and physical-device evidence (§9). |
| S3-13 | Daniel | Android | S3-12 | Firebase App Distribution and installation verification. |

**Dependency graph check.** One valid topological order is S3-01 → S3-02 → S3-04 → S3-10 → S3-03 → S3-09 → S3-07 → S3-05 → S3-06 → S3-11 → S3-08 → S3-12 → S3-13. Every issue appears after all of its dependencies, so the graph has **no cycles**. The longest chain is S3-01 → S3-10 → S3-09 → S3-06 → S3-08 → S3-12 → S3-13.

**Parallel development against agreed contracts.** Work can start before dependencies merge, using fakes for the §4 contracts:

| Wave | Can be developed in parallel | Notes |
|---|---|---|
| 1 | S3-02, S3-04, S3-10 | The two backend issues only need §4 of this plan. |
| 2 | S3-03, S3-09, S3-07 | S3-09 can build the outbox and worker against a fake usage endpoint before S3-10 merges. S3-07 can write its queries against the `usage_events` schema in §4.2. |
| 3 | S3-05, S3-06, S3-11 | Each can start UI and evaluator work with fakes for `GET recipes`, the outbox interface, and the reports. |
| 4 | S3-08 | Needs the S3-07 response shape (fakes from §4.4 allow earlier UI work). |
| 5–6 | S3-12, then S3-13 | Only on integrated `main`. |

**Integration gates (must be merged before a dependent PR is accepted):**

- S3-02 before any Android PR that adds account-scoped tables or reads `accountId`.
- S3-09 before any PR that records events (S3-05, S3-06, S3-08, S3-11).
- S3-10 deployed to the shared backend before S3-09's acceptance on a device.
- The backend endpoint before its Android report or recipe screen is accepted (S3-04 → S3-05, S3-07 → S3-08, S3-10 → S3-11).
- The latest Room version on `main` before any PR adds a migration (§5.5).

Fakes may speed up development, but **acceptance requires the real integrated dependency**.

---

## 9. Evidence and Cross-Review Requirements

### 9.1 Evidence record

Each piece of evidence is one record, stored in S3-12's documentation (one table row or one block per record):

| Field | Description |
|---|---|
| Member | GitHub account of the implementer |
| Feature / BQ | For example "Inventory reads / BQ2" |
| Strategy | `Local storage`, `Caching`, `Concurrency`, or `Eventual connectivity` |
| Source file / class | Repository-relative path and class, for example `<pkg>/feature/inventory/data/...` |
| Commit / PR | Commit SHA and PR number |
| Device | Physical device model and Android version (an emulator does not count for device evidence) |
| APK | `versionName` and the commit it was built from |
| Reproduction steps | Numbered steps, including how connectivity was changed (airplane mode, Wi-Fi off, backend stopped) |
| Expected behavior | From §3 and §6 of this plan |
| Observed behavior | What actually happened |
| Screenshot / video | File path or link |
| Reviewer and status | Reviewer's GitHub account; `Pending`, `Approved`, or `Changes requested` |

Every record starts with status **`Pending`**. Only the named reviewer may change it, after actually reviewing it.

### 9.2 Representative scenarios

All scenarios are **Pending**. None has been executed.

| ID | Scenario | Steps (summary) | Expected | Primary owners | Status |
|---|---|---|---|---|---|
| E-01 | Offline startup without cached data | Fresh install, log in, enable airplane mode before opening inventory, restaurants, and recipes; reopen the app | Each screen shows `NoLocalData`, never `Empty` and never fabricated data | Juan Pablo, Natalia | Pending |
| E-02 | Offline reopening after synchronization | Online: load inventory, a search, a detail, a comparison, and a report. Force-close, enable airplane mode, reopen | Cached data for the same account and context, with visible timestamps. Restaurant and comparison data labelled historical. A new comparison is unavailable. | All | Pending |
| E-03 | Interrupted requests | Submit an inventory edit and cut connectivity mid-request (or stop the backend after the request is received) | `OutcomeUnknown`, draft kept, reconciliation before resubmission is possible, no duplicate item | Juan Pablo | Pending |
| E-04 | Rapid filter changes with reversed response order | Change the context quickly 3 times, using a backend delay so the first response arrives last | Only the latest `queryId` is displayed. Earlier responses are dropped. | Natalia | Pending |
| E-05 | Process death with pending events | Offline: record inventory/recipe usage and a valid selection from a current comparison. Kill the process (`adb shell am kill` while in the background, or swipe away), restore connectivity | The events are delivered after the worker runs, and appear exactly once on the server | Daniel | Pending |
| E-06 | Repeated delivery with the same UUID | Drop the response of an accepted batch (proxy or backend toggle) so the worker resends | The server returns `DUPLICATE` / `202`, and one row is stored per `clientEventId` | Daniel | Pending |
| E-07 | Logout or account change with requests or workers pending | Start a slow search and keep events pending; log out; log in as another account | No late result or event appears under the second account. The first account's admitted events remain pending for it. Its work is canceled; after signing in again as the first account, they synchronize using the new generation. | Daniel, Juan Pablo | Pending |
| E-08 | Valid empty results | Account with an empty inventory; a search with no matches; recipes with no match | `Empty` states, distinct from `NoLocalData`, both online and offline after sync | Juan Pablo, Natalia | Pending |
| E-09 | Unauthorized report access | Open BQ6 and BQ9 as a `USER` (including with a role tampered locally) | Server `403`, cache deleted, "Not authorized" shown, no report data visible | Natalia, Daniel | Pending |
| E-10 | Force-stop recovery | Pending events → force-stop from Settings → restore network → wait → reopen | No delivery while force-stopped. Delivery resumes after reopening. | Daniel | Pending |
| E-11 | Room upgrade | Install the previous `main` APK with cached data; install the new APK | Migration succeeds with no crash. Only the documented cache-only rows are discarded. | Each migration author | Pending |
| E-12 | Event inserted as a worker finishes | Complete one send and insert another admitted event during worker completion | A subsequent execution remains in the account chain; the new event is delivered without requiring another app opening | Daniel | Pending |
| E-13 | BQ8 duplicate after local acknowledgment | Deliver a BQ8 event, remove the acknowledged outbox row, then trigger the same recommendation/event type again | The deterministic UUID is unchanged; the backend stores no second event | Daniel | Pending |
| E-14 | Full outbox and pending retention | Fill the account queue with 1,000 admitted pending events, then attempt another interaction | Existing pending events remain intact; the new insert returns `EventQueueFull`; UI never claims that the rejected event was saved. Pending events are not purged at seven days | Daniel | Pending |
| E-15 | Meal-category attribution | Seed at least three meal categories, create WALK/ORDER selections, then edit the catalog category | Category breakdown counts selections using their stored meal-category snapshots. Searches/details contribute no inferred meal category; older missing values remain `UNKNOWN` | Natalia | Pending |

### 9.3 Cross-review cycle

| Reviewer | Reviews PRs of | Must write a justification of | Status |
|---|---|---|---|
| Natalia (`NataliaEspitia`) | Juan Pablo: S3-01, S3-02, S3-03, S3-04, S3-05 | Juan Pablo's local storage, caching, concurrency, and eventual-connectivity strategies | Pending |
| Daniel (`dvargasl2`) | Natalia: S3-06, S3-07, S3-08, S3-12 | Natalia's local storage, caching, concurrency, and eventual-connectivity strategies | Pending |
| Juan Pablo (`bedoya0611`) | Daniel: S3-09, S3-10, S3-11, S3-13 | Daniel's local storage, caching, concurrency, and eventual-connectivity strategies | Pending |

Each justification must cover **all four** strategies. For each one it must state what problem the strategy solves, why this design fits the feature, and which evidence record (§9.1) demonstrates it. Each member writes their own explanation. This plan does not draft or attribute any teammate statement.

### 9.4 Items awaiting human action

- Natalia's review of this plan (S3-01).
- Human review of the updated proposed backend contracts in §4, including usage error codes, UUID v3 compatibility for BQ8, `recommendation_runs.zone`, nullable catalog `mealCategory`, and the meal-category snapshot on recommendation alternatives. The MVP decisions are agreed; their implementation and verification remain pending.
- Confirmation that the shared backend deployment URL and `ANALYST` test accounts are available for device evidence.
- All physical-device records (§9.2), all cross-member justifications (§9.3), and Firebase distribution and installation verification (S3-13).
- Evaluator contacts and any external credentials needed for distribution.
