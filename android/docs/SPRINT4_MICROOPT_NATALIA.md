# Sprint 4 — S4-08: one Room snapshot per restaurant cache read

Tracking: [#45](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/45). Owner: Natalia. Reviewer: Daniel. Protocol: `SPRINT4_PROFILING_PROTOCOL.md`.

## Hypothesis and implementation

The fresh search and detail paths formerly fetched a Room row to check freshness and then fetched it again before decoding. The optimized paths pass the first entity to `decodeCachedSearch` or `decodeCachedDetail`. The ordinary cached paths reuse those decoders. The diff changes one file (24 insertions, 2 deletions), retains the original TTL and timestamps, and checks the current session before the read and again before decoding. No schema or new in-memory cache is introduced.

- Before source: `db1fe82ec9a84f02660bc1f3a8167568791218b2`.
- After source: `d72b9cee67328eb95fca7b77a25e10848608442c`.
- After profile APK SHA-256: `3B6BC9FA96A292B1CC96EB7BD42605E8FFF17EC346F0D9ACDFBA73DD073C23FC`.
- Before APK full hash: not recorded in the available transcript; recover if available, otherwise disclose.
- Device reported during measurement preparation: Samsung Galaxy A33 (SM-A336M), Android 16 / API 36.
- Profile build (`com.campusmeal.android.profile`), speed compilation via `cmd package compile -m speed -f`. After build and existing debug unit-test task reported `BUILD SUCCESSFUL`.

## Representative workload

Separate search and Andean Flavor detail recordings, five operations per 30-second trace, ten traces per workload and variant. Same intended campus context, account and filters (Uniandes, 90 minutes, COP 40,000, Any dietary preference, delivery off); offline fresh-cache path. Search TTL is five minutes and detail TTL is 30 minutes. Cache renewal was performed outside the measured interval when necessary. Exact payload bytes, catalog equivalence, battery/thermal state and warmup completion are not independently established by the CSVs.

Trace sections: `cm.restaurant.cache.read` and `cm.restaurant.cache.decode`. For each operation, sum the recorded section durations; calculate the median of five operations in each run and then the median of ten run medians. Spread is (maximum run median − minimum run median) / median of run medians. This is instrumented elapsed time, not CPU time or full screen latency. Mapping outside the decode trace is not measured separately.

| Workload | Variant | Runs | Operations | DAO read spans | Decode spans | Median (ms) | Run median range (ms) | Spread |
|---|---|---:|---:|---:|---:|---:|---|---:|
| Detail | Before | 10 | 50 | 100 | 50 | 15.765 | 13.134–16.398 | 20.71% |
| Detail | After | 10 | 50 | 50 | 50 | 12.672 | 10.988–13.498 | 19.81% |
| Search | Before | 10 | 50 | 100 | 50 | 4.279 | 3.275–9.312 | 141.09% |
| Search | After | 10 | 50 | 50 | 50 | 2.963 | 2.200–4.192 | 67.23% |

Observed reductions in median instrumented duration: detail 19.62%, search 30.76%. Both routes reduce instrumented DAO fetches from two to one per fresh lookup (50%). Run ranges overlap for both workloads, particularly search; timing improvement is descriptive, not proof that the benefit exceeds variability. No run was dropped from the official series. Earlier diagnostic runs with two reads after optimization were separated because the intended fresh-cache condition was not established; the pattern is consistent with stale-cache fallback. The representative timings do not establish a benefit above variability; see the controlled pair below for the fixed-size workload.

## Run-level measurements

Each cell is the median summed read + decode time in ms for five operations. Run numbers are identifiers, not paired experiments.

| Run | Detail before | Detail after | Search before | Search after |
|---|---:|---:|---:|---:|
| 01 | 15.2854 | 11.5334 | 4.0641 | 3.0595 |
| 02 | 15.8129 | 12.5409 | 3.5020 | 3.4193 |
| 03 | 13.1342 | 13.4982 | 9.1527 | 2.8660 |
| 04 | 13.9674 | 13.0475 | 3.5376 | 4.1919 |
| 05 | 15.2581 | 11.1885 | 4.6100 | 3.7416 |
| 06 | 16.1833 | 11.3769 | 4.6034 | 2.2012 |
| 07 | 16.3983 | 13.1077 | 3.2751 | 2.4803 |
| 08 | 16.1755 | 10.9884 | 9.3119 | 3.1995 |
| 09 | 15.7163 | 12.8028 | 4.4933 | 2.1999 |
| 10 | 16.1345 | 13.4157 | 3.8928 | 2.3867 |

## Evidence paths

Relative to `android/docs/evidence/sprint4/s4-08-room-cache/`:

- `before/representative/`: `run-01` through `run-10` traces and CSVs; `sections.csv`.
- `after/representative/`: same names and file types.
- `before/search/`: `search-01` through `search-10` traces and CSVs; `sections.csv`.
- `after/search/`: same names and file types.
- `pilot/`: diagnostic captures, separate from official results.

The four supplied ZIPs contain the 40 binary traces and their CSVs. All individual CSVs match their consolidated CSV and all chronological sequences match the expected read/read/decode or read/decode pattern. Binary traces were not independently reprocessed. Confirm these relative paths in the repository before publishing; avoid committing duplicate archives.

## Controlled small/large workload (R09)

A profile-only `RestaurantCacheCheckActivity` exercises the production repository against a fresh disposable on-disk Room database, disposable DataStore, synthetic session, fixed clock and fake API. No user cache is edited. The controlled pair uses the same harness and trace configuration in both variants; only `RestaurantRepository.kt` is temporarily restored to its pre-optimization blob for the baseline. The AFTER was collected first, then the BEFORE. Both build metadata files therefore show the same Git HEAD; the recorded repository blob distinguishes the variants. The optimized source was restored afterwards and the unit-test/assembly tasks and installation succeeded. Final package speed compilation output was not included in the last transcript.

| Build metadata | Before | After |
|---|---|---|
| Repository blob | `2ece913de6d437ecbe1aabff81e68d51e97d6714` | `33bd5d1d8b97b6fe2acedc569867b4a4a44436fa` |
| APK SHA-256 | `ACFDCCDDB8CAB66CB71D9106E6495353E22E19D869C490E745B9C71E46763EBB` | `7C4180AB67EC68A8CDFB1D60C907EE55905BD47204478277C391C0689CD2A0DD` |
| Device / OS / API | SM-A336M / Android 16 / 36 | Same |

These are the controlled APK hashes, not the earlier manual-workload APK hashes. Both installed via ADB and compiled with `-m speed`. The script checks the repository blob and refuses to mix APK hashes within a variant. Three standalone warmup workloads per size on the first batch; each measured process additionally performs 15 search and 15 detail warmups before the timed bursts. The second AFTER batch resumes at run 06, with the same APK and per-process warmups. Every recording contains one burst of 100 fresh searches followed by one burst of 100 fresh details. Ten independent traces per size per variant, each captured for 30 seconds. No real HTTP calls are made by the harness; all 40 logs report `apiCalls=0 PASS`. The protocol requests airplane mode and stable battery/thermal conditions; those device settings are not independently logged in the archive.

| Payload | Small (5 entries) | Large (200 entries) |
|---|---:|---:|
| Search (restaurants) | 802 bytes | 31,997 bytes |
| Detail (meals) | 471 bytes | 11,191 bytes |

The SHA-256 signatures below match across all 20 traces for each size (10 before + 10 after). The harness also checks the expected domain result on each warmup and the final result of each measured burst. It does not assert the equality of every intermediate timed return value individually.

- Small: `searchBytes=802 detailBytes=471 searchSha=9261f49af27ac0e6020e64edbf0ecb34a070063ef2522153f0c5616412bfbede detailSha=e693303c350741e8a0df5cab53e54533f01ec89838a1601d81626f4e78f37eca`.
- Large: `searchBytes=31997 detailBytes=11191 searchSha=19307bac2f06d80aeff217406555717a1fd2ee3450dd37926556ee0884909f9b detailSha=e3f701f07bf01d4fb0ecd08772cb23cb4d7dcff293f503b3e62e1415b91fa079`.

### Results

Each value below is the median or min–max range across ten independent bursts of 100 operations. The outer `cm.s4-08.search.small/large` and `cm.s4-08.detail.small/large` asynchronous sections measure elapsed repository time, including mapping and suspension; the sum of nested read/decode spans excludes mapping outside the decode marker and other overhead. Neither is CPU time, nor end-to-end UI latency.

| Workload | Metric | Before median (range), ms | After median (range), ms | Median reduction | Ranges overlap? |
|---|---|---:|---:|---:|---|
| Small search | Whole burst | 88.216 (77.483–135.701) | 59.415 (50.400–81.491) | 32.65% | Yes |
| Small search | Read + decode | 81.496 (70.958–127.418) | 53.030 (44.905–74.096) | 34.93% | Yes |
| Small detail | Whole burst | 80.847 (66.317–106.799) | 48.024 (39.055–93.400) | 40.60% | Yes |
| Small detail | Read + decode | 74.958 (61.054–99.813) | 43.397 (34.846–86.813) | 42.10% | Yes |
| Large search | Whole burst | 273.396 (264.896–300.157) | 203.524 (196.725–212.106) | 25.56% | No |
| Large search | Read + decode | 259.032 (250.786–285.050) | 188.809 (182.871–196.775) | 27.11% | No |
| Large detail | Whole burst | 132.272 (116.067–150.265) | 105.091 (85.720–120.316) | 20.55% | Yes |
| Large detail | Read + decode | 123.835 (108.202–140.575) | 97.070 (78.590–111.810) | 21.61% | Yes |

**Acceptance evidence:** the large search workload has disjoint ranges for both the whole burst (273.396 → 203.524 ms, 25.56% reduction) and the read + decode sum (259.032 → 188.809 ms, 27.11%). The slowest AFTER large search is faster than the fastest BEFORE large search. The other three workload ranges overlap; their median reductions are descriptive only, per the group protocol. The result is specific to this device and controlled dataset; no universal latency, CPU, energy or statistical significance claim is made. Sequential variant order and unrecorded thermal state remain limitations.

All 40 binary controlled traces were independently decoded from ftrace markers and matched against their CSVs (names, timestamps, durations rounded to four decimal places). Within each of 80 bursts, chronological cache sections match 100 repetitions of read/read/decode (before) or read/decode (after). There are 4,000 measured operations per variant: 8,000 read spans before vs 4,000 after, with 4,000 decodes in each. The representative pair additionally contains 200 operations across 40 traces. The representative CSVs were rechecked against their consolidated CSVs, not independently re-decoded from binary in this audit.

### Controlled run-level measurements

Whole-burst elapsed time (ms), 100 operations per cell; run numbers are identifiers, not paired samples. Full read+decode sums and spread values are available in the per-size summaries and audit JSON.

| Run | Small search B | Small search A | Small detail B | Small detail A | Large search B | Large search A | Large detail B | Large detail A |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 01 | 135.7013 | 81.4907 | 104.0714 | 93.3997 | 264.8962 | 203.5897 | 116.0675 | 85.7203 |
| 02 | 84.4904 | 60.3121 | 82.7594 | 45.4536 | 266.7728 | 212.1055 | 125.0957 | 114.4537 |
| 03 | 133.0450 | 58.2365 | 67.3438 | 47.5661 | 267.6514 | 196.7249 | 135.9086 | 104.6697 |
| 04 | 86.7545 | 66.5176 | 106.7985 | 46.5705 | 274.0740 | 205.0945 | 116.8487 | 103.4764 |
| 05 | 89.6781 | 76.3933 | 67.5545 | 79.4458 | 272.7183 | 208.5945 | 139.6195 | 120.3155 |
| 06 | 91.8282 | 60.0268 | 92.1658 | 59.7149 | 297.9140 | 203.3380 | 150.2652 | 97.2791 |
| 07 | 83.0220 | 54.3052 | 78.9345 | 39.0548 | 300.1569 | 203.4590 | 133.4293 | 105.5113 |
| 08 | 77.4827 | 55.3427 | 66.6972 | 62.9568 | 297.4407 | 197.6403 | 149.5008 | 107.1441 |
| 09 | 85.2198 | 50.3998 | 66.3172 | 47.2939 | 287.3732 | 208.7798 | 131.1155 | 106.5014 |
| 10 | 111.6935 | 58.8023 | 103.0125 | 48.4816 | 269.7556 | 199.2333 | 126.2810 | 98.9552 |

## Correctness and regression evidence

On 2026-11-26 the optimized profile build completed the on-device `check` mode: 16 PASS assertions and `DONE mode=check PASS`. The harness uses a fake API with offline IOException or successful synthetic DTOs, and real Room; it does not send HTTP requests. Results from the provided device log:

| Scenario | Evidence and outcome |
|---|---|
| R01 Fresh detail | One read per lookup in representative and controlled AFTER traces. |
| R02 Fresh search | One read per lookup in representative and controlled AFTER traces. |
| R03 TTL / stale offline | Exact 5-minute search and 30-minute detail boundaries are fresh; +1 ms invokes offline fallback; original timestamp retained. Four controlled assertions pass. Manual stale search screenshots supplement this. |
| R04 Explicit refresh | Both search and detail force-refresh assertions pass, checking API call counts and updated timestamp; manual screenshots alone only establish online recovery. |
| R05 Exact context | Changed budget/context has no cached result offline; controlled assertion passes and manual screenshots supplement it. |
| R06 Empty vs missing | Empty cached search remains a successful empty result; missing context fails offline. Manual online/offline empty-result screenshots supplement this. |
| R07 Corrupt JSON | Both search and detail handle corrupt rows offline without success/crash and recover through the simulated online API. Four assertions pass. |
| R08 Account/session isolation | Both search and detail deny reads across accounts and old sessions. Four assertions pass. Manual account-switch screenshots supplement the controlled checks but do not show every filter. |
| R09 Fixed sizes | Both sizes measured in both variants; hashes match and large search ranges are disjoint. |

Controlled check log: `regression/R07-controlled-check.log`. Manual evidence: `regression/R03-*.jpeg`, `R04-*.jpeg`, `R05-*.jpeg`, `R06-*.jpeg`, `R08-*.jpeg`. The controlled test does not simulate every possible concurrent account-switch race or verify backend availability; it tests repository semantics in isolation. Existing debug unit-test and profile assembly tasks passed per Gradle output.

## Reproduction and evidence organization

Paths relative to `android/`:

- Harness: `app/src/profile/java/com/campusmeal/android/profiling/RestaurantCacheCheckActivity.kt`, registered in `app/src/profile/AndroidManifest.xml` only. No new dependency or production activity.
- Benchmark scripts: `scripts/profiling/s4-08/measure.ps1` and `scripts/profiling/s4-08/summarize.mjs` (Windows PowerShell + Node), with the unchanged shared `campusmeal.pbtxt` and `sections.js`.
- Controlled data: `docs/evidence/sprint4/s4-08-room-cache/controlled/{before,after}/{small,large}/run-01` through `run-10` `.csv`, `.log`, original `.perfetto-trace` and `summary.txt`; one `build.txt` per variant. `controlled/audit.json` records rechecked run values and signatures.
- Representative paths are listed above. Keep original binary captures and backups locally, outside the committed evidence if following the common protocol's local-trace rule; CSVs are the raw extracted measurements. Review access to binaries should follow the team's agreed privacy procedure; system traces can include unrelated device metadata. Do not commit duplicate ZIPs or IDE files.

From `android/`, assemble the selected profile build, then run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/profiling/s4-08/measure.ps1 -Variant after -Runs 10` (or `before` with the baseline repository file and rebuilt APK; the script is pinned to the source commit/blobs above). Use a clean evidence directory or `-Start` to resume: existing traces are not overwritten. Restore the optimized file and rebuild after baseline measurement. To reproduce the correctness checks, launch the profile activity with `--es cm.cache.mode check` and collect the `CampusMealCacheCheck` logcat tag.

## Acceptance summary

- One row fetch per successful fresh lookup: source diff and measured spans support this.
- Reproducible timing benefit above observed variability: established for controlled large search, not for the other overlapping workloads.
- Exact-context/cache/session semantics: manual and controlled checks passed within the stated scope.
- Fixed small and large payloads: measured and signatures matched.
- Final documentation and raw CSV/log evidence prepared; reviewer verification and PR/issue closure remain pending.

Do not close #45 until the reviewer has checked the evidence and the PR is accepted.
