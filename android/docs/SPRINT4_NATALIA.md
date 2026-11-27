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
| A09 Positive alternative, no diet | Context: Uniandes chosen manually, 90 min, COP 40,000, no dietary restriction, delivery off. Meal options lists 3 restaurants. Find alternatives → SUSHI: `FOUND`, routes AVAILABLE, four compatible meals, each with Budget: true · Diet: true · Time: true and the substitution explanation: Vegetarian bowl, Green Bowl, BOWLS, COP 17,000, 21 min; Vegan power bowl, Green Bowl, BOWLS, COP 19,000, 21 min; Ajiaco santafereño, Andean Flavor, SOUPS, COP 16,000, 23 min; Bandeja paisa, Andean Flavor, RICE_DISHES, COP 18,000, 23 min. Refresh availability shows a newer saved time (19:01:11 → 19:01:29 local). Restaurant details opens Green Bowl. | [Context 1](evidence/sprint4/s4-07-bq10/A09-context-campus-time-budget.png), [Context 2](evidence/sprint4/s4-07-bq10/A09-context-diet-delivery.png), [Meal options](evidence/sprint4/s4-07-bq10/A09-meal-options.png), [Menu](evidence/sprint4/s4-07-bq10/A09-category-menu.png), [First response](evidence/sprint4/s4-07-bq10/A09-sushi-first-response.png), [Refreshed](evidence/sprint4/s4-07-bq10/A09-sushi-refreshed.png), [Cards 1–2](evidence/sprint4/s4-07-bq10/A09-sushi-cards-bowls.png), [Cards 3–4](evidence/sprint4/s4-07-bq10/A09-sushi-cards-soups-rice.png), [Details](evidence/sprint4/s4-07-bq10/A09-restaurant-details.png) |
| A10 Positive alternative, vegetarian | Same context with Vegetarian selected through Change context. SUSHI stays selected and a new server response is requested (19:03:24), so the no-diet snapshot is not reused. `FOUND` with one meal: Vegetarian bowl, Green Bowl, BOWLS, COP 17,000, 21 min, Budget: true · Diet: true · Time: true. The three meals without a VEGETARIAN tag are excluded. Refresh: 19:03:24 → 19:03:40. Restaurant details opens Green Bowl. | [Context](evidence/sprint4/s4-07-bq10/A10-context-vegetarian.png), [First response](evidence/sprint4/s4-07-bq10/A10-vegetarian-first-response.png), [Refreshed](evidence/sprint4/s4-07-bq10/A10-vegetarian-refreshed.png), [Details](evidence/sprint4/s4-07-bq10/A10-restaurant-details.png) |

A01–A08 are empty snapshots, not positive recommendations. Late-night availability is a plausible explanation, but that evidence does not isolate the exact exclusion reason. The context screenshot does not independently show the selected campus; the cache hit establishes that a reusable context existed, not which campus was selected. The original recording includes device notification and quick-settings content, so only selected in-app frames are included.

A09–A10 were run by Juan Pablo on 26 November 2026, 19:00–19:04 America/Bogota. Device: Samsung SM-S948U1, Android 17 (API 37). Build: debug, installed as `com.campusmeal.android.devicecheck` with backend `https://campusmeal-api.onrender.com/api/v1/`. The build is APK SHA-256 `6493eeb4…`, from the PR #64 tree (merged as `main` `006ee46`, which contains S4-07); its full checksum is in [SPRINT4_DANIEL.md](SPRINT4_DANIEL.md) §5.1. Account: a student test account. No schedule, menu or catalog data was changed. The answer is positive because the test ran while restaurants were open. Right after A10, the same requests sent to the API returned the same meals: [API cross-check](evidence/sprint4/s4-07-bq10/A09-A10-api-crosscheck.json). Observations, neither of them an S4-07 defect:
- Diet matching requires the exact tag (`meal.dietaryTags.includes(preference)` in the backend `restaurants.service.ts`), the same rule as ordinary search. Vegan power bowl (tags VEGAN, GLUTEN_FREE) is therefore not offered to a vegetarian. This is conservative: a meal that might not be vegetarian is never shown. Tagging vegan meals as vegetarian would be a catalog decision outside this test.
- Restaurant details shows Walk and Total as "—" because a manually chosen campus is not a current location. The alternative card already shows the estimated 21 min.

Supplementary analyst evidence: [real dataset label](evidence/sprint4/s4-07-bq10/B01-real-demand.jpg) and [complete synthetic Centro report](evidence/sprint4/s4-07-bq10/B02-centro-full-report.jpg).

## 6. Remaining device evidence / acceptance checklist

