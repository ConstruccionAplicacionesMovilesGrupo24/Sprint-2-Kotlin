# Sprint 4 — Natalia: alternative meal discovery and BQ10

Tracking: [S4-06 / #43](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/43), [S4-07 / #44](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/44), [S4-08 / #45](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/45). Backend [PR #28](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/28); Android [PR #61](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/61). Reviewer: Daniel (@dvargasl2).

## 1. Scope and business question

**BQ10 — Type 5:** Which restaurant categories have high anonymized demand but low current availability in each campus zone, so CampusMeal can recommend suitable alternatives to users while providing aggregated opportunity reports to restaurant partners?

The new student destination is Alternative Meals (restaurant results → Find alternatives). The analyst destination is Supply gaps (Profile → Analytics → BQ10). The backend evaluates categorized search intents (at least five distinct searchers and at least 20% of categorized searches) against current catalog supply (at most one active, currently open restaurant offering an available meal in the category). The alternative endpoint independently searches for another category that satisfies the current budget, diet and estimated walking/dining time; it does not disclose behavioral counts or require an analyst gap report. Neither endpoint represents purchases or actual inventory.

## 2. Implementation and quality strategies

| Strategy | Implementation | Evidence status |
|---|---|---|
| Local storage | Per-account DataStore filters and Room `bq10_snapshots` with an additive v11 → v12 migration. | Report filters and snapshot survive app reopening; student category survives navigation away and back; upgrade preservation not established. |
| Caching | Exact report key: account, zone, dates, dataset, contract version; 15-minute TTL. Alternatives: five-minute TTL, canonical campus centre and non-precise request context; no reusable snapshot for device coordinates. | Both screens demonstrate fresh cache hits, exact-key misses and historical fallback; TTL expiry and retention bound not experimentally verified. |
| Memory management | At most 20 persisted snapshots per account and kind; current UI state contains only the active result; superseded jobs are cancelled. | Bound verified in source, not measured as heap allocation or tested at 21 entries. |
| Concurrency | `viewModelScope`, cancellation of superseded jobs, request revision and account generation guards; local writes serialized by session coordinator. | Zone changes and correct final result observed; in-flight cancellation not independently established. |
| Eventual connectivity | Exact-key fallback, explicit historical label and timestamp, refresh errors, online revalidation; no outbox replay for alternatives. | Offline cache hit/miss and subsequent online result observed on the physical device. |
| Account isolation | Account-owned storage; cleanup on logout/account change; report cache deletion on HTTP 403; no precise user coordinates in the new persistent storage. | Analyst-to-student-to-analyst navigation and reset filters observed; 403 verified separately at backend; local row deletion not inspected directly. |

## 3. Test environment

- Physical device: Samsung Galaxy A33 5G (SM-A336M), Android 16, API 36, ARM64, as recognized by Android Studio.
- App: CampusMeal 0.4.0, Debug; backend URL configured for Render HTTPS and reinstalled before the tests.
- Android implementation commit: [`f7ace38`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/f7ace38), as reported by the developer. Exact installed APK checksum was not captured.
- Backend: PR #28 deployed to Render; Neon migration 12 applied, integration checks passed, 39 synthetic events verified.
- Physical test date: November 25, 2026 (America/Bogota), approximately 22:50–23:08. Server timestamps are UTC, hence November 26.
- Debug assembly and both existing BQ3 unit tests passed in the developer's local environment; no claim of a dedicated automated Android BQ10 suite.

## 4. Physical-device evidence — analyst

All rows below distinguish observed behavior from source-level guarantees. Screenshots extracted from recordings are marked in section 8.

| ID / scenario | Observed result | Evidence |
|---|---|---|
| B01 Analyst navigation | Analyst profile exposes BQ10; new destination opened. | [Profile](evidence/sprint4/s4-07-bq10/B01-analyst-navigation.png) |
| B02 Centro, Nov 17, synthetic | 25 categorized searches; BOWLS 15/5 users/60%; SOUPS and SUSHI 5/5 users/20% each. Five mapped restaurants; zero available for these three categories in the captured late-night snapshot; all three flagged as gaps. | [Header](evidence/sprint4/s4-07-bq10/B02-centro-online-header.jpg), [Bowls](evidence/sprint4/s4-07-bq10/B02-centro-bowls.png), [Soups](evidence/sprint4/s4-07-bq10/B02-centro-soups.png), [Sushi](evidence/sprint4/s4-07-bq10/B02-centro-sushi.png) |
| B03 Centro, Nov 18, synthetic | Cached report shows unavailable categorized total and protected demand metrics; card shows Demand suppressed. Consistent with deployed API fixture. | [Suppression](evidence/sprint4/s4-07-bq10/B03-suppression-offline.png) |
| B04 Chapinero, Nov 17, synthetic | Five searches; Sushi five users and 100% share, zero mapped restaurants, unknown available count and Supply coverage unknown. | [Full report](evidence/sprint4/s4-07-bq10/B04-unknown-supply.jpg) |
| B05 Fresh cache | Revisiting a previously loaded query displays Saved snapshot, with saved time. | [Fresh cache](evidence/sprint4/s4-07-bq10/B05-fresh-cache.png) |
| B06 Offline hit | Airplane mode; Centro report retains matching historical snapshot and timestamp, explicitly not current availability. | [Offline hit](evidence/sprint4/s4-07-bq10/B06-offline-cache.jpg) |
| B07 Offline miss | Switching to Teusaquillo produces Offline. No matching saved result. No unrelated report is shown. | [Offline miss](evidence/sprint4/s4-07-bq10/B07-offline-no-cache.jpg) |
| B08 Reconnection | Same Teusaquillo query shows Server response at 23:00:59 local time after reconnection. | [Online response](evidence/sprint4/s4-07-bq10/B08-reconnection.jpg) |
| B09 Navigation persistence | Leaving and reopening the report restores Teusaquillo, Nov 17 and synthetic demand. | [Restored filters](evidence/sprint4/s4-07-bq10/B09-restored-filters.png) |
| B10 App reopening | Same query and saved timestamp 23:01:54 before and after closing from recents and reopening. This is not an OS process-death instrumentation test. | [Before](evidence/sprint4/s4-07-bq10/B10-before-restart.png), [After](evidence/sprint4/s4-07-bq10/B10-after-restart.png) |
| C01 Offline after refresh | Existing Centro result remains available as historical. Recording does not conclusively establish that a network request was in flight at disconnect; interrupted-in-flight case remains partial. | [Historical result](evidence/sprint4/s4-07-bq10/C01-offline-after-refresh.png) |
| C02 Changing zones | Centro → Chapinero → Centro observed, ending with correct Centro demand and rows. No stale mismatched result observed in this run; timing of overlapping requests was not instrumented. | [Video](evidence/sprint4/s4-07-bq10/C02-zone-changes-and-demand.mp4) |
| D01 Account isolation | Analyst has report before logout; student profile has no Analytics section; analyst re-login restores default real dataset and Oct 27–Nov 25 dates instead of previous synthetic single-day query. | [Before](evidence/sprint4/s4-07-bq10/D01-analyst-before-logout.png), [Student](evidence/sprint4/s4-07-bq10/D02-student-profile.png), [After](evidence/sprint4/s4-07-bq10/D03-analyst-after-relogin.png) |

The supply counts differ from the earlier API demonstration because supply is evaluated at the response's `asOf` time, not at the historical search date. The demand values remain stable across the matching synthetic queries.

## 5. Physical-device evidence — student

| ID / scenario | Observed result | Evidence |
|---|---|---|
| A01 Meal context | 60 minutes, COP 30,000, vegetarian selected. | [Context](evidence/sprint4/s4-07-bq10/A01-meal-context.jpg) |
| A02 Navigation | Find alternatives appears even when ordinary restaurant search is empty; the distinct Alternative meals destination opens. | [Results](evidence/sprint4/s4-07-bq10/A02-alternatives-navigation.jpg), [Destination](evidence/sprint4/s4-07-bq10/A03-alternatives-initial.jpg) |
| A03 Online response | Category menu contains all six recognized categories. Selecting SUSHI retains budget, diet and time; server reports AVAILABLE routing and no compatible alternatives. Refresh shows a newer saved time (22:51:37 → 22:51:52 local). | [Menu](evidence/sprint4/s4-07-bq10/A03-category-menu.jpg), [First response](evidence/sprint4/s4-07-bq10/A03-first-server-response.jpg), [Refreshed response](evidence/sprint4/s4-07-bq10/A03-alternatives-empty.jpg) |
| A04 Offline cache hit | SUSHI result from 22:51:52 remains visible with Offline, Historical snapshot — not current availability and its original timestamp. Refresh disabled. | [Offline hit](evidence/sprint4/s4-07-bq10/A04-offline-cache.png) |
| A05 Offline cache miss | Changing to SALADS offline yields No matching saved result. SUSHI snapshot is not displayed for SALADS. | [Offline miss](evidence/sprint4/s4-07-bq10/A05-offline-miss.png) |
| A06 Reconnection | Without leaving the destination, SALADS receives Server response at 22:53:46 with the corresponding category. | [Reconnection](evidence/sprint4/s4-07-bq10/A06-reconnection.png) |
| A07 Navigation persistence | BOWLS server response at 22:54:14; after navigating back to Meal options and reopening Alternative meals, BOWLS remains selected with the same saved timestamp. This demonstrates navigation persistence, not a separate process-death test for alternatives. | [Before](evidence/sprint4/s4-07-bq10/A07-before-reopen.png), [After](evidence/sprint4/s4-07-bq10/A07-after-reopen.png) |
| A08 Category-specific fresh cache | BOWLS and SUSHI each display Saved snapshot with distinct, previously observed timestamps (22:54:14 and 22:51:52), while retaining the same budget/time/diet. | [Bowls](evidence/sprint4/s4-07-bq10/A08-bowls-cache.png), [Sushi](evidence/sprint4/s4-07-bq10/A08-sushi-cache.png) |

The observed student snapshots are empty, not positive recommendations. Late-night availability is a plausible explanation, but the evidence does not isolate the exact exclusion reason. A positive response with a compatible meal, substitution explanation, price, walking/dining time, dietary checks and detail navigation remains necessary. The context screenshot does not independently show the selected campus; the cache hit establishes that a reusable context existed, not which campus was selected. The original recording includes device notification and quick-settings content, so only selected in-app frames are included.

Supplementary analyst evidence: [real dataset label](evidence/sprint4/s4-07-bq10/B01-real-demand.jpg) and [complete synthetic Centro report](evidence/sprint4/s4-07-bq10/B02-centro-full-report.jpg).

## 6. Remaining device evidence / acceptance checklist

| Scenario | Status |
|---|---|
| Student navigation and alternative endpoint response (including a positive compatible result) | Navigation, empty online response and refresh passed; positive compatible result remains pending on device. Backend unit tests cover positive results. |
| Alternatives offline matching canonical-campus context; unmatched context; reconnection and filter persistence | Matching cache, unmatched category, reconnection and navigation persistence observed (A04–A08). Context changes for budget/time/diet and precise-location nonreuse not independently exercised. |
| Confirm 5/15-minute TTL expiry and cache retention bound | Not demonstrated by these recordings. |
| Interrupt a confirmed in-flight refresh; overlapping requests with delayed responses | Partial: offline fallback and ordinary zone changes demonstrated; precise race not proven. |
| Direct mobile 403 and cached report invalidation | Backend USER 403 verified; mobile invalidation path implemented, not independently demonstrated. |
| Upgrade from an installed v11 database without data loss | v12 schema compiled; existing installation version and preservation not established. |
| Inherited features (inventory, recipes, restaurant detail, comparison, BQ9) | Home and profiles appear in recordings; complete regression walkthrough not reviewed. |
| S4-08 profiling | Separate task; collect physical BEFORE/AFTER traces before applying optimization. |

Both new destinations and core connectivity strategies have physical-device evidence. Keep #44 open until remaining acceptance evidence, in particular a positive alternative, and reviewer approval are recorded.

## 7. Source and limitations

- Android [BQ10 data, API, repository and DAO](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Data.kt).
- Android [ViewModels and Compose screens](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Screens.kt).
- Android [database migrations](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/core/database/DatabaseMigrations.kt).
- Backend [contract and policy](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/blob/main/docs/SPRINT4_BQ10.md).

Demand measures recorded searches, not purchases or all students. Supply is a time-specific shared catalog snapshot, not historical inventory or market-wide availability. Complementary suppression reduces within-response disclosure but does not eliminate differencing across overlapping windows. Walking-only recommendations depend on route estimates, not live guarantees. The BQ10 cache does not persist actual device coordinates. The 20-entry bound and TTLs are implementation properties, not measured heap or expiry outcomes in the supplied evidence.

## 8. Evidence provenance and privacy

Original phone captures: `1000326738.jpg`, `1000326740.jpg`, `1000326742.jpg`, `1000326744.jpg`, `1000326746.jpg` (renamed without content changes). The remaining PNGs are extracted frames from the original screen recordings: B01/B05/B09 from `1000326751.mp4`, B10 from `1000326752.mp4`, B03/C01 from `1000326753.mp4`, B02 category cards from `1000326755.mp4`, D01/D02/D03 from `1000326756.mp4`. C02 is the in-app recording `1000326755.mp4`, re-encoded at smaller resolution and with audio removed. Original recordings containing keyboard clipboard contents, personal apps or account entry are intentionally not included. Student JPGs and supplementary analyst JPGs are renamed from the WhatsApp ZIP of November 25, 2026, 23:19. Student PNGs are extracted from its 108-second video at approximately 24s (A04), 30s (A05), 54s (A06), 69s and 78s (A07), 99s and 105s (A08). No original notification-shade footage is included. A long suppression screenshot with a duplicated SALADS card at a scroll-stitch boundary was not included; the report card evidence from the earlier recording is retained. No credentials or raw login recordings are intentionally included.
