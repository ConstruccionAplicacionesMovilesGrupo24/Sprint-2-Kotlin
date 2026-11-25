# Sprint 4 — Profiling protocol and common baseline (S4-02)

Tracking issue: [S4-02 / #47](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/47).
Owner: Daniel (@dvargasl2). Reviewer: Juan Pablo (@bedoya0611).

How the three micro-optimizations are measured, so every "before" is comparable with its "after". It is separate from the
BQ1 product telemetry (S4-09/S4-10): these traces never leave the developer's machine.

| Pair | Owner | Optimization | Sections |
|---|---|---|---|
| S4-05 | Juan Pablo | BQ3 ingredient lookup | `cm.bq3.evaluate` |
| S4-08 | Natalia | One Room snapshot per cached read | `cm.restaurant.cache.read`, `cm.restaurant.cache.decode` |
| S4-11 | Daniel | UTC timestamp formatting | `cm.outbox.isoUtc`, `cm.outbox.enqueue` |

## 1. The measured build

`profile` build type in `app/build.gradle.kts`: `initWith(release)`, `isProfileable = true`, not debuggable, same minification
as release (**off**), debug signing key, application id `com.campusmeal.android.profile`. It installs next to the distributed
app, so its data is disposable and the real one is never touched.

```bash
./gradlew :app:assembleProfile          # needs campusmeal.releaseApiBaseUrl (https://…/) in local.properties
adb install -r app/build/outputs/apk/profile/app-profile.apk
```

Do not enable R8 for Sprint 4: if only the "after" were minified, the difference would be the compiler's.

### 1.1 Verified properties (2026-11-25, macOS, JDK 17, AGP 9.3.2)

| Check | Command | Observed |
|---|---|---|
| Profileable | `aapt2 dump xmltree app-profile.apk --file AndroidManifest.xml` | `profileable`, `enabled=true`, `shell=true` |
| Not debuggable | same, and `adb shell dumpsys package … \| grep flags` | No `debuggable` attribute; flags without `DEBUGGABLE` |
| Release unchanged | merged release manifest | No `profileable` nor `debuggable` |
| Installs and opens | API 37 emulator | Started, no entry in the crash buffer |
| System trace readable | `scripts/profiling/campusmeal.pbtxt`, API 37 emulator | 3.7 MB trace with `Choreographer#doFrame` and Compose slices of the app |

Measurements are taken on a physical phone when the owner has one (§3). An owner without an Android phone may measure a pair
on the emulator: both variants then use the same AVD on the same host, and the report states it as a limitation (timings
include host scheduling and are not representative of a phone in absolute terms; only the paired comparison is used).

## 2. Trace sections

`core/common/TraceSections.kt`, on `android.os.Trace` (no dependency).

- `traceSection` is for code that stays on one thread. Its block is `crossinline`, so the compiler rejects a suspend call
  inside it: a synchronous section must never begin on one thread and end on another.
- `traceAsyncSection` is for code that suspends (Room reads, event admission). Begin and end are matched by a cookie; it needs
  API 29, and on older phones the block just runs unmarked.

Sections carry no user data. Their names and places must not change inside a pair.

What each one includes: `cm.bq3.evaluate`, the evaluation of the whole catalog, without Room. `cm.restaurant.cache.read`, one
suspend DAO call, including the wait for Room's executor. `cm.restaurant.cache.decode`, JSON to DTO only.
`cm.outbox.isoUtc`, one timestamp. `cm.outbox.enqueue`, payload + timestamp + session lock + Room, **without network**: it is
the denominator that keeps an isolated gain in `isoUtc` from being exaggerated.

## 3. Conditions of a pair

1. One device for both variants, API 29 or later: a physical phone (battery above 50 %, charging, battery saver off, not hot)
   or, only when the owner has no Android phone, the same emulator AVD on the same host (on power, no other heavy work).
2. The same commit except for one dedicated optimization commit. The "before" is a functional baseline that already includes
   the new Sprint 4 features; take it **before** the optimization is merged.
3. Same account, same dataset, same date. Write down the number of recipes, inventory rows and cached restaurants.
4. Wake Render up first (`GET /health` until `status: ok`, ~40 s cold). That wait is never part of a measurement.
5. Compilation state: after each install, `adb shell cmd package compile -m speed -f com.campusmeal.android.profile`.
6. Warmup: run the workload 3 times and discard. Then at least **5 measured runs** (10 if the range is wider than 20 % of the
   median), one trace per run. Cold or warm, and empty or filled cache, is fixed for the pair and written down.
7. Airplane mode for the three workloads once the data is local: none of them measures the network.

| Pair | Scripted workload of one run |
|---|---|
| S4-05 | Recipes open; alternate the maximum preparation time between two fixed values 6 times, never repeating a value the 5-result memo still holds |
| S4-08 | With a fresh cached search: open results, open one cached detail, go back; 5 times |
| S4-11 | `scripts/profiling/s4-11-burst.sh`, in a disposable database without account or network (conditions 3, 4 and 7 do not apply). Representative: the admissions of 5 meal comparisons (usage, impression, selection and load telemetry: 20 events). Stress: 500 events. Both after a 50-event warmup in the same process |

### 3.1 Recording

```bash
adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/run-01.perfetto-trace < scripts/profiling/campusmeal.pbtxt
adb pull /data/misc/perfetto-traces/run-01.perfetto-trace
```

It records 30 seconds; start the workload right after launching it. The same two lines work in macOS/Linux shells and in the
Windows Command Prompt (`cmd`); PowerShell does not support `<`, so use `cmd` there. Open the file in
<https://ui.perfetto.dev> and run `scripts/profiling/sections.sql`. Android Studio's System Trace is also valid with the same
build; do not mix both tools inside a pair.

Java/Kotlin **allocations** need a debuggable process: record them as a separate pair with the `debug` build and never compare
its timing with the `profile` numbers.

## 4. What each capture records

| Field | Value |
|---|---|
| Issue / variant (before, after) / Git SHA | |
| Phone, Android version, API level | |
| APK version and SHA-256 | |
| Compilation mode, warmup runs, measured runs | |
| Cold or warm; cache state; airplane mode | |
| Dataset (recipes, inventory rows, cached restaurants, events) | |
| Raw values per run (file) | |
| Median, minimum, maximum | |
| Limitations, discarded runs and why | |

Report the median and the range, not only the best run. If the ranges of "before" and "after" overlap, say that no difference
was demonstrated.

## 5. Baseline status

| Pair | Owner | "Before" capture | Status |
|---|---|---|---|
| S4-05 | Juan Pablo | `df0a40e`, 2026-11-25, SM-S948U1 (API 37): representative 2.043 ms (1.861–2.299, 10 runs), stress 28.412 ms (26.497–29.484, 5 runs). `docs/evidence/sprint4/s4-05-bq3/before/` | Captured |
| S4-08 | Natalia | | Pending |
| S4-11 | Daniel | `dad0a47`, 2026-11-25, API 37 emulator (4 vCPU, 4 GB; no Android phone available): `cm.outbox.isoUtc` representative 0.136 ms (0.092–0.244, 10 runs), stress 0.073 ms (0.016–0.099, 10 runs). `docs/evidence/sprint4/s4-11-iso-utc/before/` | Captured |

## 6. References

- <https://developer.android.com/studio/profile/>
- <https://developer.android.com/topic/performance/tracing/custom-events>

No automated benchmark framework is added for this prototype (S4-02 acceptance).