| Scenario | Status |
|---|---|
| Student navigation and alternative endpoint response (including a positive compatible result) | Passed. Navigation, empty online response and refresh (A02–A03). Positive compatible results with meal, restaurant, substitute category, price, time, Budget/Diet/Time checks, explanation and detail navigation, with and without a vegetarian diet (A09–A10). |
| Alternatives offline matching canonical-campus context; unmatched context; reconnection and filter persistence | Matching cache, unmatched category, reconnection and navigation persistence observed (A04–A08). A diet change sends a new request instead of reusing the snapshot (A10). Budget/time changes and precise-location nonreuse not independently exercised. |
| Confirm 5/15-minute TTL expiry and cache retention bound | Not demonstrated by these recordings. |
| Interrupt a confirmed in-flight refresh; overlapping requests with delayed responses | Partial: offline fallback and ordinary zone changes demonstrated; precise race not proven. |
| Direct mobile 403 and cached report invalidation | Backend USER 403 verified; mobile invalidation path implemented, not independently demonstrated. |
| Upgrade from an installed v11 database without data loss | v12 schema compiled; existing installation version and preservation not established. |
| Inherited features (inventory, recipes, restaurant detail, comparison, BQ9) | Home and profiles appear in recordings; complete regression walkthrough not reviewed. |
| S4-08 profiling | Separate task; collect physical BEFORE/AFTER traces before applying optimization. |

Both new destinations and core connectivity strategies have physical-device evidence. The positive alternative is recorded (A09–A10). Keep #44 open until the remaining acceptance evidence and reviewer approval are recorded.

## 7. Source and limitations

- Android [BQ10 data, API, repository and DAO](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Data.kt).
- Android [ViewModels and Compose screens](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Screens.kt).
- Android [database migrations](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/core/database/DatabaseMigrations.kt).
- Backend [contract and policy](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/blob/main/docs/SPRINT4_BQ10.md).

Demand measures recorded searches, not purchases or all students. Supply is a time-specific shared catalog snapshot, not historical inventory or market-wide availability. Complementary suppression reduces within-response disclosure but does not eliminate differencing across overlapping windows. Walking-only recommendations depend on route estimates, not live guarantees. The BQ10 cache does not persist actual device coordinates. The 20-entry bound and TTLs are implementation properties, not measured heap or expiry outcomes in the supplied evidence.

## 8. Evidence provenance and privacy

Original phone captures: `1000326738.jpg`, `1000326740.jpg`, `1000326742.jpg`, `1000326744.jpg`, `1000326746.jpg` (renamed without content changes). The remaining PNGs are extracted frames from the original screen recordings: B01/B05/B09 from `1000326751.mp4`, B10 from `1000326752.mp4`, B03/C01 from `1000326753.mp4`, B02 category cards from `1000326755.mp4`, D01/D02/D03 from `1000326756.mp4`. C02 is the in-app recording `1000326755.mp4`, re-encoded at smaller resolution and with audio removed. Original recordings containing keyboard clipboard contents, personal apps or account entry are intentionally not included. Student JPGs and supplementary analyst JPGs are renamed from the WhatsApp ZIP of November 25, 2026, 23:19. Student PNGs are extracted from its 108-second video at approximately 24s (A04), 30s (A05), 54s (A06), 69s and 78s (A07), 99s and 105s (A08). No original notification-shade footage is included. A long suppression screenshot with a duplicated SALADS card at a scroll-stitch boundary was not included; the report card evidence from the earlier recording is retained. No credentials or raw login recordings are intentionally included.

## 9. Supplemental validation — November 26, 2026

This section supersedes the outstanding-test status in sections 2 and 6 where the new evidence applies. The original evidence and its provenance remain unchanged.

### 9.1 Controlled physical-device checks

The profile-only `Bq10CheckActivity` exercises the production BQ10 repository with a disposable Room database and DataStore, synthetic account scopes, an injected clock, and a fake API. It does not change the live backend, permissions, or user data. The profile build and installation succeeded; on November 26 at 22:43 Bogotá time the device log recorded **42/42 PASS assertions** and `DONE PASS assertions=42`. See [repository-check.log](evidence/sprint4/s4-07-bq10/controlled/repository-check.log).

| Area | Executed outcome |
|---|---|
| Alternative TTL | 299,999 ms is fresh; 300,000 ms expires; simulated 503 returns historical data; reconnect refreshes. |
| Report TTL | 899,999 ms is fresh; 900,000 ms expires; simulated 503 returns historical data; reconnect refreshes. |
| Exact context | Budget, time, dataset and date changes cause offline misses. Noncanonical coordinates and absent campus do not reuse a campus snapshot; noncanonical online request succeeds without increasing the snapshot count and remains an offline miss. |
| Filter privacy | Nonprecise category and report filters persist; saved BQ10 filter values do not contain the synthetic device coordinates. |
| Retention | After inserting distinct queries, exactly 20 snapshots remain per tested kind; oldest evicted, newest retained. |
| Authorization | Simulated HTTP 403 denies report access, removes all report rows for the account, preserves alternatives, and prevents offline recovery of the deleted report. |
| Connectivity | Simulated IOException during forced refresh preserves historical report content. |
| Session and cancellation | Delayed response from a previous session denied; old scope cannot fetch; new account cannot reuse old reports/alternatives or filters; account cleanup preserves the other account; cancelled in-flight request does not persist a result. |
| Migration | Exported v11 schema upgraded to v12 and validated by Room; synthetic inventory row, restaurant cache timestamp, review draft and pending outbox row preserved; new BQ10 table writable. |

