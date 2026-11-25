# Sprint 4 — Juan Pablo (@bedoya0611)

Issues: [S4-01 / #38](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/38),
[S4-03 / #39](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/39),
[S4-04 / #40](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/40),
S4-05 / #41, S4-12 / #42. Reviewer: Natalia (@NataliaEspitia).

Backend under test for every result below: `https://campusmeal-api.onrender.com/api/v1/` (Render + Neon).
No local or tunnelled backend was used.

## 1. Pull requests

| Issue | Repository | PR | State |
|---|---|---|---|
| S4-01 | Sprint-2-Kotlin | #52 (scope, inventory usage event) | Merged (`dd2f89d`) |
| S4-03 | CampusMealBack | #24 (reviews, menu revisions, BQ13 endpoint) | Merged (`da297a4`), deployed to Render, migration applied on Neon |
| S4-03 | CampusMealBack | #25 (Render evidence, visible demo reviews) | Merged (`7ca52c7`) |
| S4-04 | Sprint-2-Kotlin | _this PR_ (`sprint4/s4-04-reviews-partner-health`) | Open |

## 2. S4-04 — what was built

BQ13 (Type 4, original wording in `SPRINT4_SCOPE.md` §4) and the student review feature that feeds it. It is
not BQ9 and is not presented as BQ9.

### 2.1 New destinations

| Destination | Role | Entry | States |
|---|---|---|---|
| `ReviewMealScreen` (`CampusMealRoute.ReviewMeal`) | USER (any signed-in account) | "Write a review" in the new *Student reviews* card of the restaurant detail | loading draft, editing, validation error, offline notice, saving, admitted, own reviews with delivery state |
| `PartnerHealthScreen` (`CampusMealRoute.PartnerHealthReport`) | ANALYST | Profile → Analytics → "BQ13 · Partner health" (shown to ANALYST; the backend enforces 403) | loading, content (network / saved / saved and outdated), empty values, offline without copy, 400 range, 403 (access removed), session expired |

Both have back navigation and are independent back-stack entries.

### 2.2 Local storage (Room v10, `MIGRATION_9_10`, additive)

| Table | Content | Bound / lifetime | Account lifecycle |
|---|---|---|---|
| `review_drafts` | Scores (nullable) and the raw waiting-time text, per account and restaurant | 1 per account and restaurant; deleted when submitted or cleared | Deleted on logout and when another account activates |
| `review_submissions` | Every explicitly submitted review with `deliveredAt` / `rejectionReason` | Written in the **same transaction** as the outbox row | Undelivered rows keep their owner (admitted work, like the outbox); settled rows are deleted on logout / account change |
| `review_summary_cache` | Last public summary JSON per account and restaurant | 15 min TTL, LRU 50 per account, `invalidatedAt` set when one of the account's reviews is confirmed | Deleted on logout / account change |
| `partner_health_reports` | BQ13 JSON per account, `from`, `to`, `includeSynthetic`, `contractVersion` | 15 min TTL, 20 per account | Deleted on logout / account change and on **403** |

DataStore: `partnerHealth.lastRange.{from,to,includeSynthetic}.{accountId}` (last BQ13 filters per account).

### 2.3 Durable delivery (outbox `REVIEW` kind, `SPRINT4_SCOPE.md` §6)

- Explicit submission creates `clientReviewId` (UUID) once. `OutboxRepository.enqueueReview` stores the outbox
  row `{"v":1,"restaurantId":…,"body":{…}}`, the `review_submissions` row and the draft deletion in one Room
  transaction under the session lock: either all of them exist or none.
- Delivery uses the existing per-account drain and unique WorkManager chain; no new worker. The row is sent only
  with its owner's token. A lost acknowledgement resends the same `clientReviewId`; the backend answers 200 with
  the same receipt (S4-03 check 11: five concurrent sends → one row).
- `OutboxDeliveryListener` (new, additive) tells `ReviewRepository` the outcome: 2xx → `deliveredAt` and
  summary invalidation; permanent 4xx → `rejectionReason` = `HTTP <code> <API_ERROR_CODE>`.
- Unknown `v` → permanent `unreadable payload` (never a crash or silent drop). BQ8/BQ6 payloads unchanged.

Delivery states shown to the user: **Queued**, **Sending…**, **Waiting for confirmation** (sent at least once
without an answer; resent with the same id), **Sent ✓**, **Rejected** (with the reason), **Could not be
delivered** (outbox row gone, never acknowledged). Pending own reviews are listed apart and never mixed into the
server summary.

### 2.4 Concurrency and connectivity

- Review form: every edit writes the Room draft; draft writes and the submission share one `Mutex`, so they run
  in order and a late draft write cannot resurrect a submitted form (it re-checks the form under the lock). The
  admission runs in `NonCancellable`. Double taps are ignored while `submitting`.
- Same-day rule mirrored locally (`activeSubmissionsOn`) to avoid a predictable 409; the server stays authoritative.
- Summary card: its own ViewModel; a new request cancels the previous one; answers from another session are
  dropped; it observes the cache invalidation flow and reloads when a review is confirmed; reconnect reloads
  anything not from the network (`ReconnectEffect`).
- BQ13: a filter change cancels the previous request; answers for other filters or another session are dropped;
  answers whose echoed `from`/`to`/`includeSynthetic` differ from the request are never stored. 403 deletes the
  account's saved reports and disables further requests on the screen.
- Flows are collected with `collectAsStateWithLifecycle`; own submissions use `stateIn(WhileSubscribed(5 s))`.

### 2.5 BQ13 presentation rules

- `UNKNOWN` = no revision recorded ("update history unknown"), never "stale" or "never updated".
- Zero vs suppressed vs missing: `0 exposures, no selection rate` / `hidden (fewer than 5 users)` / `—`.
- Cohorts show how many restaurants were hidden and therefore not pooled.
- Associations: `Spearman ρ` with the number of restaurants, or "Not enough data (n …)" with the backend reason.
  Labeled observational, not causal; backend `limitations[]` listed.
- `Include synthetic demo data` switch (off by default) and an "Includes synthetic demo data" tag.

## 3. Build

| Item | Value |
|---|---|
| Branch | `sprint4/s4-04-reviews-partner-health` from `main` `dd2f89d` |
| Command | `./gradlew :app:assembleDebug` — success, no compiler warnings in the touched files |
| Room schema | `app/schemas/…/10.json` exported; its `createSql` matches `MIGRATION_9_10` statement by statement |
| API base URL | Render (`local.properties`, debug and release) |
| Debug APK SHA-256 | `2de9bcfe883e683aa128cfd3dc9f37f30228ef0bc9eed749665fed5edfa56c97` (final branch build). E1–E15 ran on the build just before it, which differs only in BQ13 singular/plural labels; the final APK was smoke-tested on the BQ13 screen |

## 4. Validation on an emulator (2026-11-25, AVD `Tusky_API_36`, Android 16)

Executed by Claude Code through `adb` against Render + Neon. Accounts: the demo USER and the demo ANALYST.
These are emulator results, **not** the physical-device evidence the plan requires (§5).

| # | Scenario | Expected | Observed |
|---|---|---|---|
| E1 | Install `main` (Room v9), log in, search, open Green Bowl; airplane mode; reopen → pending usage rows | v9 DB with a cached detail and `PENDING` outbox rows | `user_version 9`, 1 cached detail, `USAGE PENDING` rows |
| E2 | Install the S4-04 APK over it (`adb install -r`), still offline | Migration 9→10, session and data kept, no crash | No crash; Home shown signed in; `user_version 10`; 4 new tables; all `PENDING` rows and the detail cache kept |
| E3 | Offline: detail of Green Bowl | Card says no saved reviews, offline | "No saved reviews for this restaurant on this device. Connect to load them." + "Write a review" |
| E4 | Offline: rate 3 of 5 aspects, force-stop, reopen the form | Draft restored | Overall 5, Portion 4, Taste 5 restored; "Draft saved on this device." (Room row checked) |
| E5 | Offline: complete and submit | Draft deleted; submission and `REVIEW` row stored together | `review_drafts` 0 rows; `review_submissions` 1 row; outbox `REVIEW PENDING attempts 0` with the `{"v":1,…}` envelope |
| E6 | Force-stop, reopen offline, then disable airplane mode | Sent once after reconnection | `REVIEW … status=201` in the drain log, along with the pending usage events; submission `delivered`; summary 7 → **8 reviews from 8 students** |
| E7 | Second review of Green Bowl the same day | Refused locally | "You already reviewed this restaurant today…" |
| E8 | Quinoa Corner: summary cached (6 reviews), submit online, go back | Delivery invalidates the cache, the card reloads by itself within the 15-min TTL | `REVIEW 201`; card shows **7 reviews from 7 students** without a manual refresh |
| E9 | Analyst submits offline (Green Bowl), logs out; demo USER logs in online; wait 25 s | The analyst's review is not sent with the USER's token | Only the USER's usage event was sent; analyst `REVIEW` stayed `PENDING attempts 0` |
| E10 | USER logs out, analyst logs in | The analyst's review is sent | `REVIEW 201`, submission `delivered` |
| E11 | Clear app data (simulates another device), analyst reviews Green Bowl again the same day | Visible permanent rejection with reason | `REVIEW 409`; "Rejected · You had already reviewed this restaurant that day (maybe from another device)." |
| E12 | BQ13 window A `2026-11-14…20`, synthetic on | Equal to `GET partner-health` for the same query | Equal value by value: cohorts FRESH 64.3 % / STALE 27.3 % / UNKNOWN 0.0 %, per-restaurant 6/8, 3/6, 2/6, 1/5, 0/5, ρ = 0.89 (n 5) for both associations; behavior matches S4-03 §6.1. Review counts now include the 30 visible demo reviews (S4-03 §7.2), as the API does |
| E13 | BQ13 window B (‹ Earlier) | S4-03 §6.2 | Green Bowl recommendations and reviews hidden; Andean Flavor FRESH "0 exposures, no selection rate", "0 in range"; Sushi Rápido STALE 13 days; The Garden and Quinoa Corner UNKNOWN; both associations "Not enough data" |
| E14 | Real data only, `2026-11-19…25` | No real revisions → all UNKNOWN; hidden cells not pooled | FRESH 0, STALE 0, UNKNOWN 5 restaurants; UNKNOWN review mean 4.30 from 10 reviews, "3 hidden restaurants not counted"; selection "— (0 of 0 exposures)" |
| E15 | Airplane mode, force-stop, reopen BQ13 | Last filters restored; saved report shown | Window B restored from DataStore; "Saved Nov 25, 09:15"; "Later ›" shows window A "Saved"; 5 cached reports in Room |

Side effect on Neon (real, `synthetic = false`): reviews from the demo USER (Green Bowl, Quinoa Corner) and the
demo ANALYST (Green Bowl) dated 2026-11-25, plus the rejected attempt that stored nothing.

Not executed: the 403 path in the UI (USER accounts do not see the entry; covered by the same logic as BQ9 and
by S4-03 check 14) and a forced lost acknowledgement (covered by backend idempotency, S4-03 check 11).

## 5. Physical-device evidence (pending, owner: Juan Pablo)

Same APK on a phone, against Render + Neon. Fill device, Android version, APK SHA-256 and observation.

| Scenario | Expected | Device / Android / APK | Observed | Status |
|---|---|---|---|---|
| Upgrade from the installed 0.4.0 app with pending items | No crash, data kept | | | Pending |
| Offline draft → kill app → restore | Draft restored | | | Pending |
| Offline submit → restart → reconnect | Sent once (201), summary updated | | | Pending |
| Account change with a pending review | Never sent as the other account | | | Pending |
| BQ13 windows A and B with synthetic data | As in E12–E13 | | | Pending |
| BQ13 offline reopen | Saved report and filters | | | Pending |

## 6. S4-04 acceptance

- [x] Two independently navigable destinations, with back navigation and loading/empty/error/offline states (§2.1; E3, E14, E15).
- [x] A review created offline survives restart and is stored once by the backend after reconnection (E4–E6). Phone evidence pending (§5).
- [x] Changing accounts never publishes or sends the prior account's review as the new user (E9–E10).
- [x] BQ13 matches S4-03 and distinguishes unknown, zero and suppressed (E12–E14).
- [x] Local storage, caching, concurrency and connectivity code by Juan Pablo (§2.2–§2.4).
- [ ] This document links the PR and **physical-device** evidence (§5 pending).
