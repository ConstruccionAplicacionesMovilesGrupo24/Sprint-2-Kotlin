# S4-05 — "after" capture conditions

Filled in from `SPRINT4_PROFILING_PROTOCOL.md` §4. Raw values: `representative/sections.csv` and
`stress/sections.csv` (from the original traces with `scripts/profiling/sections.js`); summaries in `summary.txt`.

| Field | Value |
|---|---|
| Issue / variant / Git SHA | S4-05 / **after** / `97eed472ff0df37bfeedd5ae266a14eede6af20c`. Between `df0a40e` and it, the only change under `android/app` and the Gradle files is `RecipeEvaluator.kt` (`git diff --stat df0a40e 97eed47 -- android/app android/gradle`); the commit also adds this evidence, the docs and the profiling scripts, none of which is in the APK |
| Phone, Android version, API level | Samsung SM-S948U1, Android 17, API 37 (same phone as "before") |
| APK version and SHA-256 | `0.4.0-profile`, `4d9724840631304d20e9dbed8e47e8c8f9aca1bc793534d76f719e5f1e6fc50c`; installed over the "before" APK, data kept |
| Compilation mode, warmup runs, measured runs | `cmd package compile -m speed -f` after install. 3 discarded warmup runs per dataset. Representative: 10 measured runs (same count as "before"). Stress: 5 measured runs (8.8 % spread) |
| Cold or warm; cache state; airplane mode | Same as "before": warm, one Recipes visit per dataset, catalog and inventory in Room, airplane mode on (restored afterwards) |
| Dataset | Same as "before": real catalog `2026-10-27.1` (6 recipes) + the analyst's 50 items; synthetic stress 300 / 500, seed 3500, today `2026-11-25` |
| Workload of one run | Identical: Perfetto 30 s, 6 slider taps 20 → 120 min, 6 uncached `cm.bq3.evaluate` sections per run |
| Raw values per run | `representative/sections.csv` (60 rows), `stress/sections.csv` (30 rows) |
| Median, minimum, maximum | **Representative:** median of run medians **2.014 ms**, range 1.907–2.523 ms (all 60: median 2.091, min 1.697, max 3.869). **Stress:** median of run medians **17.669 ms**, range 17.132–18.682 ms (all 30: median 17.564, min 15.371, max 21.621) |
| Limitations, discarded runs and why | None discarded. Representative spread 30.6 %: runs 7–10 were ~0.4 ms slower than runs 1–6 (likely CPU frequency / thermal state; not controlled beyond the protocol). Battery 79–80 %, charging over USB, 25.2–25.9 °C. Same date as "before" (2026-11-25), about 15 minutes later |
