# Sprint 4 — CampusMeal Android delivery and evidence index

Tracking: [S4-13 / #46](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/46). Owner: Natalia Espitia; reviewer: Daniel Vargas. Evidence snapshot: November 26, 2026 (America/Bogota). Contributors: Juan Pablo, Natalia and Daniel. This document consolidates existing records; it does not replace earlier sprint wiki pages or claim a new test was run by its author.

**Delivery status:** review draft. Android main inspected at [`288ad2f`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/288ad2fbcf33612b696facf0112a65695df4f6a2). Backend BQ10 evidence merged at [`1910b13`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/commit/1910b13522dbe2b16d8b6adbcd5954c57fc3892b). PRs #65 and #66 are open at this snapshot; their evidence is cited at their immutable branch heads, not represented as merged. Signed final release and common-device acceptance are pending S4-14. Android subgroup only; no Swift views are counted.

## 1. Shared and individual rubric traceability

The following crosswalk uses the detailed numbered criteria in *ISIS-3510 — Sprint 4 Description: App Implementation and Definition*, pp. 2–6. The cover-page allocation differs from the detailed items; the detailed scores sum to 205 individual and 180 team points. Scores below are rubric weights, not earned grades. The official PDF is the authority for submission logistics; the internal #46 checklist is supplementary.

| Official criterion | Points | Where to review / current state |
|---|---:|---|
| 1. Inherited S2/S3 features and oral-feedback fixes | 0 (required) | §2, §7; scope and Sprint 3 delivery. Partial regression; feedback reconciliation needs owner confirmation. |
| 2. Value proposition, features, BQs, revenue model and data | 10 | §3 and S4-12; #42 closed; limitations disclosed. |
| 3. Design, implement, justify and profile micro-optimizations | 40 | §5, code excerpts, pinned before/after sources and profiling logs/CSV; #45 pending review. Official criterion accepts logs **or** screenshots. |
| 4. One new view per teammate | 15 | §4: six new Android destinations, two per contributor; cross-platform parity not independently checked. |
| 5a–f. Features by sprint, BQs/owner/type/rationale, eventual connectivity, local storage, threading and caching | 0 (mandatory) | §2, §4, §6–7. Strategies, rationale, evidence and gaps. Missing this component prevents grading. |
| 6. Working app on real devices | Required | Individual physical evidence; integrated signed APK and oral readiness pending S4-14. |
| 7. Share app with TAs | 0 (required) | §8 submission checklist; Firebase invitations and Teams notice not confirmed. |
| 8. New original BQ per teammate | 20 | §4: BQ13, BQ10, BQ1; original wording and types. |
| 9. New feature per teammate; mirror apps | 80 | §4, §6–7; Android implemented; iOS parity or authorized exception needs team confirmation. |
| 9a. Multithreading/concurrency | 20 of 80 | §6–7: cancellation, revision guards, application/IO scopes, evidence. |
| 9b. Local storage | 20 of 80 | §6: additive migrations, Room, DataStore, durable markers. |
| 9c. Caching | 20 of 80 | §6–7: exact keys, TTL, bounded retention and historical fallback. |
| 9d. Eventual connectivity for every functionality | 20 of 80 | §7: all-feature matrix; not every failure combination executed. |
| 10. Six-minute ethics video | 46 team | Separate team artifact; recording/submission not confirmed; syllabus and competence sheet must be checked. |
| 11. Coevaluation and collaboration/repository multiplier | 134 team | §8: actual commits, issues and PRs; peer scores, milestones and board completion not independently checked. |
| 12. Readable, consistent and complete written artifacts | 20 individual | This index and owner reports; final review needed. |
| 13. Repository tools and process | 20 individual | §8: issues, PRs, reviews, commits; milestone/board/squash workflow review pending. |

## 2. Inherited functionality and closure

| Feature / BQ | Origin | Owner / attribution | Sprint 4 treatment and evidence |
|---|---|---|---|
| Authentication/session/profile | S2, extended S3 | Juan Pablo | Preserved Keystore/session generations and account profile; student/analyst sign-in in Natalia §9.2; transient refresh/expiry combinations not re-established in latest walkthrough |
| Inventory / BQ2 | S2, extended S3 | Juan Pablo | S4-01 adds deduplicated INVENTORY usage event. Scope §3.3: eight scenarios, offline/restart/account change, six server rows across two accounts. Natalia §9.2 shows inventory |
| Recipe evaluation / BQ3 | S3 | Juan Pablo | S4-05 ingredient indexing; 2 equivalence tests, offline catalog strategy; latest Natalia recipe query returned zero matches |
| Restaurant search/detail / BQ4 | S2, extended S3 | Natalia | S4-08 removes duplicate Room fetch; #65 pending. Natalia §9.2 valid empty nighttime search; A09–A10 positive alternative-to-detail on Juan Pablo's device |
| Restaurant cache / BQ7-related cache path | S3 | Natalia | Existing 5/30-minute search/detail policies; S4-08 changes fetch mechanism only, not new view/BQ |
| Cook / Walk / Order / BQ5 | S2, extended S3 | Daniel | Natalia §9.2 comparison and saved Cook plan; Daniel §5 emulator previous/offline comparison telemetry; latest physical previous-comparison retrieval not independently repeated |
| Explanation/selection / BQ8 | S2 | **Carlos, original BQ author; Daniel, Android integration** | Existing impression/selection outbox retained. Not a new S4 BQ |
| Analytics outbox and adoption / BQ6 | S3 | Daniel | S4 extends dispatcher/retention; scope §3.3 and Daniel §5/5.1; Natalia §9.2 BQ6 month navigation |
| Zone demand / BQ9 | S3 | Natalia | Reused for BQ10; Natalia §9.2 BQ9 range/zone and suppression; existing exact report cache |

### Scope gap reconciliation (G-1–G-7)

- G-1: Inventory usage event implemented and exercised (scope §3.3; PR #52).
- G-2: Prior Sprint 3 Daniel document says restaurant search/detail usage emitters pending; scope §2.2 establishes they already existed, and Sprint 3 delivery lists commits `e1711fb`, `9867298`. Preserve the older report as historical; refer to the corrected scope and current ViewModels. A dedicated new physical emission count for both restaurant events was not independently established here.
- G-3: Daniel's old pending device rows are not automatically closed by the new sync tests. See Daniel §5–5.1 for actual executed emulator/physical cases; remaining individual inherited rows need owner reconciliation.
- G-4: Earlier Sprint 3 review placeholders are not completed retrospectively. S4-12 reviewer evidence is accepted; see §8 for remaining review cycle.
- G-5: Older release documentation names 0.3.1/code 3, while baseline Gradle and scope specify 0.4.0/code 4. Do not infer which APK is installed from Gradle alone. S4-14 must record signed APK version, hash, distribution and in-place upgrade.
- G-6: Daniel implements visible expiry and bounded retention rather than silently deleting pending records; emulator retention fixtures passed.
- G-7: New smoke checks and individual tests supplement offline/reconnect, account change, outbox replay and reports. Unknown inventory mutation outcome, full inherited regression, live mobile authorization combinations and common signed APK validation are not exhaustively demonstrated in this snapshot.

## 3. Value proposition and revenue model

Source: [S4-12 value proposition](SPRINT4_VALUE_PROPOSITION.md), [accepted reviewer reproduction](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/42#issuecomment-7206744381), [owner acceptance](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/42#issuecomment-7207641095). #42 is closed.

| Initial promise / need | Implemented support | What can be concluded / limit |
|---|---|---|
| Faster meal decisions | Cook/Walk/Order, BQ1 rendered-load outcomes, BQ3 evaluation | Technical comparison/evaluation evidence, not a measured reduction in student decision time |
| Time/budget/diet fit | Context-aware restaurants, alternatives | Constraint checks and matching test responses; route/time estimates, not guarantees of purchase or availability |
| Use expiring ingredients | Manual inventory, expiry warning, recipe matching | Functionality demonstrated; scanning and push reminders not delivered; no measured waste reduction |
| Confidence in restaurants | Structured reviews, separate catalog rating, menu-revision history/BQ13 | Self-reported reviews; no causal link or real menu-freshness correlation established |
| Partner opportunities | BQ9 aggregate demand, BQ10 gaps, BQ13 partner health | Privacy-protected interest and catalog snapshots; not purchases, market demand or validated paid analytics |

Free core student experience is the proposed freemium foundation. Restaurant subscriptions, labeled sponsored promotions, aggregated analytics and university partnerships are proposed commercial mechanisms, not deployed billing or validated willingness to pay. Payments must not override dietary, budget, time or expiry constraints. No external partner self-service, revenue or real student pilot is established. Developer runs and synthetic fixtures are functional evidence, not adoption evidence.

Representative snapshots: BQ13 real-only report had 37 published reviews and five UNKNOWN menu statuses, with insufficient association data; BQ10 real demand was `NO_OBSERVATIONS`; synthetic Centro Nov 17 had 25 categorized searches (BOWLS 15, SOUPS 5, SUSHI 5; five distinct searchers in each category). Supply is evaluated at `asOf`, and changes independently of the historical demand window. BQ1 small sample labels and injected-session exclusions must remain visible. BQ6 partial November and synthetic labels are not full-month production adoption. See the dated snapshots and reviewer comment rather than treating these as live totals.

## 4. New S4 features, destinations, BQs and contributions

The three new BQ wordings below are reproduced from [frozen scope §4](SPRINT4_SCOPE.md#4-business-questions-original-wording-unchanged), not rewritten.

| Owner | Original BQ / type | Original wording |
|---|---|---|
| Juan Pablo | BQ13 / Type 4 | Which restaurant partners keep menus and prices updated within a seven-day window, and does that update frequency correlate with higher student selection rates and review scores? |
| Natalia | BQ10 / Type 5 | Which restaurant categories have high anonymized demand but low current availability in each campus zone, so CampusMeal can recommend suitable alternatives to users while providing aggregated opportunity reports to restaurant partners? |
| Daniel | BQ1 / Type 1 | Which device model, operating-system version, and application version combinations have a crash rate above 1% or a 95th-percentile loading time above two seconds on the meal-comparison screen? |

| Owner | New feature family | Distinct new destination | Access and entry |
|---|---|---|---|
| Juan Pablo | Structured reviews, durable submission and partner health | `ReviewMealScreen` | Signed-in account, restaurant detail → Write a review |
| Juan Pablo | Same | `PartnerHealthScreen` | ANALYST, Profile → Analytics → BQ13 |
| Natalia | Context-compatible substitutes and supply-gap analytics | `AlternativeMealsScreen` | Signed-in account, Meal options → Find alternatives |
| Natalia | Same | `SupplyGapReportScreen` | ANALYST, Profile → Analytics → BQ10 |
| Daniel | Synchronization center and durable performance telemetry | `SynchronizationCenterScreen` | Signed-in account, Profile → Synchronization |
| Daniel | Same | `PerformanceHealthScreen` | ANALYST, Profile → Analytics → BQ1 |

**BQ rationale:** BQ13 tests whether confirmed menu maintenance and student feedback can support partner decisions, without assuming causality. BQ10 detects aggregate interest versus current supply and offers context-compatible substitutes, without claiming purchases. BQ1 identifies device/version combinations that may need reliability or latency investigation, with confidence and coverage labels.

These are six distinct destinations, not renamed prior screens. Existing search/detail, inventory, recipe evaluation, pending labels, outbox worker, BQ6 and BQ9 are not counted as new S4 features.

| Owner | Backend delivery | Android delivery | Individual evidence |
|---|---|---|---|
| Juan Pablo | [#24](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/24), [#25](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/25) | [#52](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/52), [#54](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/54), [#57](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/57), [#58](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/58), [#60](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/60), [#63](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/63) merged | [S4-04 report](SPRINT4_JUAN_PABLO.md), [S4-05 profiling](SPRINT4_MICROOPT_JP.md), [S4-12](SPRINT4_VALUE_PROPOSITION.md) |
| Natalia | [#28](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/28), [#29](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/29) merged | [#61](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/61), [#62](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/62) merged; [#65](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/65), [#66](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/66) open | [S4-07 report with supplement, PR #66](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/9c7e6ad8350a334dc8be15d4155e71be12d17b9c/android/docs/SPRINT4_NATALIA.md), [S4-08 profiling, PR #65](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/SPRINT4_MICROOPT_NATALIA.md) |
| Daniel | [#26](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/26), [#27](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/27) | [#53](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/53), [#55](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/55), [#59](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/59), [#64](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/64) merged | [S4-10 report](SPRINT4_DANIEL.md), [S4-11 profiling](SPRINT4_MICROOPT_DANIEL.md); physical evidence collected by Juan Pablo, not Daniel |

## 5. Micro-optimizations: comparable measurements and correctness

[Common protocol](SPRINT4_PROFILING_PROTOCOL.md), [evidence index](evidence/sprint4/README.md). Each pair uses the release-derived profile build with trace sections and its own dataset/device. Do not compare across devices or treat durations as CPU time, energy or end-to-end UI latency. Reduction = (before median − after median) / before median × 100%. Spread = (max run median − min run median) / median. Under the team protocol, overlapping ranges do not establish improvement beyond measured variability. Runs are not paired observations. Original traces are retained locally according to the protocol; extracted CSVs, logs, conditions and source reports are the reviewable evidence. The official criterion 3 accepts profiling logs or screenshots, plus supporting code. Internal #46 also requests same-region screenshots; these are not present for all pairs. Reviewers should agree whether existing profiling logs satisfy the internal checklist or attach actual captures from retained traces. UI screenshots and generated graphs are not profiler screenshots.

### Supporting source excerpts (from pinned versions)

Juan Pablo — ingredient lookup (before / after; surrounding code and preserved order in the linked source):

```kotlin
// before: df0a40e
val named = usable.filter { it.normalizedName in keys }
// after: 97eed47
val index = UsableIndex(usable)
// inside checkIngredient:
val named = index.rowsMatching(ingredient.matchingKeys)
```

Natalia — fresh search return path, after the existing TTL check (detail follows the same pattern):

```kotlin
// before: db1fe82
return cachedSearch(scope, contextKey)
// after: d72b9ce
return decodeCachedSearch(scope, entity)
```

Daniel — formatter creation (before / after; format call and trace in linked sources):

```kotlin
// before: dad0a47
SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(epochMillis))
// after: cd52361
private val formatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() =
        SimpleDateFormat(PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
```

These excerpts show the changed mechanisms, not standalone compilable programs. The pinned sources below provide the complete context.

### 5.1 Juan Pablo — BQ3 ingredient lookup (S4-05)

[Before source](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/df0a40e/android/app/src/main/java/com/campusmeal/android/feature/recipes/domain/RecipeEvaluator.kt) · [After source](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/97eed472ff0df37bfeedd5ae266a14eede6af20c/android/app/src/main/java/com/campusmeal/android/feature/recipes/domain/RecipeEvaluator.kt) · [Diff](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/compare/df0a40e...97eed47) · [Owner report, raw run values and reproduction](SPRINT4_MICROOPT_JP.md) · [Evidence](evidence/sprint4/s4-05-bq3/).

Previously scanned the usable inventory for every ingredient; now builds a local normalized-name index, preserving row order and numeric summation. No cross-account memo added. Two golden equivalence tests pass; sensitivity check without merge sort fails both. Physical Samsung SM-S948U1, Android 17/API 37, speed compilation, three warmup runs, airplane mode, USB charging. APK hashes and conditions: [before](evidence/sprint4/s4-05-bq3/before/conditions.md), [after](evidence/sprint4/s4-05-bq3/after/conditions.md).

| Dataset | Runs/variant | Before median (range), ms | After median (range), ms | Interpretation |
|---|---:|---|---|---|
| Representative, 6 recipes / 50 rows | 10 | 2.043 (1.861–2.299) | 2.014 (1.907–2.523) | ~1.4% descriptive only; ranges overlap |
| Stress, 300 recipes / 500 rows | 5 | 28.412 (26.497–29.484) | 17.669 (17.132–18.682) | **37.8% reduction**, disjoint ranges |

Raw representative and stress values and sections CSVs are in the evidence folder. Natalia reproduced the four medians and the unit-test command in #42. Same-region profiler screenshot pair: not located in the inspected evidence tree; request from owner if required.

### 5.2 Natalia — one Room snapshot per fresh restaurant lookup (S4-08)

[Before](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/db1fe82ec9a84f02660bc1f3a8167568791218b2/android/app/src/main/java/com/campusmeal/android/feature/restaurants/data/RestaurantRepository.kt) · [After](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/d72b9cee67328eb95fca7b77a25e10848608442c/android/app/src/main/java/com/campusmeal/android/feature/restaurants/data/RestaurantRepository.kt) · [Diff](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/compare/db1fe82...d72b9ce) · [Owner report](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/SPRINT4_MICROOPT_NATALIA.md) · [Evidence, PR #65 head](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/tree/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/evidence/sprint4/s4-08-room-cache).

Freshness check and decoder reuse one entity instead of querying twice. Preserves timestamps, forced refresh, stale fallback, exact context and account generation; no schema change or new memory cache. Physical Samsung SM-A336M, Android 16/API 36. 40 representative and 40 controlled traces. Controlled runs: disposable real Room, fixed clock, fake API (zero calls during measured bursts), 10 runs per size per variant, 100 searches + 100 details per run; 8,000 operations total. 8,000 before reads vs 4,000 after reads, 4,000 decodes each. The four payload SHA-256 signatures match across variants; payload lengths 802/31,997 bytes (search), 471/11,191 (detail). Whole burst includes mapping; read/decode sections exclude outside mapping. Sequential run order and unrecorded thermal state limit inference.

| Workload / metric | Before median (range), ms | After median (range), ms | Median reduction / evidence |
|---|---|---|---|
| Representative detail, per-operation sections | 15.765 (13.134–16.398) | 12.672 (10.988–13.498) | 19.62%; overlap |
| Representative search, per-operation sections | 4.279 (3.275–9.312) | 2.963 (2.200–4.192) | 30.76%; overlap |
| Controlled small search, whole burst | 88.216 (77.483–135.701) | 59.415 (50.400–81.491) | 32.65%; overlap |
| Controlled small detail, whole burst | 80.847 (66.317–106.799) | 48.024 (39.055–93.400) | 40.60%; overlap |
| Controlled large search, whole burst | 273.396 (264.896–300.157) | 203.524 (196.725–212.106) | **25.56%; disjoint** |
| Controlled large detail, whole burst | 132.272 (116.067–150.265) | 105.091 (85.720–120.316) | 20.55%; overlap |
| Controlled large search, read + decode | 259.032 (250.786–285.050) | 188.809 (182.871–196.775) | **27.11%; disjoint** |

Controlled APK SHA-256 before `ACFDCCDDB8CAB66CB71D9106E6495353E22E19D869C490E745B9C71E46763EBB`; after `7C4180AB67EC68A8CDFB1D60C907EE55905BD47204478277C391C0689CD2A0DD`. Representative before full hash not available. Build blobs, 40 CSV/log pairs, run-level values and audit in owner report. 16 controlled correctness assertions passed on device; representative CSVs and controlled binary-to-CSV audit reported by owner documentation. Neither CPU/energy nor every intermediate timed return is asserted. Same-region profiler screenshot pair: pending; original binaries retained locally, not in PR. Reviewer acceptance pending.

### 5.3 Daniel — reusable UTC timestamp formatter (S4-11)

[Before](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/dad0a47/android/app/src/main/java/com/campusmeal/android/core/outbox/OutboxRepository.kt) · [After](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/cd52361/android/app/src/main/java/com/campusmeal/android/core/outbox/UtcTimestamps.kt) · [Diff](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/compare/dad0a47...cd52361) · [Owner report](SPRINT4_MICROOPT_DANIEL.md) · [Raw data and conditions](evidence/sprint4/s4-11-iso-utc/) · [Per-run graph](evidence/sprint4/s4-11-iso-utc/isoutc-per-run.svg).

Replaces a new `SimpleDateFormat` per event with a thread-local formatter. Preserves UTC, US locale, truncation and original instant. Four JVM tests pass (boundaries, locale/zone, 100,000 random instants, 160,000 timestamps from 8 threads); unsafe shared-formatter sensitivity fails. Payload hashes match; one vs eight callers MATCH. Emulator API 37, 4 vCPU, 4 GB, Apple M4 Pro host; owner has no Android device. Ten runs per variant per dataset, fresh process and 50-event warmup. Exact APK hashes and conditions are in [conditions](evidence/sprint4/s4-11-iso-utc/conditions.md).

| Metric | Before median (range), ms | After median (range), ms | Interpretation |
|---|---|---|---|
| Formatter, 20-event representative workload | 0.136 (0.092–0.244) | 0.022 (0.013–0.038) | ~84% less, disjoint |
| Formatter, 500-event stress workload | 0.073 (0.016–0.099) | 0.008 (0.002–0.009) | ~89% less, disjoint |
| Whole admission, representative burst | 227.3 (47.2–503.6) | 430.5 (40.5–908.0) | Overlap, no improvement established |
| Whole admission, stress burst | 2169.2 (397.6–2871.6) | 1683.6 (401.2–2967.9) | Overlap, no improvement established |

The formatter itself improves; full Room/session admission does not show improvement beyond variability. Emulator evidence is not a phone measurement. The graph is not an Android Studio screenshot; same-region profiler screenshot pair and physical profiling requirement remain to be reconciled with reviewer/course policy. #50 is closed; no new claim of physical equivalence.

## 6. Individual quality strategy traceability

| Owner / feature | Actual storage and cache | Actual concurrency and eventual connectivity | Executed evidence |
|---|---|---|---|
| Juan Pablo / reviews | Room v10 `review_drafts`, `review_submissions`, `review_summary_cache` (15 min, LRU 50/account/restaurant entries), `partner_health_reports` (15 min, 20/account); per-account drafts and durable REVIEW outbox | Transactional submit + stable id; invalidation after delivery; account-aware replay; report job replacement; exact historical fallback | S4-04 E1–E15 emulator; §5 phone draft restore, offline submit/reconnect, account switch, windows A/B and offline reopen; no UI 403 test claimed |
| Natalia / BQ10 | Room v12 `bq10_snapshots`, exact key and contract version, max 20/account/kind, 5 min alternatives/15 min report; DataStore nonprecise filters | `viewModelScope`, cancellation, request revision and account generation guards, coordinator-serialized writes; no replay queue; explicit historical results and retry on reconnect | S4-07 A01–A10, B01–B10, C01–C02, D01–D03; PR #66 42 controlled assertions, 13 selected frames; precise HTTP completion ordering not observed |
| Daniel / sync + BQ1 | Room v11 outbox attempt/close fields and `performance_reports`, 15 min/20/account, DataStore filters; local bounded crash marker | Durable enqueue, stable ids, unique WorkManager chain, application-scope telemetry, IO marker import; report cancellation; no non-idempotent mutation replay | S4-10 §5 emulator, §5.1 assisted phone crash, lost ack, double retry, account switching and BQ1 fixtures; installed release upgrade blocked |

Source navigation: [Juan Pablo source descriptions](SPRINT4_JUAN_PABLO.md#2-s4-04--what-was-built); [Natalia repository and DAO](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Data.kt), [Natalia ViewModels and screens](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Screens.kt); [Daniel implementation and lifecycle](SPRINT4_DANIEL.md#4-local-storage-cache-threading-and-connectivity). Migrations v9→10, 10→11, 11→12 are additive; [migration source](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/288ad2fbcf33612b696facf0112a65695df4f6a2/android/app/src/main/java/com/campusmeal/android/core/database/DatabaseMigrations.kt). The v11→12 controlled test preserved synthetic inventory, cache timestamp, review draft and pending outbox, and Room validated v12; it does not replace release upgrade.

## 7. All-feature connectivity and failure matrix

Status notation: **D** = a scenario demonstrated in cited device/emulator/controlled evidence; **P** = partial or a related scenario only; **S** = behavior described by source/design or earlier sprint report, not newly executed here; **N/A** = not a meaningful operation for that feature. D does not mean the integrated signed release was tested. For report rows, offline data is historical even within TTL; no cache is silently replaced with a different query. For every row, 401/session expiry and account change must not allow old-account results to become current; 403 is an authorization response, not an offline fallback. A server 403 cannot be learned while disconnected.

| Feature | Offline with local data | Offline without local data | Interruption / 5xx | Reconnect | Restart | Account change | 403 | Evidence |
|---|---|---|---|---|---|---|---|---|
| Authentication / profile | S: existing scoped session/profile; no offline new authentication claim | S: network required for new sign-in | S: refresh handling, generation guard; latest transient refresh not tested | P: student/analyst sign-in observed | S: session restoration | D: switch roles | S: restricted endpoints reject unauthorized role | Scope §2; Natalia §4 D01, §9.2 |
| Inventory / expiry | D: synchronized snapshot and offline usage admission | D: not synced vs empty inventory distinguished | S: drafts / unknown mutation outcome reconciled, no blind replay; dedicated latest mutation interruption missing | D: pending usage delivered | D: usage persists after reopen | D: event sent only as original account | N/A: no analyst-only report; permission error not tested | Scope §3.3; Sprint 3 inventory; Natalia §9.2 |
| Recipes | S: evaluate local catalog and inventory | S: explicit missing-data state | S: keep stored catalog if refresh fails; cancel obsolete evaluation | P: latest filters/refresh observed; reconnect not independently tested | S: persisted catalog/filters | S: scoped inventory and filters | N/A: no analyst-only report | Sprint 3 BQ3; JP micro-opt; Natalia §9.2 |
| Restaurant search | D: exact stale context fallback | D: different context offline miss; empty distinct | D: controlled stale/offline fallback and corrupt JSON; S: HTTP 5xx behavior | P: manual online recovery; force-refresh checks | S: Room persistence | D: controlled search isolation; manual partial | S: non-401 HTTP failures handled; no direct role-specific test | Natalia S4-08 R01–R08 |
| Restaurant detail | D: controlled exact cached/stale result | D: controlled missing/corrupt detail failure | D: controlled offline and corrupt recovery; S: HTTP 5xx behavior | D: simulated recovery and force refresh | S: persisted detail cache | D: controlled account and old-session isolation | S: normal authenticated endpoint; no direct role test | Natalia S4-08 R01–R08; JP A09–A10 positive detail |
| Cook / Walk / Order / history | D: emulator OFFLINE_HISTORY is historical, not selectable as current | S: explicit retry | D: emulator timeout/cancellation; no claim of server 5xx test | P: explicit recalculation observed on emulator | S: stored previous comparison | S: account-scoped history | N/A: no analyst-only report | Daniel §5; Sprint 3 decision; Natalia §9.2 |
| BQ8 impression/selection | D: durable usage/outbox infrastructure; P: selection-specific replay not re-established here | S: admitted event stored first, no cache required | D: shared lost-ack/retry evidence; P: BQ8-specific case not isolated | D: shared drain | D: shared outbox process recovery | D: shared account isolation | N/A: event write not analyst report; BQ8 analytics authorization not re-tested | Scope §2; Daniel §5/5.1; value proposition |
| BQ6 feature adoption | S: exact month cache and dated result | S: explicit no saved report | S: retain exact report on failure | P: month navigation observed, dedicated reconnect not re-tested | S: Room/filter persistence | S: scoped report | S: backend role restriction, latest mobile 403 unverified | Sprint 3 delivery; Scope §3.3; Natalia §9.2 |
| BQ9 zone demand | S: same range/zone cache | S: no unrelated cached result | S: stale fallback and obsolete-request guard | P: query changes observed; dedicated reconnect not re-tested | S: filters/cache persisted | S: account scope | S: existing 403 handling; live latest UI not established | Sprint 3 delivery; Natalia §9.2 |
| Structured review drafts/submission | D: restore draft, queue complete review | D: no cached summary; local review can be drafted | D: durable pending state, 409 rejection; D: shared lost-ack infrastructure, not review-specific 5xx | D: one delivered review and refreshed summary | D: force-stop and reopen draft/pending | D: no delivery using other token | S: permanent 4xx visible; no direct mobile 403 review test | JP §4 E3–E11, §5 |
| Review summary | D: offline cached/draft behavior; P: dedicated stale timestamp not separately reproduced | D: no saved reviews message | S: preserve cached result; invalidate after own delivery | D: summary updates after submission | S: Room persistence | S: cleanup | S: authenticated read; not analyst-only | JP §4 E3/E8, §5 |
| BQ13 partner health | D: saved report and filters | S: explicit unavailable state | S: retain matching cache, report cancellation | P: online and offline queries tested, separate 5xx/reconnect not established | D: force-stop/reopen | S: account cleanup | S: backend restriction and cache invalidation; mobile 403 not executed | JP §4 E12–E15, §5 |
| Alternative meals | D: canonical exact snapshot marked historical | D: category, budget, time, noncanonical and absent-campus misses | D: controlled 503 + cancellation; partial physical interrupted refresh | D: A06 and controlled refresh | P: navigation persistence, not independent process death | D: controlled account isolation | S: repository handles 403; no live alternative 403 test | Natalia §5 A01–A10, §9.1–9.2 |
| BQ10 supply gaps | D: exact historical result with saved time | D: zone/date/dataset misses | D: controlled 503/IOException, session cancellation; P: live response ordering not instrumented | D: B08 and controlled reconnect | D: app reopen from recents, not OS process-death instrumentation | D: UI role switch and controlled storage cleanup | D: simulated mobile repository 403 deletes report rows; backend USER 403 verified; live UI not exercised | Natalia §4, §9; backend BQ10 |
| Synchronization center | D: pending/failed states visible locally | D: local empty/zero waiting state | D: lost ack, double retry, expired/failed fixtures | D: one effective delivery | D: pending work retained, emulator and phone | D: events kept with original account | S: worker handles endpoint-specific permanent/retryable status; no center-only 403 | Daniel §5/5.1 |
| Session/crash/comparison telemetry | D: durable marker and pending events | N/A: admission creates local record | D: injected crash, timeout, cancel; force-stop not counted as crash | D: marker import and effective delivery | D: crash/reopen, account-owned marker | D: marker never attributed to later account | S: authenticated event endpoints; analyst restriction is report-specific | Daniel §3, §5/5.1 |
| BQ1 performance report | D: exact saved report | D: other range without copy (emulator) | S: matching cache fallback and cancellation; no separate physical 5xx timing | P: online and cached report exercised | S: Room/filter persistence | D: USER no entry (emulator); S: scoped cleanup | D: emulator 403 clears saved copies; backend USER 403 | Daniel §5 and §5.1 |

Report-specific TTLs and exact keys are described in owner reports. No matrix cell represents a new experiment performed for this consolidation. The untested combinations are explicit review items, not silently passed checks. A final APK walkthrough must revisit critical inherited and new flows on one integrated version.

## 8. Review, authorization, data provenance and final release

### Cross-review status

| Reviewer → owner | Evidence / current state |
|---|---|
| Natalia → Juan Pablo | #42 reviewer reproduction accepted by owner; #41 profiling arithmetic and equivalence command reproduced. See accepted comments in §3. Earlier Sprint 3 placeholders remain historical. |
| Daniel → Natalia | #65 and #66 open with Daniel requested; no approval recorded at snapshot. Do not close #44/#45 or claim acceptance until review. |
| Juan Pablo → Daniel | Assisted physical evidence in PR #64 and closed #50; evidence collection is not itself a new endorsement of every S4-10 requirement. #49 still open. Confirm remaining owner/reviewer acceptance. |

### Authorization and interpretation

Student features are available to authenticated accounts; analyst views require ANALYST and the backend, not hidden UI alone, enforces role restrictions. BQ10 403 tests cover actual server rejection for USER and controlled Android deletion, not live role revocation during navigation. BQ13 and BQ1 separate actual zero/no observations, unknown coverage and suppressed cells, rather than treating withheld values as zeros. The frozen contract uses at least five distinct users for protected cells; BQ1 confidence additionally depends on sample coverage. Injected crashes and synthetic fixtures are labeled and excluded from real crash-rate interpretation where appropriate. Exact device coordinates are not persisted by the new BQ10 store or included in analytics; no coarse snapshot may be reused for a noncanonical location. This does not claim that restaurant routing never needs a current coordinate in a request.

### Release blockers and acceptance handoff

| Issue | State at snapshot | Next action |
|---|---|---|
| #38 scope, #39 backend BQ13, #40 reviews, #41 BQ3 optimization, #42 value proposition, #43 backend BQ10, #48 backend BQ1, #50 UTC optimization | Closed | Preserve source and evidence; reconcile limitations rather than reopening by default |
| #44 Android BQ10 | Open, PR #66 | Reviewer acceptance of 42 assertions, smoke checks and limitations, merge |
| #45 Room optimization | Open, PR #65 | Reviewer checks measurements and preservation; merge |
| #46 group delivery | Open | Review this document, actual wiki publication, official rubric crosswalk now included; wiki publication and internal screenshot criterion to reconcile |
| #47 shared profiling protocol | Open, implementation PR #53 merged | Owner/reviewer verify profileability, original evidence and protocol acceptance |
| #49 synchronization/BQ1 | Open, PRs #55/#64 merged | Owner/reviewer reconcile evidence and acceptance |
| #51 integrated release (S4-14) | Open | Signed APK using original signing identity, incremented versionCode, exact source/APK hashes, upgrade without loss, same-version physical checks and distribution confirmation |

No final release/distribution claim is made. The signed 0.4.0 upgrade was blocked on Juan Pablo's phone because the signing key was unavailable. Isolated debug/profile builds and disposable schema migrations cannot prove the distributed upgrade. Android final and backend deployed revisions must be recorded at release, not inferred from the main branch snapshot.

### Mandatory submission logistics (course description pp. 3–6)

- Repository/wiki and repository URL in Bloque Neón before November 28, 2026, 05:00 America/Bogota (10:00 UTC). Do not edit assessed material after the cutoff.
- Android: individual Firebase App Distribution invitations to all seven TAs listed in the official PDF, with ten-minute grace period until 05:10 Bogotá (10:10 UTC). Use `isis3510-24-android-Sprint4`, provide useful locations/test account instructions securely, and verify new-user registration. Immediately post in the general Teams channel: `As group 24, we have sent an apk named [actual filename].apk. We work in Kotlin for Android`. Do not record this as sent until actually sent.
- iOS: coordinate real-device testing with the respective TA via Teams and check mirror-feature/view parity, or record a specific authorized exception. Android-only delivery cannot prove the iOS requirement.
- Ethics: six-minute video; consult the syllabus and competence sheet for the exact required structure. Team must review, record and submit it; this document does not assert completion.
- Each contributor completes peer evaluation and checks their attributable commits, issue progress, PR reviews/approvals, milestone and Kanban board; reconcile the required squash/merge practice with the team's repository settings. Do not invent reviews or close incomplete work merely to improve the multiplier.
- Course policy on AI is stated in the official description p. 2; team reports prior permission for documentation organization/redaction. Contributors must review the draft, validate their own claims and follow the instructor's actual authorized scope.

## 9. Reviewer checklist before closing #46

- [x] Compare §1 with the official numbered rubric (detailed criteria, pp. 2–6).
- [ ] Verify all cited source and raw-evidence links against the final merged tree; PR #65/#66 heads intentionally remain immutable references.
- [ ] Confirm that the existing profiling logs and code satisfy official item 3; reconcile internal #46 screenshot request with reviewers. If screenshots are requested, open retained traces and capture the same measured region before/after, without inventing results. Keep originals local or use agreed controlled access.
- [ ] Review untested/partial failure matrix entries and record any supplemental runs or reviewer-accepted limitations.
- [ ] Confirm reviewer cycle and authorship without writing endorsements on behalf of others.
- [ ] Publish a Sprint 4 wiki section that links this document and preserves the Sprint 1–3 content.
- [ ] Reconcile release status with S4-14 after actual signed APK validation; do not mark final delivery complete prematurely.
- [ ] Confirm iOS parity/authorized exception, ethics video, BN link, Firebase/Teams and peer evaluation separately; these are not established by this index.

### Source index

[Scope](SPRINT4_SCOPE.md) · [Sprint 3 delivery](SPRINT3_DELIVERY.md) · [Value proposition](SPRINT4_VALUE_PROPOSITION.md) · [Juan Pablo](SPRINT4_JUAN_PABLO.md) · [Daniel](SPRINT4_DANIEL.md) · [Natalia supplement, pinned](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/9c7e6ad8350a334dc8be15d4155e71be12d17b9c/android/docs/SPRINT4_NATALIA.md) · [JP profiling](SPRINT4_MICROOPT_JP.md) · [Daniel profiling](SPRINT4_MICROOPT_DANIEL.md) · [Natalia profiling, pinned](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/SPRINT4_MICROOPT_NATALIA.md) · [Common protocol](SPRINT4_PROFILING_PROTOCOL.md) · [Backend BQ10](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/blob/1910b13522dbe2b16d8b6adbcd5954c57fc3892b/docs/SPRINT4_BQ10.md).
