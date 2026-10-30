# Sprint 3 delivery: Daniel's section (S3-12)

Daniel Vargas (`dvargasl2`). Input for `SPRINT3_DELIVERY.md` (S3-12, owner Natalia), kept in its own file so the consolidated document can link or copy it without merge conflicts.

Status values follow `SPRINT3_PLAN.md` §9.1: every record is **Pending** until its named reviewer approves it. Device fields stay empty until the scenario is run on a physical phone; nothing below is a fabricated result.

## 1. Business question

**BQ6 — Type 3 — Daniel**

"Which core CampusMeal features are used fewer than two times per month by at least 80% of monthly active users?"

Rationale: find features that may need redesign, using durable, deduplicated interaction data instead of opinions.

- **Data source:** `usage_events` (backend), fed by the Android outbox: `SESSION_STARTED` defines monthly active users, and `FEATURE_USED` counts uses of `INVENTORY`, `RECIPES`, `RESTAURANT_SEARCH`, `RESTAURANT_DETAIL` and `MEAL_COMPARISON`.
- **Answer:** `GET /api/v1/analytics/feature-adoption?month=YYYY-MM` (ANALYST only). Users with zero uses count as low-use, months are computed in America/Bogota, and the response flags `partialMonth` and `includesSyntheticData`.
- **Synthetic vs real data:** the demo month 2026-09 comes from `seed:usage` (10 MAU: MEAL_COMPARISON 8/10 = 80% flagged, INVENTORY 7/10 = 70% not flagged) and is labelled "Includes synthetic demo data" in the app. Real months show their own MAU, so the sample size is always visible.
- **Known gap:** recipes (`OutboxRecipeUsageRecorder`) and meal comparison (`DecisionViewModel`) record `FEATURE_USED`. Inventory and restaurant screens do not call `enqueueUsage` yet, so real data under-counts those two features.

## 2. Contributions

