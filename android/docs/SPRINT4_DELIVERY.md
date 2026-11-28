# Sprint 4 — CampusMeal Android delivery and evidence index

Tracking: [S4-13 / #46](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/46). Owner: Natalia Espitia; reviewer: Daniel Vargas. Evidence snapshot: November 26, 2026 (America/Bogota). Contributors: Juan Pablo, Natalia and Daniel. This document consolidates existing records; it does not replace earlier sprint wiki pages or claim a new test was run by its author.

**Historical delivery status (November 26 snapshot):** review draft. Android main inspected at [`288ad2f`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/288ad2fbcf33612b696facf0112a65695df4f6a2). Backend BQ10 evidence merged at [`1910b13`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/commit/1910b13522dbe2b16d8b6adbcd5954c57fc3892b). PRs #65 and #66 were open at that historical snapshot, and S4-14 release acceptance was pending; the final handoff below supersedes those states. Their evidence remains cited at the original immutable branch heads. Android subgroup only; no Swift views are counted.

## Final delivery handoff — November 28, 2026 (America/Bogota)

**Status: implementation merged, signed release distributed, all 14 tracking issues closed.** This handoff reconciles the November 26 evidence snapshot with Android main [`5ee7c1d`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/5ee7c1de78ec2755530f92668ad5b7452b2722e6) and the [final release record](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/5ee7c1de78ec2755530f92668ad5b7452b2722e6/android/docs/SPRINT4_RELEASE.md), including PRs [#71](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/71), [#72](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/72) and [#73](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/73). The November 26 profiling measurements and test conditions are preserved. This documentation update does not execute new tests or create approvals.

| Area | Final status and evidence |
|---|---|
| Implementation | All 27 merged Sprint 4 PRs (21 Android, 6 backend) have the Sprint 4 milestone. Android #56 was closed without merging. #65–#70 are merged; #71 reconciles delivery status, #72 records Daniel's release checks and #73 records Natalia's release checks. |
| Issue closure | All 14 Android tracking issues #38–#51 are closed, including [S4-14 / #51](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/51). Issue closure is a workflow fact, not proof that every acceptance checkbox or historical test limitation has been resolved. |
| Collaboration | Natalia's implementation PRs were merged by teammates. Actual reviews and traceability comments include her reproductions on #57 and #60 and her pre-merge approval of #72. Post-merge reviews are distinguished from pre-merge approvals. At this snapshot #73 has no formal review recorded. Native PR–issue linkage, squash compliance and final grading are not independently established. |
| Wiki | The team published the [SPRINT-4 wiki](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/wiki/SPRINT-4), [micro-optimizations](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/wiki/SPRINT-4-Micro-optimizations) and [connectivity/evidence](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/wiki/SPRINT-4-Evidence). The supplied Markdown versions cover the main rubric sections; 14 referenced screenshot paths and selected evidence paths were checked in the pinned repository trees. This is not an independent live rendering or exhaustive link check. |
| Kanban | [CampusMeal — Sprint 4 Kanban](https://github.com/orgs/ConstruccionAplicacionesMovilesGrupo24/projects/1/views/1) consolidates the 14 existing issues. It was created during final handoff, not retroactively presented as a board used throughout the sprint. The captured board showed 13 Done and #51 In Progress before #51 closed. The final board status and evaluator access have not been independently checked. |
| Platform scope | Following a teammate's departure, the remaining three contributors deliver Android/Kotlin and the shared backend. No Swift implementation or mirror-app parity is claimed. Retain the instructor's confirmation of the revised scope; this document does not independently establish a course exemption. |
| Signed release | CampusMeal 0.5.0 (versionCode 5), package `com.campusmeal.android`, Room 12, built from [`a816687`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/a816687ad9c87284bed8f5b67a0d3037038f5c14) as reported by Natalia. APK SHA-256 `0337fb3b1b5b5213506c860069be884b099182d3ef89c10ec8b1515f243243d0`. Both 0.4.0 and 0.5.0 use the same verified certificate. Juan Pablo's in-place upgrade succeeded on a prepared 0.4.0 fixture; the original long-lived installation had been removed, so its original history was not tested. |
| Release validation | All three owners' 0.5.0 validation rows are recorded in [SPRINT4_RELEASE.md](SPRINT4_RELEASE.md) §4.2. Juan Pablo and Natalia used physical devices; Daniel validated on an API 37 emulator with the distributed APK, with earlier assisted phone evidence credited to Juan Pablo. |
| Integrated smoke checks | All three correctness harnesses ran on `a816687` using the profile build: outbox 1 vs 8 callers MATCH, restaurant cache 16 assertions PASS, BQ10 42 assertions PASS. These are integrated correctness checks, not new before/after timings. |
| Backend and fixture cleanup | All 12 Neon migrations applied; Render health and endpoints verified. The temporary restaurant opening-hours fixture was restored on November 27 at 23:59 Bogotá (04:59:54Z), with every row compared to the backup before commit. The exact Render dashboard deployment SHA was not independently read; the release record identifies the deployed code through behavior and documentation-only subsequent commits. |
| Distribution and submission | Daniel uploaded Natalia's signed APK to Firebase on November 27 at 22:55:05 Bogotá. The release record lists six invitations (three team members and three further recipients) and confirms Natalia and Juan Pablo installed the release. The team reports that the APK, Firebase distribution, Teams notice, Bloque Neón repository link and [ethics video](https://youtu.be/CY06xh7cqT8) were submitted. The updated official evaluator list and individual evaluator access are not independently reconciled by this document. |
| Peer coevaluation | Awaiting the course form |

### Final owner validation — signed 0.5.0

| Owner | Environment | Recorded outcomes |
|---|---|---|
| Juan Pablo | SM-S948U1, Android 17, Nov 27 23:01–23:25 Bogotá | In-place upgrade, retained session/draft/events, offline review replay with one effective creation, BQ13, inherited flows, role rejection and alternative cache/network checks. Late-night positive availability used the temporary fixture, since restored. |
| Natalia | SM-A336M, Android 16/API 36, Nov 28 00:23–00:27 Bogotá | Eight alternatives/BQ10 connectivity and cache checks: SUSHI online, historical offline snapshot, SALADS cache miss and online recovery; Centro Nov 17 synthetic report online/offline, Teusaquillo cache miss and reconnect. Budget COP 40,000, 60 min and unrestricted diet retained. Both online alternatives responses were valid empty results; positive alternatives and small-sample suppression were not demonstrated in these final-run captures. Earlier positive alternatives and controlled checks remain separate evidence. |
| Daniel | API 37 emulator, distributed APK, Nov 27 23:46–23:53 Bogotá | Offline WALK selection queued; two waiting events drained after reconnection, each sent with one attempt. BQ1 received emulator 0.5.0 telemetry (0 of 2 sessions crashed, one fresh network load of 715 ms), exercised demo thresholds and saved report offline. No new physical-device run by Daniel is claimed. |

The [release record](SPRINT4_RELEASE.md) and [committed release evidence](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/tree/5ee7c1de78ec2755530f92668ad5b7452b2722e6/android/docs/evidence/sprint4/s4-14-release) are the detailed source for these observations. All implementation tracking issues are closed; remaining verification limitations are not erased by closure. In particular, the #51 issue body still contains unchecked acceptance boxes, and individual evaluator invitations/installation, course scope authorization and peer coevaluation remain separate from the recorded technical release.

---

## 1. Shared and individual rubric traceability

The following crosswalk uses the detailed numbered criteria in *ISIS-3510 — Sprint 4 Description: App Implementation and Definition*, pp. 2–6. The cover-page allocation differs from the detailed items; the detailed scores sum to 205 individual and 180 team points. Scores below are rubric weights, not earned grades. The official PDF is the authority for submission logistics; the internal #46 checklist is supplementary.

| Official criterion | Points | Where to review / current state |
|---|---:|---|
| 1. Inherited S2/S3 features and oral-feedback fixes | 0 (required) | §2, §7; scope and Sprint 3 delivery. Original regression evidence supplemented by signed 0.5.0 walkthrough; unexecuted scenarios retain their limits. |
| 2. Value proposition, features, BQs, revenue model and data | 10 | §3 and S4-12; #42 closed; limitations disclosed. |
| 3. Design, implement, justify and profile micro-optimizations | 40 | §5, code excerpts, pinned before/after sources and profiling logs/CSV; #41, #45 and #50 closed. Official criterion accepts logs **or** screenshots. |
| 4. One new view per teammate | 15 | §4: six new Android destinations, two per contributor; cross-platform parity not independently checked. |
| 5a–f. Features by sprint, BQs/owner/type/rationale, eventual connectivity, local storage, threading and caching | 0 (mandatory) | §2, §4, §6–7. Strategies, rationale, evidence and gaps. Missing this component prevents grading. |
| 6. Working app on real devices | Required | Signed 0.5.0 owner rows recorded; Juan Pablo and Natalia on physical phones, Daniel on emulator. Oral assessment remains separate. |
| 7. Share app with TAs | 0 (required) | Firebase distribution recorded; Teams notice team-reported. Updated evaluator list/access not independently reconciled. |
| 8. New original BQ per teammate | 20 | §4: BQ13, BQ10, BQ1; original wording and types. |
| 9. New feature per teammate; mirror apps | 80 | §4, §6–7; Android implemented; iOS parity or authorized exception needs team confirmation. |
| 9a. Multithreading/concurrency | 20 of 80 | §6–7: cancellation, revision guards, application/IO scopes, evidence. |
| 9b. Local storage | 20 of 80 | §6: additive migrations, Room, DataStore, durable markers. |
| 9c. Caching | 20 of 80 | §6–7: exact keys, TTL, bounded retention and historical fallback. |
| 9d. Eventual connectivity for every functionality | 20 of 80 | §7: all-feature matrix; not every failure combination executed. |
| 10. Six-minute ethics video | 46 team | Team reports submitted; video linked in wiki. Rubric compliance not independently evaluated. |
| 11. Coevaluation and collaboration/repository multiplier | 134 team | §8: actual commits, issues, PRs and Kanban. Peer form pending; final multiplier not established. |
| 12. Readable, consistent and complete written artifacts | 20 individual | This index, owner reports and three wiki pages; remaining verification limitations disclosed. |
| 13. Repository tools and process | 20 individual | §8: issues, PRs, reviews, commits, milestones and retrospective Kanban; native linkage, squash and grading not established. |

## 2. Inherited functionality and closure

| Feature / BQ | Origin | Owner / attribution | Sprint 4 treatment and evidence |
|---|---|---|---|
| Authentication/session/profile | S2, extended S3 | Juan Pablo | Preserved Keystore/session generations and account profile; student/analyst sign-in in Natalia §9.2; transient refresh/expiry combinations not re-established in latest walkthrough |
| Inventory / BQ2 | S2, extended S3 | Juan Pablo | S4-01 adds deduplicated INVENTORY usage event. Scope §3.3: eight scenarios, offline/restart/account change, six server rows across two accounts. Natalia §9.2 shows inventory |
| Recipe evaluation / BQ3 | S3 | Juan Pablo | S4-05 ingredient indexing; 2 equivalence tests, offline catalog strategy; latest Natalia recipe query returned zero matches |
| Restaurant search/detail / BQ4 | S2, extended S3 | Natalia | S4-08 removes duplicate Room fetch; #65 merged. Natalia §9.2 valid empty nighttime search; A09–A10 positive alternative-to-detail on Juan Pablo's device |
| Restaurant cache supporting BQ4 | S3 | Natalia | Existing 5/30-minute search/detail policies; S4-08 changes fetch mechanism only, not new view/BQ |
| Cook / Walk / Order / BQ5 | S2, extended S3 | Daniel | Natalia §9.2 comparison and saved Cook plan; Daniel §5 emulator previous/offline comparison telemetry; latest physical previous-comparison retrieval not independently repeated |
| Explanation/selection / BQ8 | S2 | **Carlos, original BQ author; Daniel, Android integration** | Existing impression/selection outbox retained. Not a new S4 BQ |
| Analytics outbox and adoption / BQ6 | S3 | Daniel | S4 extends dispatcher/retention; scope §3.3 and Daniel §5/5.1; Natalia §9.2 BQ6 month navigation |
| Zone demand / BQ9 | S3 | Natalia | Reused for BQ10; Natalia §9.2 BQ9 range/zone and suppression; existing exact report cache |

### Scope gap reconciliation (G-1–G-7; historical evidence with final handoff above)

- G-1: Inventory usage event implemented and exercised (scope §3.3; PR #52).
- G-2: Prior Sprint 3 Daniel document says restaurant search/detail usage emitters pending; scope §2.2 establishes they already existed, and Sprint 3 delivery lists commits `e1711fb`, `9867298`. Preserve the older report as historical; refer to the corrected scope and current ViewModels. A dedicated new physical emission count for both restaurant events was not independently established here.
- G-3: Daniel's old pending device rows are not automatically closed by the new sync tests. See Daniel §5–5.1 for actual executed emulator/physical cases; the signed walkthrough supplements those rows; unexecuted historical cases retain their original limits.
- G-4: Earlier Sprint 3 review placeholders are not completed retrospectively. S4-12 reviewer evidence is accepted; see §8 for remaining review cycle.
- G-5: Older release documentation names 0.3.1/code 3, while baseline Gradle and scope specify 0.4.0/code 4. Do not infer which APK is installed from Gradle alone. S4-14 now records signed APK version, hash, distribution and in-place upgrade on a prepared 0.4.0 fixture.
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
| Natalia | [#28](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/28), [#29](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/pull/29) merged | [#61](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/61), [#62](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/62) merged; [#65](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/65), [#66](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/pull/66) merged | [S4-07 report with supplement, PR #66](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/9c7e6ad8350a334dc8be15d4155e71be12d17b9c/android/docs/SPRINT4_NATALIA.md), [S4-08 profiling, PR #65](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/SPRINT4_MICROOPT_NATALIA.md) |
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

Controlled APK SHA-256 before `ACFDCCDDB8CAB66CB71D9106E6495353E22E19D869C490E745B9C71E46763EBB`; after `7C4180AB67EC68A8CDFB1D60C907EE55905BD47204478277C391C0689CD2A0DD`. Representative before full hash not available. Build blobs, 40 CSV/log pairs, run-level values and audit in owner report. 16 controlled correctness assertions passed on device; representative CSVs and controlled binary-to-CSV audit reported by owner documentation. Neither CPU/energy nor every intermediate timed return is asserted. Same-region profiler screenshot pair: pending; original binaries retained locally, not in PR. Daniel recorded a post-merge technical review of #65; this does not constitute pre-merge approval.

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
| Daniel / sync + BQ1 | Room v11 outbox attempt/close fields and `performance_reports`, 15 min/20/account, DataStore filters; local bounded crash marker | Durable enqueue, stable ids, unique WorkManager chain, application-scope telemetry, IO marker import; report cancellation; no non-idempotent mutation replay | S4-10 §5 emulator, §5.1 assisted phone crash, lost ack, double retry, account switching and BQ1 fixtures; signed 0.5.0 emulator validation and separate assisted phone evidence; upgrade recorded in S4-14 |

Source navigation: [Juan Pablo source descriptions](SPRINT4_JUAN_PABLO.md#2-s4-04--what-was-built); [Natalia repository and DAO](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Data.kt), [Natalia ViewModels and screens](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/f7ace38/android/app/src/main/java/com/campusmeal/android/feature/bq10/Bq10Screens.kt); [Daniel implementation and lifecycle](SPRINT4_DANIEL.md#4-local-storage-cache-threading-and-connectivity). Migrations v9→10, 10→11, 11→12 are additive; [migration source](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/288ad2fbcf33612b696facf0112a65695df4f6a2/android/app/src/main/java/com/campusmeal/android/core/database/DatabaseMigrations.kt). The v11→12 controlled test preserved synthetic inventory, cache timestamp, review draft and pending outbox, and Room validated v12; it is separate from the signed v9→v12 upgrade now recorded in S4-14.

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

Report-specific TTLs and exact keys are described in owner reports. No matrix cell represents a new experiment performed for this consolidation. The untested combinations are explicit review items, not silently passed checks. The final signed-APK walkthrough and owner checks now supplement the matrix (see final handoff and S4-14 §4); the original D/P/S labels retain their stated scope.

## 8. Review, authorization, data provenance and final release

### Cross-review status

| Reviewer → owner | Evidence / current state |
|---|---|
| Natalia → Juan Pablo | #42 reviewer reproduction accepted by owner; #41 profiling arithmetic and equivalence command reproduced; recorded technical reviews on #57 and #60. Earlier Sprint 3 placeholders remain historical. |
| Daniel → Natalia | #65 and #66 merged; #44 and #45 closed. Daniel recorded a technical post-merge review of #65 and a technical acceptance comment on #66. #71 also has a post-merge review. These are not pre-merge approvals. |
| Juan Pablo → Daniel | Assisted physical evidence in PR #64; #49, #50 and #51 are closed. Evidence collection is not itself a formal approval. Natalia approved #72 before merge; #73 had no formal review at the final inspected snapshot. |

### Authorization and interpretation

Student features are available to authenticated accounts; analyst views require ANALYST and the backend, not hidden UI alone, enforces role restrictions. BQ10 403 tests cover actual server rejection for USER and controlled Android deletion, not live role revocation during navigation. BQ13 and BQ1 separate actual zero/no observations, unknown coverage and suppressed cells, rather than treating withheld values as zeros. The frozen contract uses at least five distinct users for protected cells; BQ1 confidence additionally depends on sample coverage. Injected crashes and synthetic fixtures are labeled and excluded from real crash-rate interpretation where appropriate. Exact device coordinates are not persisted by the new BQ10 store or included in analytics; no coarse snapshot may be reused for a noncanonical location. This does not claim that restaurant routing never needs a current coordinate in a request.

### Release closure and acceptance handoff

| Issue | Final state | Evidence / limit |
|---|---|---|
| #38–#43 | Closed | Scope, BQ13, reviews, BQ3 optimization, value proposition and backend BQ10 records retained. |
| #44–#46 | Closed | BQ10 Android, Room optimization and consolidated delivery; #65, #66, #67 and #71 merged. |
| #47–#50 | Closed | Profiling protocol, backend BQ1, synchronization/BQ1 and UTC optimization; source and run evidence retained. |
| [#51 — S4-14](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/51) | Closed | Signed release 0.5.0, owner validation rows, fixture restoration, Firebase distribution record and integrated harness checks in [SPRINT4_RELEASE.md](SPRINT4_RELEASE.md); limitations remain explicitly documented. |

The original failed/blocked upgrade attempt is historical. S4-14 later demonstrated the in-place update using the exact distributed 0.4.0 binary and a newly prepared fixture. The exact original installation history was not tested. Daniel's owner validation used the final APK in an emulator. Issue closure is not a substitute for the unchecked acceptance boxes, evaluator access or rubric grading.

### Mandatory submission logistics (course description pp. 3–6)

- Repository/wiki and repository URL in Bloque Neón before November 28, 2026, 05:00 America/Bogota (10:00 UTC). Do not edit assessed material after the cutoff.
- Android: individual Firebase App Distribution invitations to all official TAs (seven listed in the original PDF; the team reports one TA left, so the current authorized roster must govern), with ten-minute grace period until 05:10 Bogotá (10:10 UTC). Use `isis3510-24-android-Sprint4`, provide useful locations/test account instructions securely, and verify new-user registration. Immediately post in the general Teams channel: `As group 24, we have sent an apk named [actual filename].apk. We work in Kotlin for Android`. The team reports the Teams notice was sent; external delivery details are not independently checked.
- iOS: coordinate real-device testing with the respective TA via Teams and check mirror-feature/view parity, or record a specific authorized exception. Android-only delivery cannot prove the iOS requirement.
- Ethics: six-minute video; consult the syllabus and competence sheet for the exact required structure. The team reports submission and links the video in the wiki; rubric compliance has not been independently evaluated.
- Each contributor completes peer evaluation and checks their attributable commits, issue progress, PR reviews/approvals, milestone and Kanban board; reconcile the required squash/merge practice with the team's repository settings. Do not invent reviews or close incomplete work merely to improve the multiplier.
- Course policy on AI is stated in the official description p. 2; team reports prior permission for documentation organization/redaction. Contributors must review the draft, validate their own claims and follow the instructor's actual authorized scope.

## 9. Final documentation checklist and verification limits

- [x] Compare the crosswalk with the official numbered rubric (detailed criteria, pp. 2–6).
- [x] Reconcile the final repository workflow: 27 merged PRs with Sprint 4 milestone and 14 closed tracking issues.
- [x] Publish the three Sprint 4 wiki pages (team-reported publication; supplied Markdown inspected).
- [x] Document the retrospective Kanban and its original issue links; final board state and evaluator access still require direct confirmation.
- [x] Reconcile signed release 0.5.0, build identity, all three owner validation rows, three integrated harness checks and restored opening hours against the final release record.
- [x] Record team-reported delivery of Firebase/Teams, Bloque Neón and ethics video without inferring evaluator acceptance.
- [ ] Independently verify every source/evidence link and live wiki rendering; selected pinned paths were checked, not every link.
- [ ] Reconcile the internal same-region profiler screenshot request, unexecuted failure paths and remaining physical-device limitations with course policy. Official criterion 3 permits profiling logs or screenshots plus code; no new captures or tests are claimed.
- [ ] Confirm all actual reviewer actions, native PR–issue relations, final Kanban status/access and squash compliance where applicable. Do not backdate or invent evidence.
- [ ] Reconcile the updated official TA roster with the recorded Firebase invitations and confirm the authorized Android-only scope. Evaluator download/installation is not inferred.
- [ ] Complete peer coevaluation when the course form becomes available.

### Source index

[Final release](SPRINT4_RELEASE.md) · [Sprint 4 wiki](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/wiki/SPRINT-4) · [Kanban](https://github.com/orgs/ConstruccionAplicacionesMovilesGrupo24/projects/1/views/1)

[Scope](SPRINT4_SCOPE.md) · [Sprint 3 delivery](SPRINT3_DELIVERY.md) · [Value proposition](SPRINT4_VALUE_PROPOSITION.md) · [Juan Pablo](SPRINT4_JUAN_PABLO.md) · [Daniel](SPRINT4_DANIEL.md) · [Natalia supplement, pinned](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/9c7e6ad8350a334dc8be15d4155e71be12d17b9c/android/docs/SPRINT4_NATALIA.md) · [JP profiling](SPRINT4_MICROOPT_JP.md) · [Daniel profiling](SPRINT4_MICROOPT_DANIEL.md) · [Natalia profiling, pinned](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/blob/205830c211b0bc73610bdc0e1a56fcc7d735e150/android/docs/SPRINT4_MICROOPT_NATALIA.md) · [Common protocol](SPRINT4_PROFILING_PROTOCOL.md) · [Backend BQ10](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/blob/1910b13522dbe2b16d8b6adbcd5954c57fc3892b/docs/SPRINT4_BQ10.md).