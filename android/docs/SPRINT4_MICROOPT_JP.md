# Sprint 4 — Micro-optimization of the BQ3 ingredient lookup (S4-05)

Issue: [S4-05 / #41](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/41).
Owner: Juan Pablo (@bedoya0611). Reviewer: Natalia (@NataliaEspitia). Protocol: `SPRINT4_PROFILING_PROTOCOL.md` (S4-02).

**Result.** On the stress dataset (300 recipes / 500 inventory rows) one uncached BQ3 evaluation went from
**28.41 ms to 17.67 ms** (median of run medians; −37.8 %, 1.61×), with non-overlapping ranges and a bit-for-bit
identical output. On the real data (6 recipes / 50 rows) **no difference was demonstrated** (2.04 → 2.01 ms,
overlapping ranges).

## 1. Hot path

`feature/recipes/domain/RecipeEvaluator.kt` normalizes the usable inventory once, then, for **each ingredient of
each recipe**, `checkIngredient` filtered the **whole** usable list:

```kotlin
// before (df0a40e)
val keys = ingredient.matchingKeys
val named = usable.filter { it.normalizedName in keys }
```

Cost: recipes × ingredients × usable rows set lookups. For the stress dataset (300 recipes of 2–7 ingredients, about
70 % of 500 rows usable) that is roughly half a million row checks per evaluation. Sprint 3 work (evaluation on `Dispatchers.Default`, cooperative
cancellation, 5-result memo) is unchanged and is not counted as part of this optimization.

## 2. Change (one dedicated commit, `RecipeEvaluator.kt` only)

A `UsableIndex` is built once per `evaluateBq3` call: usable-row positions grouped by normalized name. Each
ingredient resolves its `matchingKeys` through the map instead of scanning every row.

```kotlin
// after
val index = UsableIndex(usable)                       // once per evaluation, local to the call
val named = index.rowsMatching(ingredient.matchingKeys)
```

What is preserved, and why it is identical:

| Property | How |
|---|---|
| Same rows | A row has exactly one normalized name, so groups of different keys are disjoint: an alias never counts a row twice |
| Original row order | Each group keeps inventory order; when several keys match, positions are merged and sorted back to inventory order |
| Same floating-point totals | `available` is still `comparable.sumOf { … }` over rows in the original order |
| Unit conversion, optional ingredients, expiry priority, sorting | Untouched code after the lookup |
| Cancellation | `checkCancelled()` still runs per row (normalization), per recipe and per ingredient |
| Lifetime | The index is a local of one evaluation; nothing is cached across evaluations or accounts |

## 3. Equivalent output

`app/src/test/java/…/recipes/domain/Bq3EquivalenceTest.kt` (JVM, `./gradlew :app:testDebugUnitTest --tests '*Bq3EquivalenceTest*'`):

- Golden files written by the **unoptimized** evaluator in the baseline commit `df0a40e` (`src/test/resources/bq3/`), never
  rewritten afterwards.
- `edgeCasesMatchGolden`: hand-written fixture with aliases (including an alias equal to the name and a shared alias),
  mixed units in one dimension, a non-comparable unit, `0.15 kg` vs `150 g`, missing / expired / zero / negative / NaN /
  invalid-date rows, an item expiring today, optional ingredients, duplicate names by spelling, an empty recipe and
  ordering ties broken by id; 5 filter combinations; full dump compared as text.
- `syntheticDatasetsMatchGolden`: 4 synthetic datasets (30/50, 300/500, 60/120, 10/500) × 5 filter combinations; SHA-256
  of the full dump.
- Every field is dumped, doubles also as raw bits (`doubleToLongBits`), so a different summation order would fail.

| Check | Result |
|---|---|
| Baseline evaluator | 2 tests, 0 failures (golden written from it) |
| Optimized evaluator | 2 tests, 0 skipped, 0 failures |
| Sensitivity: optimized evaluator **without** the merge sort | Both tests fail (`ComparisonFailure`), so the check does detect an order change |

On the phone the stress run shows the same "26 recipes match" before and after.

## 4. Measurement

Same phone, APK build type, account, datasets, date and workload; only the optimization commit differs.

| | Before | After |
|---|---|---|
| Git | `df0a40e` | `df0a40e` + optimization commit |
| APK SHA-256 | `153cf5c5…a3ae3d6` | `4d972484…1e6fc50c` |
| Phone | Samsung SM-S948U1, Android 17, API 37 | same |
| Conditions | `compile -m speed`, 3 warmup runs, airplane mode, warm Recipes visit, charging over USB | same |

Workload of one run: Perfetto system trace (`scripts/profiling/campusmeal.pbtxt`, 30 s); six taps on the maximum
preparation time slider cycling 20 → 40 → 60 → 80 → 100 → 120 min. With a 5-entry LRU memo, a 6-value cycle never
hits, so every tap is one uncached `cm.bq3.evaluate` section. Values were extracted with
`scripts/profiling/sections.js` (same B/E pairing as `sections.sql`) and summarized with `scripts/profiling/summary.js`.

### 4.1 Stress (synthetic, 300 recipes / 500 rows)

| Variant | Runs | Per-run medians (ms) | Median of runs | Range |
|---|---:|---|---:|---|
| Before | 5 | 26.497, 29.484, 27.283, 28.412, 28.735 | **28.412** | 26.497–29.484 |
| After | 5 | 17.669, 17.132, 18.622, 17.276, 18.682 | **17.669** | 17.132–18.682 |

Improvement: (28.412 − 17.669) / 28.412 = **37.8 %** (1.61×). The slowest "after" run (18.682) is faster than the
fastest "before" run (26.497): the benefit (≈10.7 ms) exceeds the observed variation (≤ 3.0 ms in either variant).

### 4.2 Representative (real: 6 recipes, 50 rows)

| Variant | Runs | Median of runs | Range |
|---|---:|---:|---|
| Before | 10 | 2.043 | 1.861–2.299 |
| After | 10 | 2.014 | 1.907–2.523 |

The ranges overlap: **no difference is demonstrated** on real data. With 6 recipes the lookup is a small part of the
section; normalizing the 50 inventory names (NFD + regex), which this change does not touch, dominates.

Original traces, raw CSVs and filled-in conditions: `docs/evidence/sprint4/s4-05-bq3/{before,after}/`.

## 5. Interpretation and limits

- The optimization removes the recipes × ingredients × rows factor; its benefit grows with catalog and inventory size.
  The production catalog is small today (6 recipes), so users would not notice it yet; it protects BQ3 as the catalog
  grows.
- The stress dataset is synthetic and labeled as such; it is only active in the `profile` build when selected at launch.
- Timings are wall-clock section durations from a system trace on one phone; CPU frequency was not pinned (the
  representative "after" series has slower runs 7–10). The stress conclusion does not depend on it: no overlap.
- Allocation profiles (debug build) were not recorded; optional in the plan.
- Next candidate if more is needed: the per-evaluation name normalization, which now dominates small datasets.

## 6. Reproduce

```bash
./gradlew :app:testDebugUnitTest --tests '*Bq3EquivalenceTest*'
./gradlew :app:assembleProfile && adb install -r app/build/outputs/apk/profile/app-profile.apk
adb shell cmd package compile -m speed -f com.campusmeal.android.profile
adb shell am start -S -n com.campusmeal.android.profile/com.campusmeal.android.app.MainActivity --es cm.bq3.dataset stress
# Recipes → airplane mode → per run: perfetto (campusmeal.pbtxt) + 6 slider positions → adb pull
node scripts/profiling/sections.js run-*.perfetto-trace > sections.csv && node scripts/profiling/summary.js sections.csv
```

## 7. Acceptance (S4-05)

- [ ] Dedicated optimization commit and exact before/after source links (commit pending; before = `df0a40e`).
- [x] Equivalent outputs, preserved cancellation/session behavior and no regression on representative data (§3, §4.2).
- [x] Original profiler captures, raw timings, median/range and improvement calculation published (§4, evidence folder).
- [x] Measured benefit exceeding observed run variation (stress, §4.1).
- [x] This document contains evidence, code and interpretation.