| Issue | PR | Commit | What |
|---|---|---|---|
| S3-10 (CampusMealBack #18) | CampusMealBack #19 | `52954f3` on `main` | `usage_events` table, `POST analytics/usage-events` (202, idempotent by `clientEventId`), `GET analytics/feature-adoption`, `seed:usage`, `docs/SPRINT3_BQ6.md` |
| S3-09 (#22) | #30 | `331110e` | Room outbox (v6), per-account WorkManager sync, BQ8 events and usage events through the outbox |
| Bug: context lost on close | #31 | `b92c68b` | `ContextPreferencesStore`: last context per account in DataStore, no coordinates |
| S3-11 (#24) | #32 | `19d4d74` | Offline comparison history (Room v7), comparison concurrency, BQ6 analyst screen with per-month cache |
| S3-13 (#26) | #33 | `31d3c64` | Signed release build, version 0.3.0, Firebase App Distribution |

Design notes: `SPRINT3_EVENT_SYNC.md`, `SPRINT3_DECISION_BQ6.md`, `BUG_CONTEXT_PERSISTENCE.md`, `SPRINT3_RELEASE.md`.

## 3. My four strategies

Paths are relative to `android/app/src/main/java/com/campusmeal/android/`.

### 3.1 Local storage

| Data | Where | Class |
|---|---|---|
| Pending events (BQ8 impressions and selections, `SESSION_STARTED`, `FEATURE_USED`) | Room `outbox_events` (v6) | `core/outbox/OutboxEventEntity`, `OutboxDao`, `OutboxRepository` |
| Last 10 comparisons per account, as received | Room `comparison_history` (v7) | `feature/decision/data/local/ComparisonHistory.kt` (`ComparisonHistoryStore`) |
| BQ6 reports per account and month | Room `feature_adoption_reports` (v7) | `feature/analytics/data/FeatureAdoption.kt` (`FeatureAdoptionRepository`) |
| Last BQ6 month viewed; last meal context | DataStore `analytics.featureAdoption.month.{accountId}`, `context.last.{accountId}` | `FeatureAdoptionRepository`, `feature/context/data/local/ContextPreferencesStore` |

- **Why Room for events:** an event must survive process death, so it is written to disk *before* any network call. A transaction plus a `clientEventId` primary key gives idempotent inserts.
- **Why DataStore for preferences:** a few key-value pairs with no queries.
- **Privacy:** no coordinates in any table. History stores only the coarse context (campus id, minutes, budget).
- **Account isolation:** history, reports and context are `AccountScopedStore`s registered in `AccountDataCleaner`, deleted on logout or account change. Pending outbox events are kept on purpose: they belong to the account that created them and are only sent with that account's token.
- **Migrations:** `MIGRATION_5_6` and `MIGRATION_6_7` are purely additive (schemas `6.json`, `7.json`).

### 3.2 Caching

- **BQ6 report:**
  - Key `(accountId, month)`, TTL 15 min (`CachePolicy.analyticsReport`), last 12 months.
  - A fresh copy is shown without a request. A stale copy is revalidated, and is shown as "Offline · saved … (may be outdated)" if the backend cannot be reached.
  - Another month never reuses those figures. A 403 deletes the account's reports.
- **Comparison history:** a bounded historical cache that is *never* presented as current. Offline it is labelled "Previous comparison" with its date, and its alternatives cannot be chosen, because a selection must refer to a recommendation the server issued now.
- **Alternatives considered:**
  - An in-memory cache was rejected because it is lost on process death, and BQ6 is meant to be read offline.
  - An HTTP cache (OkHttp) was rejected because it is not account-aware and cannot express "same month only".

### 3.3 Concurrency

- **Outbox delivery:**
  - One unique WorkManager chain per account (`AccountWork.outboxSyncName`, `ExistingWorkPolicy.APPEND_OR_REPLACE`) in `core/outbox/OutboxScheduler.kt`. Two workers never send the same account's events at once, and an event inserted while a worker finishes still gets a run.
  - Batches of 20, `SENDING` reset on start, exponential backoff from 30 s, at most 8 attempts.
- **Admission:** `OutboxRepository.admit` runs inside `AccountSessionCoordinator.runIfCurrent(scope)`. An event of a session that has just ended is rejected, never stored under the next account. There is a cap of 1,000 pending events per account.
- **Comparisons (`feature/decision/presentation/DecisionViewModel`):**
  - A new comparison cancels the one in flight (`compareJob`), and a request counter drops late answers.
  - "Retry" is ignored while loading, and a second tap on "Choose" is ignored.
- **BQ6 screen (`AnalystReportViewModel`):** changing the month cancels the previous load, and an answer for a month no longer shown is dropped.
- **Alternative considered:** a `Mutex` in the app process alone does not survive process death and does not coordinate with WorkManager, so it was not enough for delivery.

### 3.4 Eventual connectivity

- **At-least-once delivery:**
  - Events are stored offline and sent when the network constraint is met, also after the app is killed or the phone restarted.
  - The server deduplicates by `clientEventId`, so a resend after a lost response stores one row.
  - Outcomes are classified: 2xx → sent; network failure or 5xx → retry; 401 → wait for a valid session; other 4xx → failed, not retried forever.
- **Offline UI:**
  - The selection status shows `PENDING` → `SENT`.
  - Decide shows the previous comparison.
  - BQ6 shows the cached month with its date.
  - The context form is restored.
- **Never queued:** a new `POST meal-decisions/compare` is never queued, because it creates a recommendation on the server. It is sent only when the user asks again with a connection.

## 4. Evidence records (to run on a physical phone)

Fields as in `SPRINT3_PLAN.md` §9.1. APK: `0.3.0` (versionCode 2), built from `31d3c64`, distributed through Firebase App Distribution. Reviewer: `bedoya0611`.

| ID | Strategy | Steps (summary) | Expected | Device / Android | Observed | Evidence | Status |
|---|---|---|---|---|---|---|---|
| E-02 | Local storage, Caching | Online: compare, open BQ6 2026-09. Force-close, airplane mode, reopen | "Previous comparison" with date, no "Choose"; BQ6 2026-09 "Offline · saved …"; 2025-01 "No saved report" | | | | Pending |
| E-05 | Eventual connectivity | Airplane mode, choose an alternative, swipe the app away, restore network | Selection delivered once (Logcat `CampusMealOutbox` 202; one row on server) | | | | Pending |
| E-06 | Eventual connectivity | Resend an accepted event (same `clientEventId`) | 202 / duplicate, one row stored | | | | Pending |
| E-07 | Concurrency | Pending events, log out, log in as another account | No event of account A sent with B's token; A's events sync after A logs in again | | | | Pending |
| E-09 | Caching | Open BQ6 as `demo@campusmeal.local` | No Analytics entry; direct request answers 403 | | | | Pending |
| E-10 | Eventual connectivity | Pending events, force-stop from Settings, restore network, reopen | Nothing sent while force-stopped; delivered after reopening | | | | Pending |
| E-11 | Local storage | Install 0.1.0 with data, then 0.3.0 | Migrations 5→6→7 run, no crash | | | | Pending |
| — | Concurrency | Tap "Compare" several times quickly; tap "Choose" twice | One current result; one selection event | | | | Pending |

Development checks already done on the emulator (Medium Phone, Android 17; they do not replace device evidence):

- S3-09: an offline selection was later delivered with 202 for RECOMMENDATION and USAGE events (Logcat).
- Bug fix: the context survives killing the app.
- S3-11: history and BQ6 work offline.
- 0.3.0 release APK: login, restaurants, Decide and BQ6 work against Render.

## 5. Cross-member justification: Natalia's strategies

Reviewer: Daniel. Reviewed code: CampusMealBack PR #21 (S3-07, BQ9, merged `1364c32`), plus the restaurant code currently on Android `main`.

**State at review time (2026-10-29):**

- S3-07 (backend) is merged and deployed on Render, with migration `AddBq9DemandContext` applied on Neon.
- **S3-06 (#19) and S3-08 (#21) have no branch or PR yet.** The current restaurant cache on Android (`feature/restaurants/data/RestaurantRepository`) was written by Juan Pablo in S3-02, so it is not evidence for Natalia.
- The Android parts below must be completed and reviewed when those PRs exist.

**BQ9 — Type 4 — Natalia:** "Which anonymized meal categories, price ranges, and hours show the highest demand in each campus zone, and how could these aggregated trends support restaurant partners' menu and promotion decisions?"

### 5.1 Local storage

- **What is implemented (backend):**
  - Migration `1790700000000-AddBq9DemandContext` adds `meals.meal_category`, `recommendation_runs.zone` and `recommendation_runs.synthetic`, and backfills the categories of the deployed demo catalog.
  - `RecommendationsService` stores the **zone** derived from the campus (`zoneFromCampusId`: `campus-00x` → `bogota-centro` / `chapinero` / `teusaquillo` / `unknown`).
  - It also stores the selected meal's **category snapshot** in the alternative's metadata.
- **Why it fits:**
  - Demand is attributed to what was recommended *at that moment*: editing a meal's category later does not rewrite history (scenario E-15).
  - Only a coarse zone is stored, never coordinates. Using an enum instead of free text in `usage-events` closes the door to sending anything finer.
- **Limitation:** rows created before the migration have `zone` and category `NULL` and are reported as `unknown`.
- **Pending (S3-06/S3-08):**
  - Room `restaurant_search_cache` and `zone_demand_report_cache`.
  - DataStore filters for BQ9.
  - `context.lastForm.{accountId}`: already provided by `ContextPreferencesStore` (PR #31); Natalia can reuse it instead of creating a second store.

### 5.2 Caching

- **Backend:** there is no server-side cache. That is acceptable for an analyst report, since the query is bounded to ≤ 92 days and computed on demand.
- **Pending (S3-08):** the Android report cache, planned as 15-min TTL, keyed by account and date range. To be consistent with BQ6, a stale copy should be shown only for the *same* range, and deleted on 403.
- **Pending (S3-06):** the restaurant search cache keyed by account and context, with a 5-min TTL.

### 5.3 Concurrency

- **Backend:** `zoneDemand` runs two independent read queries (usage events and WALK/ORDER selections) one after the other, then aggregates in memory.
  - Running them with `Promise.all` would cut latency. With ≤ 92 days of data the sequential version is acceptable, but it is the first optimization if the report is slow on Render.
  - The endpoint only reads, so concurrent requests cannot conflict.
- **Pending (S3-06):** cancel superseded searches (one `Job` per query, plus a `queryId` check against late responses), scenario E-04.
- **Pending (S3-08):** cancel the previous report load when the date range changes.

### 5.4 Eventual connectivity

- **Backend:**
  - Usage events are bucketed by `occurred_at`, the time on the phone, in America/Bogota. Events delivered late by the outbox therefore land in the hour and day they happened, not when they arrived.
  - Events without a zone or category are kept as `unknown` instead of being rejected, so offline-created events are never lost.
- **Privacy under sparse data:** cells with fewer than 5 distinct users are suppressed, so a small offline batch can never single out a user.
- **Limitations:**
  - The usage half of BQ9 reads `FEATURE_USED` for `RESTAURANT_SEARCH`/`RESTAURANT_DETAIL` with `zoneId`, `foodCategory` and `budgetBand`. Android does not record those events yet, and `UsageEventDto` has no such fields, so today only the WALK/ORDER selection half has real data.
  - There is no BQ9 demo seed (`synthetic = true`), so with fewer than 5 users per cell the demo report is mostly suppressed.
  - BQ9 documentation (`docs/SPRINT3_BQ9.md`, `API_CONTRACT.md`) is missing.
- **Pending (S3-06/S3-08):** offline restaurant results labelled as historical, search and detail events through the outbox, and the offline report from cache.

### 5.5 Reproduced scenario

To run once S3-08 is available: **E-09**. As `demo@campusmeal.local` (USER), `GET analytics/zone-demand?from=2026-10-01&to=2026-10-29` returns 403 and the Android screen shows "not authorized" with no cached data.

Already observed on 2026-10-29 against Render: the endpoint is deployed (listed in Swagger) and returns 401 without a token.

| Device / Android | Observed | Evidence | Status |
|---|---|---|---|
| | | | Pending |
