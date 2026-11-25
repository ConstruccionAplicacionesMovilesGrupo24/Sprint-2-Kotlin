# S4-05 — "before" capture conditions

Filled in from `SPRINT4_PROFILING_PROTOCOL.md` §4. Raw values: `representative/sections.csv` and
`stress/sections.csv` (from the original traces with `scripts/profiling/sections.js`); summaries in `summary.txt`.

| Field | Value |
|---|---|
| Issue / variant / Git SHA | S4-05 / **before** / `df0a40e` (branch `sprint4/s4-05-bq3-lookup`: `main` `b5dceee` + measurement harness, evaluator unchanged) |
| Phone, Android version, API level | Samsung SM-S948U1, Android 17, API 37 |
| APK version and SHA-256 | `0.4.0-profile` (`com.campusmeal.android.profile`), `153cf5c5dd934afec6b07b8c71eb85dcfb7a9f63a6fd53496130df148a3ae3d6`; profileable, not debuggable |
| Compilation mode, warmup runs, measured runs | `cmd package compile -m speed -f` after install. 3 discarded warmup runs per dataset. Representative: 10 measured runs (the first 5 had a 21.9 % spread, above the 20 % rule). Stress: 5 measured runs (10.5 % spread) |
| Cold or warm; cache state; airplane mode | Warm: one Recipes visit kept open per dataset. Catalog and complete inventory already stored in Room. Airplane mode on during every run (restored afterwards) |
| Dataset | **Representative (real):** production catalog `2026-10-27.1`, 6 recipes; analyst inventory of 50 items created through the API on 2026-11-25 (mixed units, Spanish aliases, duplicates, 1 expired, 1 zero). **Stress (synthetic, labeled):** `Bq3SyntheticDataset.stress("2026-11-25")`, 300 recipes / 500 items, seed 3500, selected with `--es cm.bq3.dataset stress` (profile build only), generated outside the section |
| Workload of one run | Perfetto 30 s with `scripts/profiling/campusmeal.pbtxt`; 3 s later, 6 taps on the "Maximum preparation time" slider cycling 20 → 40 → 60 → 80 → 100 → 120 min (1.3 s apart). The 5-entry LRU memo never holds the next value of a 6-value cycle, so each tap is one uncached evaluation: 6 `cm.bq3.evaluate` sections per run |
| Raw values per run | `representative/sections.csv` (60 rows), `stress/sections.csv` (30 rows) |
| Median, minimum, maximum | **Representative:** median of run medians **2.043 ms**, range 1.861–2.299 ms (all 60 evaluations: median 2.016, min 1.606, max 4.334). **Stress:** median of run medians **28.412 ms**, range 26.497–29.484 ms (all 30: median 27.951, min 24.675, max 31.939) |
| Limitations, discarded runs and why | One earlier probe trace (tooling check of `sections.js`) is not part of the series. `cm.bq3.evaluate` also includes the inventory normalization (NFD + regex per row), which the optimization does not target; on the 6-recipe representative data this fixed cost dominates. Battery 70–80 %, charging over USB, 25.7–27.7 °C, battery saver off. Date 2026-11-25 (Bogotá) |