Harness: `app/src/profile/java/com/campusmeal/android/profiling/Bq10CheckActivity.kt`; v11 schema asset: `app/src/profile/assets/s4-07-room-v11.json`. Build with `:app:assembleProfile`, install the profile APK, launch `com.campusmeal.android.profile/com.campusmeal.android.profiling.Bq10CheckActivity` and collect `adb logcat -d -s CampusMealBq10Check`.

Limitations: the fake API verifies repository responses to controlled failures, not a live mobile 403 or all Compose races. The v11 migration is a disposable schema-based test, not an installed signed release upgrade. No heap benchmark is claimed. Exact APK hashes were not captured for this supplemental run.

### 9.2 Inherited-feature smoke checks and BQ10 navigation

Natalia assembled and installed the debug build successfully and recorded two physical-device walkthroughs on November 26, approximately 22:48–22:54 Bogotá time. The first launch command used the wrong package; the subsequent videos demonstrate app navigation. Original recordings contain authentication screens and are retained locally; only selected in-app frames are included below. The observed scope is a smoke check, not exhaustive regression of every inherited mutation and failure path.

| Scenario | Observed outcome and evidence |
|---|---|
| Student authentication and inventory | Student sign-in followed by inventory with expiration labels; [inventory](evidence/sprint4/s4-07-bq10/controlled/E01-inventory.jpg). |
| Recipes | Filters and refresh exercised; no matching recipes for the current inventory; [recipes](evidence/sprint4/s4-07-bq10/controlled/E02-recipes.jpg). |
| Context and restaurant search | Manual Uniandes, 60 minutes, COP 50,000, no diet; delivery toggled off; valid empty result, not proof of a positive search; [campus](evidence/sprint4/s4-07-bq10/controlled/E03-campus.jpg), [budget/time](evidence/sprint4/s4-07-bq10/controlled/E03-budget-time.jpg), [search](evidence/sprint4/s4-07-bq10/controlled/E04-restaurant-search.jpg). |
| Alternatives | BOWLS then HOME_STYLE online responses, no compatible alternatives in these nighttime snapshots; [BOWLS](evidence/sprint4/s4-07-bq10/controlled/E05-alternatives-bowls.jpg). |
| Restaurant detail | Reuse Juan Pablo's positive physical-device checks A09–A10, not an independent Natalia run on the latest debug APK: [A09 detail](evidence/sprint4/s4-07-bq10/A09-restaurant-details.png), [A10 detail](evidence/sprint4/s4-07-bq10/A10-restaurant-details.png). See section 5 for device, build and API cross-check. The ordinary search-to-detail route was not independently repeated in the nighttime walkthrough. |
| Cook / Walk / Order | Comparison reached a cooking recommendation and the home plan showed Cook with Rice; [saved plan](evidence/sprint4/s4-07-bq10/controlled/E06-saved-plan.jpg). Previous-comparison retrieval not demonstrated in this recording. |
| Analyst authentication and BQ6 | Analyst login and report/month navigation observed; [BQ6](evidence/sprint4/s4-07-bq10/controlled/E07-bq6.jpg). |
| BQ9 | Date/zone selection and results with suppression; [BQ9](evidence/sprint4/s4-07-bq10/controlled/E08-bq9.jpg). |
| BQ10 rapid zone changes | Loading and cached responses across Centro, Chapinero and Teusaquillo; final Centro synthetic report returns 25 categorized searches, BOWLS 15/5/60%, SOUPS 5/5/20%. [Loading](evidence/sprint4/s4-07-bq10/controlled/E09-zone-loading.jpg), [Centro](evidence/sprint4/s4-07-bq10/controlled/E09-centro.jpg), [Teusaquillo](evidence/sprint4/s4-07-bq10/controlled/E09-teusaquillo.jpg), [final Centro](evidence/sprint4/s4-07-bq10/controlled/E09-final-centro.jpg). No mismatched final result observed; HTTP completion order was not instrumented. |

Frame provenance: analyst recording `WhatsApp Video 2026-11-26 at 10.55.45 PM.mp4` (9, 39, 90, 96, 102 and 114 s); student recording `WhatsApp Video 2026-11-26 at 10.56.53 PM.mp4` (27, 51, 63, 84, 90, 99 and 141 s). No login frames included.

### 9.3 Acceptance status

Both new destinations, backend cross-checks, offline exact-context handling, filter persistence, repository failure paths, bounded storage and migration now have recorded evidence. The prior partial concurrency evidence is supplemented by controlled cancellation/session checks and a manual zone-switch walkthrough. The inherited smoke checks above are not a full regression suite; previous-comparison recovery, ordinary search-to-detail on the latest build, full installed release upgrade, and direct live mobile 403 remain unverified in this supplemental run. Keep #44 open until Daniel reviews and accepts the evidence and the validation PR is merged. Do not interpret this as a claim that every inherited feature/failure combination was tested.
