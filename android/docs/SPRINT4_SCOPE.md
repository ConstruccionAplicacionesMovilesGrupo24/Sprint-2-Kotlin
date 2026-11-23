# Sprint 4 — Scope, baseline and frozen contracts (S4-01)

Tracking issue: [S4-01 / #38](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/38).
Owner: Juan Pablo (@bedoya0611). Reviewer: Natalia (@NataliaEspitia).
Plan: `Sprint4_plan.md` (planning date 18 Nov 2026).

**Status of this document.** The contracts in §5–§9 are the S4-01 proposal. They become frozen when the
reviewer approves the S4-01 PR; later changes are made by PR to this file, never only in code. Evidence
fields that need a physical phone are left empty on purpose: nothing below is a fabricated result.

## 1. Baseline

| Item | Value | How it was checked |
|---|---|---|
| Android repository | `Sprint-2-Kotlin` (local clone remote: `Sprint-2.git`, redirected) | `git remote -v` |
| Android baseline SHA | `207a08200ee255f2ea3ad135217f079aa537f623` (`main`, 2026-11-23) | `git rev-parse HEAD` |
| Backend baseline SHA | `82ea227c18f66e8164d7da356042e301832ab8ea` (`main`, 2026-11-23, after `git pull`) | `git log` |
| App version | `versionName 0.4.0`, `versionCode 4`; release minification off | `android/app/build.gradle.kts` |
| Room schema | v9, schemas 1–9 exported, migrations `MIGRATION_1_2` … `MIGRATION_8_9`, no destructive fallback | `CampusMealDatabase.kt`, `DatabaseMigrations.kt`, `app/schemas/` |
| Latest backend migration | `1790700000000-AddBq9DemandContext.ts` | `src/database/migrations/` |
| Backend roles | `USER`, `ANALYST` | `src/users/enums/user-role.enum.ts` |

### 1.1 Build results (2026-11-23, Windows 11, JDK Temurin 21.0.8)

| Build | Command | Result |
|---|---|---|
| Android, S4-01 branch (baseline + §3 change) | `./gradlew :app:assembleDebug` | Success; `app-debug.apk` produced |
| Backend, baseline | `npm run build` and `npm run lint` | Success, no lint errors |

Not executed yet (owner: Juan Pablo, before closing S4-01): the inherited flows on a physical phone against
the deployed backend (§2.2). The release build was not produced in S4-01: it needs the signing settings and
belongs to S4-14.

## 2. Inherited Sprint 2/3 inventory

### 2.1 Features by origin

| Feature / BQ | Origin | Android owner | Source state at baseline | Sprint 4 treatment |
|---|---|---|---|---|
| Authentication, session refresh, account scope, profile | S2, extended in S3 | Juan Pablo | Keystore session, refresh interceptor, session generation checks, Room `account_profile` | Preserve; re-run account change and transient refresh (§2.2) |
| Expiring inventory / BQ2 | S2, extended in S3 | Juan Pablo | Complete Room snapshot, drafts, unknown-outcome reconciliation, pending consumes | Preserve; **usage event added in S4-01 (§3)** |
| Recipes / BQ3 | S3 | Juan Pablo | Catalog cache, evaluation on `Dispatchers.Default`, 5-result memo, `FEATURE_USED{RECIPES}` | Preserve; S4-05 optimizes the ingredient lookup |
| Context-aware restaurants / BQ4 | S2, extended in S3 | Natalia | Exact-context Room caches, detail cache, cancellation; `RESTAURANT_SEARCH` and `RESTAURANT_DETAIL` usage events **already emitted** | Preserve; S4-08 removes the duplicate Room read |
| Cook / Walk / Order / BQ5 | S2, extended in S3 | Daniel | Comparison history, recent decision, follow-up actions; `FEATURE_USED{MEAL_COMPARISON}` | Preserve backend scoring authority |
| Recommendation explanations / BQ8 | S2 | Original BQ author: Carlos. Android integration: Daniel | Impressions/selections through the outbox | Keep both credits |
| Usage outbox and BQ6 | S3 | Daniel | Room `outbox_events` + unique WorkManager chain per account; analyst report | Extend additively (§6) |
| Zone demand / BQ9 | S3 | Natalia | Backend aggregation, Android report with cache and 403 handling | Reused by BQ10 |

### 2.2 Unresolved inherited items

Every item needs executed evidence before it is considered closed; merged code alone does not close it.

| # | Item | Found in | Owner | Resolution |
|---|---|---|---|---|
| G-1 | Inventory emitted no `FEATURE_USED{INVENTORY}`, so BQ6 undercounted inventory adoption | Source search: `UsageFeature.INVENTORY` had no caller | Juan Pablo | **Code fixed in S4-01 (§3).** Device evidence pending (§3.3) |
| G-2 | `SPRINT3_DELIVERY_DANIEL.md` still says restaurant search/detail events are pending, but `RestaurantResultsViewModel` and `RestaurantDetailViewModel` emit them | §5.1–§5.4 "Pending (S3-06/S3-08)" notes of that file vs. source | Daniel | Correct the text, linking the source lines; verify the events once on a phone |
| G-3 | Daniel's device records E-02, E-05, E-06, E-07, E-09, E-10, E-11 and the double-tap row are empty (`Pending`), as is the reproduced BQ9 scenario | `SPRINT3_DELIVERY_DANIEL.md` §4 and §5.5 | Daniel | Run them on the 0.4.0 APK (or the S4-01 APK) and fill the table |
| G-4 | Cross-reviews not written: Natalia→Juan Pablo is a template ("Rewrite these notes in your own words"), Juan Pablo→Daniel and Daniel→Natalia say "To be completed" | `SPRINT3_DELIVERY.md` §6 | Each reviewer | Each reviewer writes their own review; no text is written on someone else's behalf |
| G-5 | `SPRINT3_RELEASE.md` describes `0.3.1 / code 3`, while Gradle says `0.4.0 / code 4` | Release doc vs. `build.gradle.kts` | Daniel | Record which APK was actually distributed (version, hash, SHA); S4-14 publishes the next one with `versionCode ≥ 5` |
| G-6 | Outbox purges every row older than 80 days, including still-pending ones, with no visible state | `OutboxDao.purgeOlderThan`, `OutboxRepository.RETENTION_DAYS` | Daniel (S4-10) | Policy in §7 |
| G-7 | Inherited scenarios not yet re-run for Sprint 4: offline reopen, unknown inventory mutation outcome, account change, outbox replay, analyst 403 | Plan §6.1 | Juan Pablo (inventory, recipes, session); Natalia (restaurants, BQ9); Daniel (outbox, BQ6, comparison) | Run on the same APK; record device, Android version, APK SHA, expected vs. observed |

## 3. Fix: inventory usage event (G-1)

### 3.1 Definition

The Sprint 3 contract (`SPRINT3_PLAN.md` §4.2) defines `FEATURE_USED` as: *a core feature's main content
state is displayed after a user action, once per screen visit; offline use counts.* For inventory:

- **Visit:** one `InventoryViewModel`, which is one back-stack entry of the Inventory tab. Its id is a UUID
  stored in `SavedStateHandle`, so it survives rotation, process recreation and tab switches that restore
  the entry (`saveState`/`restoreState`). Leaving through Home pops the entry, so the next entry is a new
  visit.
- **Main content:** a synchronized snapshot is on screen, including a synchronized **empty** inventory.
  "Never synchronized on this device" (`snapshot == null`) is missing data, not a use, and records nothing.
- **Not counted again:** recomposition, refresh, reconnect, drafts, consume, the midnight re-check, opening
  Recipes and coming back.

### 3.2 Implementation

| File | Change |
|---|---|
| `feature/inventory/presentation/InventoryViewModel.kt` | `visitId` in `SavedStateHandle`; `onMainContentShown()` admits `enqueueUsage(scope, INVENTORY, clientEventId = visitId)` once (`inventory.usageOffered` flag), inside `NonCancellable`; `OutboxRepository` injected by the factory |
| `feature/inventory/presentation/InventoryScreen.kt` | `LaunchedEffect(mainContentShown)` with `mainContentShown = state.loaded && state.snapshot != null` |

Properties inherited from the outbox (unchanged): the row is written to Room inside the coordinator lock
before any network attempt; a different account's scope is rejected; the same `clientEventId` is stored once
on the device (`OnConflictStrategy.IGNORE`) and once on the server (`ON CONFLICT (user_id, client_event_id)
DO NOTHING`). The backend already accepts `feature = INVENTORY` (`usage-feature.enum.ts`). No Room or backend
migration is needed.

### 3.3 Device checks (pending)

| Scenario | Expected | Device / Android / APK | Observed | Status |
|---|---|---|---|---|
| Open Inventory online with items | One `FEATURE_USED(INVENTORY) admitted` log; one row on the server | | | Pending |
| Rotate, refresh, open Recipes and return, switch to Decide and back | No second admission | | | Pending |
| Home → Inventory → Home → Inventory | Two events (two visits) | | | Pending |
| Airplane mode with a stored snapshot, open Inventory, kill the process, restore network | Event stays `PENDING`, delivered once after reopening | | | Pending |
| Fresh install offline (no snapshot) | No event; "not synced" state | | | Pending |
| Synchronized empty inventory | One event | | | Pending |
| Pending event, logout, login as another account | Event sent only after the original account signs in again | | | Pending |
| BQ6 report for the month | `INVENTORY` use counts include the new events | | | Pending |

## 4. Business questions (original wording, unchanged)

| BQ | Type | Owner | Original wording (Sprint 1 wiki) |
|---|---|---|---|
| BQ13 | 4 | Juan Pablo | Which restaurant partners keep menus and prices updated within a seven-day window, and does that update frequency correlate with higher student selection rates and review scores? |
| BQ10 | 5 | Natalia | Which restaurant categories have high anonymized demand but low current availability in each campus zone, so CampusMeal can recommend suitable alternatives to users while providing aggregated opportunity reports to restaurant partners? |
| BQ1 | 1 | Daniel | Which device model, operating-system version, and application version combinations have a crash rate above 1% or a 95th-percentile loading time above two seconds on the meal-comparison screen? |

No BQ is added to or retyped in the catalogue. BQ8 stays credited to Carlos as its original author; Daniel is
recorded as the owner of its Android integration.

## 5. Additive API contracts

Common rules for every new endpoint:

- Additive only: existing endpoints, DTOs and the Swift client keep working. New fields are never added to
  the strict `POST analytics/usage-events` DTO.
- Owner identity always comes from the access token; it is never in a body.
- Reports are `ANALYST` only (`@Auth(UserRole.ANALYST)`; `USER` → 403). Student endpoints use `@Auth()`.
- Every response carries `contractVersion` (starts at `1`), `generatedAt` and, where relevant, `asOf`, the
  echoed query, coverage fields and a `synthetic` indicator.
- Behavioral and review aggregates suppress cells with fewer than **5 distinct users** (value `null` and
  `suppressed: true`), including derived totals that would reveal a suppressed cell.
- Dates in query strings are Bogotá calendar dates `YYYY-MM-DD`; ranges are inclusive and at most 92 days,
  like BQ9. Instants are ISO-8601 UTC ending in `Z`.
- New migrations in CampusMealBack use timestamps after `1790700000000`.

### 5.1 Juan Pablo — reviews and BQ13 (S4-03, S4-04)

**`POST /api/v1/restaurants/:id/reviews`** (`@Auth()`)

```json
{
  "clientReviewId": "uuid-v4",
  "overall": 4, "portion": 3, "taste": 5, "freshness": 4, "value": 3,
  "waitMinutes": 12,
  "occurredAt": "2026-11-23T17:05:00Z"
}
```

- Scores are integers 1–5; `waitMinutes` is an integer 0–180. No free text.
- `occurredAt`: at most 5 minutes in the future and at most 30 days old. `reviewDate` is its Bogotá date.
- Unique `(user_id, client_review_id)` and unique `(user_id, restaurant_id, review_date)`.
- `201` with the stored review on creation; `200` with the same body when the same `clientReviewId` is
  replayed with the same content (idempotent replay after a lost acknowledgement).
- `409 REVIEW_ALREADY_SUBMITTED_TODAY` for a different `clientReviewId` on the same restaurant and Bogotá day;
  `409 CLIENT_REVIEW_ID_CONFLICT` for the same id with different content; `404` unknown restaurant;
  `400` validation. Android treats every 4xx except 401/408/429 as permanent (visible "rejected" state).

Response: `{ contractVersion, reviewId, clientReviewId, restaurantId, reviewDate, createdAt }`.

**`GET /api/v1/restaurants/:id/reviews?limit=20`** (`@Auth()`, `limit` 1–50)

```json
{
  "contractVersion": 1, "restaurantId": "…", "generatedAt": "…",
  "summary": {
    "source": "STUDENT_SELF_REPORTED",
    "reviewCount": 7, "distinctReviewers": 6, "suppressed": false,
    "meanOverall": 3.9, "meanPortion": 3.4, "meanTaste": 4.1, "meanFreshness": 3.7, "meanValue": 3.3,
    "meanWaitMinutes": 11.5
  },
  "recent": [ { "reviewDate": "2026-11-22", "overall": 4, "portion": 3, "taste": 5, "freshness": 4, "value": 3, "waitMinutes": 12 } ],
  "catalogRating": { "value": 4.5, "provenance": "CATALOG_SEED" }
}
```

No author id or name is returned. The seeded `averageRating` is reported separately and never mixed into the
review means. Means are `null` and `suppressed: true` with fewer than 5 distinct reviewers.

**Menu revisions.** New append-only table `menu_revisions` (`id`, `restaurant_id`, `meal_id` nullable,
`change_type` `MENU_ITEM_ADDED | MENU_ITEM_REMOVED | PRICE_CHANGED | AVAILABILITY_CHANGED`,
`changed_at`, `source` `MAINTENANCE_SCRIPT | SYNTHETIC_SEED`, `revision_note`). It is written in the same
transaction as the menu edit by a maintenance service invoked from a CLI script (`npm run menu:edit …`). No
HTTP mutation endpoint and no new role are added; `ANALYST` gets no write rights. Rows are only created when
content or price actually changes. History before the first revision is **unknown**; `createdAt` and the
generic `updatedAt` are never relabeled as menu maintenance.

**`GET /api/v1/analytics/partner-health?from=…&to=…`** (`ANALYST`)

Per restaurant: `menuStatus` (`FRESH` = confirmed revision within the 7 Bogotá calendar days ending at the
cutoff, `STALE` = revisions exist but none in that window, `UNKNOWN` = no revision recorded),
`lastRevisionAt`, `daysSinceRevision`, `revisionsInWindow`, review count/mean (suppressed below 5 reviewers),
`exposures`, `selections`, `selectionRate`.

- Exposure: one distinct recommendation with a recorded impression that offered the restaurant (WALK and
  ORDER alternatives of the same run count once). Selection: a selection of that restaurant within those
  exposed recommendations. `selectionRate = selections / exposures`, `null` when `exposures = 0`.
- Cohorts `FRESH`, `STALE`, `UNKNOWN` with pooled rates and review means.
- `association`: Spearman rank correlation between `revisionsInWindow` and `selectionRate` (and separately
  review mean) across restaurants with non-suppressed values; `INSUFFICIENT_DATA` with fewer than 5 such
  restaurants or a constant variable. Always labeled observational, with sample sizes and coverage dates.

### 5.2 Natalia — BQ10 and alternatives (S4-06, S4-07)

Frozen essentials; the field-level DTO is written by Natalia in `docs/SPRINT4_BQ10.md` and linked here.

- Restaurant zone: new nullable `restaurants.analytics_zone` column using the existing `AnalyticsZone`
  values (`bogota-centro`, `bogota-chapinero`, `bogota-teusaquillo`), backfilled by an explicit per-restaurant
  list in the migration. `null` = unknown zone. Never inferred from a user's location.
- Demand: deduplicated `RESTAURANT_SEARCH` usage events with non-null `foodCategory` in the window; detail and
  selection events are excluded from this count.
- Policy version 1: high demand = ≥ 5 distinct searchers **and** category share ≥ 20 % of categorized search
  intents in the zone; low supply = ≤ 1 active, currently open restaurant with ≥ 1 available meal in the
  category at `asOf`. Suppressed or unknown demand never creates a gap.
- `GET /api/v1/analytics/supply-gaps?zone=…&from=…&to=…` (`ANALYST`): demand window and supply `asOf` shown
  separately, with `policyVersion`, counts and denominators.
- `POST /api/v1/restaurants/alternatives` (`@Auth()`): campus, category, available minutes, budget, diet;
  returns eligible alternatives with the substitution explanation and constraint checks, or an explicit
  no-compatible-result state. No behavioral counts in this response. Computed online; no outbox kind.

### 5.3 Daniel — BQ1 telemetry (S4-09, S4-10)

Frozen essentials; field-level DTO in `docs/SPRINT4_BQ1.md`.

- `POST /api/v1/analytics/performance-events` (`@Auth()`), one event per request like the existing usage
  endpoint, idempotent by `(user_id, client_event_id)`, `202`.
- Types: `SESSION_START` (`sessionId`), `FATAL_CRASH` (`sessionId`, exception class name only),
  `COMPARISON_LOAD` (`loadId`, `durationMs`, `outcome` `FRESH_NETWORK | CACHED_RESTORE | OFFLINE_HISTORY |
  ERROR | TIMEOUT | CANCELLED`). Common: `deviceModel`, `osVersion`, `appVersion`, `platform`, `occurredAt`.
  No tokens, coordinates or exception messages.
- `GET /api/v1/analytics/performance?from=…&to=…` (`ANALYST`): grouped by device model, OS version and app
  version; crash rate and nearest-rank p95 of `FRESH_NETWORK` loads; flags strictly above 1 % / 2000 ms;
  `null` on empty samples; small-sample label.

## 6. Outbox extension

| Kind | Endpoint | Payload | Owner |
|---|---|---|---|
| `RECOMMENDATION` (existing) | `POST analytics/events` | unchanged | Daniel |
| `USAGE` (existing) | `POST analytics/usage-events` | unchanged | Daniel |
| `REVIEW` (new) | `POST restaurants/:id/reviews` | `{"v":1,"restaurantId":"…","body":{…§5.1…}}`; `clientEventId` = `clientReviewId` | Juan Pablo |
| `PERFORMANCE` (new) | `POST analytics/performance-events` | `{"v":1,"body":{…§5.3…}}` | Daniel |

- Existing rows have no envelope and keep being decoded as today. New kinds use the `v` field; an unknown
  `v` is a permanent failure (`unreadable payload`), never a crash or a silent drop.
- One drain per account (the existing unique WorkManager chain). No second worker is added for any kind.
- A row is only sent with the token of the account that created it (unchanged).
- Daniel integrates `OutboxRepository.send` and the scheduler; Juan Pablo supplies the `REVIEW` branch
  through a PR against that integration, Daniel the `PERFORMANCE` branch.
- `REVIEW` acknowledgement: `201` or `200` → `SENT`, and the review summary cache for that restaurant is
  invalidated. `409` → `FAILED` with the code shown to the user.

## 7. Retention policy (G-6)

Facts at baseline: the backend refuses usage events whose `occurredAt` is more than **90 days** old
(`MAX_EVENT_AGE_DAYS = 90`); Android deletes every outbox row whose `createdAt` is more than **80 days** old,
whatever its status, and deletes `SENT` rows after 1 day. A pending row that is never delivered is therefore
removed silently.

Policy for Sprint 4 (implemented by Daniel in S4-10, since the sync center is where it becomes visible):

1. Pending and failed rows are never deleted silently. After 80 days a `PENDING` row becomes `EXPIRED`
   (new status value, no schema change: `status` is a string) with `lastError = "expired before delivery"`.
2. `EXPIRED` and `FAILED` rows are listed in the synchronization center and can be dismissed by the user;
   they are purged 30 days after expiring/failing.
3. `SENT` rows keep the 1-day purge.
4. Review rows follow the review endpoint's 30-day `occurredAt` limit: a `REVIEW` still pending after 30 days
   becomes `EXPIRED` and is shown on the review screen as "could not be delivered".
5. The admission cap stays at 1,000 `PENDING`/`SENDING` rows per account; a full outbox returns
   `OutboxAdmission.Rejected("outbox full")` (unchanged). Read-cache pruning never deletes admitted work.

## 8. Local storage and cache bounds (initial policies)

| Feature | Storage | Bound | TTL | Key |
|---|---|---|---|---|
| Review drafts | Room, account-owned | 1 draft per account and restaurant | — | `accountId + restaurantId` |
| Review summaries | Room | latest 50 per account (LRU by access time) | 15 min; invalidated on confirmed submission | `accountId + restaurantId + contractVersion` |
| BQ13 report | Room | 20 queries per account | 15 min | `accountId + from + to + contractVersion` |
| BQ13 filters | DataStore | 1 per account | — | `partnerHealth.lastRange.{accountId}` |
| Alternatives | Room | 20 queries per account | 5 min | account + campus + category + time + budget + diet + contractVersion (no coordinates) |
| BQ10 report | Room | 20 queries per account | 15 min | account + zone + from + to + contractVersion |
| BQ1 report | Room | 20 queries per account | 15 min | account + from + to + contractVersion |
| Crash marker | Small file in `noBackupFilesDir`, written synchronously by the handler | 1 marker | until durably admitted | session id |

All account-owned writes check the captured session scope; late responses are dropped when the scope or query
changed. A 403 deletes the matching report and blocks cached access. Logout/account change clears read caches
and drafts through `AccountDataCleaner`; admitted outbox rows keep their original owner.

## 9. Migration and shared-file integration order

**Room (coordinated by Juan Pablo).** Nobody reserves a version number in advance. The first schema PR to be
approved takes v10 (`MIGRATION_9_10`), the next one rebases and takes v11, and so on. Each PR exports its
schema JSON, adds the migration to `DatabaseMigrations.ALL`, and is checked by upgrading an installed v9 APK
that has drafts and pending outbox rows. Expected order by dependency, not a reservation:
reviews/BQ13 cache (S4-04) → alternatives/BQ10 cache (S4-07) → BQ1 cache (S4-10). The `EXPIRED` status
needs no schema change.

**Backend.** Each owner adds additive migrations with timestamps after `1790700000000`. Deploy to Render/Neon
before the dependent Android acceptance. `npm run build`, `npm run lint` and `migration:run` on a fresh
database are part of each backend PR.

**Navigation and container.** `CampusMealRoute`, `CampusMealNavHost` and `AppContainer` are edited by every
owner; merge one PR at a time and rebase the next. Each owner adds only their own destinations.

**Branches.** One short branch per issue from current `main` (`sprint4/s4-NN-short-name`), one focused PR,
linking the issue and listing build and device validation. Optimization commits are separate from feature
commits.

## 10. S4-01 acceptance checklist

- [x] Baseline SHAs and build results recorded (§1).
- [x] Inherited feature inventory and outstanding checks recorded, each with an owner and a resolution (§2).
- [x] Inventory usage event implemented, once per visit, through the durable outbox (§3).
- [ ] Inventory usage event verified on a physical phone, online, offline and across restart (§3.3).
- [x] Original BQ text, types and owners recorded (§4).
- [x] Additive API contracts, outbox kinds, retention, bounds and migration order proposed (§5–§9).
- [ ] Contracts approved by the reviewer (Natalia) and acknowledged by Daniel for §5.3, §6 and §7.
- [ ] Inherited scenarios G-7 executed with recorded evidence.
