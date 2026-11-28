# Sprint 4 / S4-14: integrated release 0.5.0

Issue: [S4-14 / #51](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/51).
Owner: Daniel (@dvargasl2). Reviewer: Juan Pablo (@bedoya0611). Release build and signing: Natalia (@NataliaEspitia),
who holds the key of the distributed 0.4.0.

**Status: released and distributed (2026-11-27).** Still open: Natalia's BQ10 validation row (§4.2) and restoring the
opening-hours test fixture in Neon (§4.2). Every value marked *Pending* is filled only after it actually happens. Nothing below claims a
build, an installation or a distribution that has not been recorded.

## 1. Version

| | Distributed before | This release |
|---|---|---|
| `versionCode` / `versionName` | 4 / `0.4.0` (commit [`a3fb056`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/a3fb056), 2026-10-31, built and distributed by Natalia) | **5 / `0.5.0`** |
| Room schema | 9 | **12** |
| Android source commit | `a3fb056` | [`a816687`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/a816687) (`main` after #68 and #70), as reported by Natalia; consistent with the APK, which contains `ResultSource.FRESH_CACHE` (#68) |
| APK SHA-256 | `d23869cc8f65e7b272d1479f588dfc62a4032d462f514cd59773da6190f28a04`. Copy of the installed distributed 0.4.0, pulled from the SM-S948U1 on 2026-11-27 | `0337fb3b1b5b5213506c860069be884b099182d3ef89c10ec8b1515f243243d0` (Natalia's signed APK) |
| Signing certificate SHA-256 | `1e1a1c28e65a44b2cda258571034c83439fc388e6e78608aeb5d8474526d12ed` (`CN=Natalia Espitia, OU=campusmeal`, v2 scheme) | `1e1a1c28e65a44b2cda258571034c83439fc388e6e78608aeb5d8474526d12ed`: **identical** (`apksigner verify --print-certs`, build-tools 36.0.0) |

`minSdk` 24, `targetSdk` 36, release minification off, `applicationId` `com.campusmeal.android`. The release URL is
`https://campusmeal-api.onrender.com/api/v1/` (`campusmeal.releaseApiBaseUrl`; the build refuses a non-HTTPS URL).

**Gap G-5 (`SPRINT4_SCOPE.md`).** `SPRINT3_RELEASE.md` documents 0.3.0 and 0.3.1 (code 3), built by Daniel with his
key. Gradle then moved to 0.4.0 / code 4 in `a3fb056`, and that build was compiled and distributed by Natalia.
Android only installs an update signed with the same certificate as the installed app, so 0.5.0 is signed by Natalia
with the 0.4.0 key; §3 records the two certificate fingerprints so this is verified, not assumed.

## 2. What changes for an installed 0.4.0

### 2.1 Room migrations (additive, no destructive fallback)

| Migration | Change | Owner |
|---|---|---|
| 9 → 10 | New tables `review_drafts`, `review_submissions`, `review_summary_cache`, `partner_health_reports` | Juan Pablo (reviews and BQ13, S4-04) |
| 10 → 11 | `outbox_events` gains `lastAttemptAtEpochMillis` and `closedAtEpochMillis` (existing `FAILED` rows get a close time); new `performance_reports` | Daniel (S4-10) |
| 11 → 12 | New table `bq10_snapshots` | Natalia (S4-07) |

No existing table is dropped or rebuilt, so drafts, pending outbox rows and cached data of 0.4.0 are kept. Exported
schemas `app/schemas/…/9.json` to `12.json` are in the repository. Upgrades already verified on the emulator:
v9 → v11 (`SPRINT4_DANIEL.md` §5) and the exported v11 schema → v12 (`SPRINT4_NATALIA.md`). The in-place upgrade of the
**distributed** 0.4.0 on a phone is §4.1.

### 2.2 Release notes 0.5.0

New destinations (two per member, `SPRINT4_DELIVERY.md` §4):

- Structured restaurant reviews that survive offline and process death, and the BQ13 partner-health report (Juan Pablo).
- Context-compatible meal alternatives and the BQ10 supply-gap report (Natalia).
- Synchronization center and the BQ1 performance-health report, backed by durable crash and comparison-load telemetry (Daniel).

Micro-optimizations (measured before/after, `SPRINT4_MICROOPT_*.md`): BQ3 ingredient lookup by index (Juan Pablo),
one Room read per fresh restaurant cache lookup (Natalia), a per-thread UTC formatter in event admission (Daniel).

Fixes: duplicate list key in the synchronization center (`SPRINT4_DANIEL.md` §5.2); false "You're offline" banner on
a still-fresh restaurant search (PR #68, merged as `a816687`; present in the 0.5.0 APK).

## 3. Build and signing (Natalia)

From an up-to-date `main`, with her own `android/local.properties` (release key settings and
`campusmeal.releaseApiBaseUrl`); the keystore and its passwords never enter the repository:

```bash
git switch main && git pull
cd android
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
shasum -a 256 app/build/outputs/apk/release/app-release.apk          # Windows: certutil -hashfile <apk> SHA256
<sdk>/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Record here: the source commit (`git rev-parse HEAD`), the APK SHA-256 and the certificate SHA-256 printed by
`apksigner`. Run `apksigner verify --print-certs` on the 0.4.0 APK too (downloaded from Firebase): both certificate
digests must be identical, otherwise the update cannot be installed over 0.4.0 and must not be distributed.

## 4. Validation on the same signed APK

### 4.1 In-place upgrade from the distributed 0.4.0

On a phone that has the release-signed 0.4.0 (Juan Pablo's SM-S948U1): with 0.4.0, leave an inventory draft and
pending events offline; then install 0.5.0 over it (Firebase invitation or `adb install -r`), without uninstalling.

| Check | Expected | Device / time | Observed | Status |
|---|---|---|---|---|
| Install over 0.4.0 | Accepted (same signature), no data reset | SM-S948U1 / Android 17, 2026-11-27 23:04:58 | `adb install -r` → `Success`. `versionCode` 4 → 5. `firstInstallTime` stays 23:01:32, so this is an update, not a new install | Done |
| Session | Still signed in, or a normal re-login; no crash | 23:05, offline | Opens still signed in (test student account) with the 0.4.0 context (Uniandes, 60 min, $40,000). Empty `logcat -b crash` | Done |
| Draft and pending events | Kept; pending events delivered once after reconnecting | 23:05–23:08 | Offline, the sync center lists the 0.4.0 draft ("New item: Upgrade draft tomatoes", 23:04, never submitted) and **4 waiting**. Two App usage events from 23:04 were created offline by 0.4.0; one App usage and one App performance event were created by 0.5.0 at launch. The 3 events 0.4.0 had already sent stay as Sent. On reconnecting: "0 waiting". BQ6 `eventsReceived` 645 → **648** (+3 usage events), still 648 after 60 s. The draft, submitted online, gives one inventory item | Done |
| Room schema | 12 | 23:05–23:08 | Not readable directly (release build, no `run-as`). Room validates the v12 schema identity when it opens the database and fails on a missing migration. The app opens it, and the screens backed by the v11 columns (sync center) work. The 3 sent and 2 pending outbox rows written under v9 remain, plus the inventory draft | Done (indirect) |

The original 0.4.0, installed 2026-11-19, was removed by mistake before this session: the 0.5.0 was first installed from
scratch at 22:55, `firstInstallTime` = `lastUpdateTime`. The test therefore reinstalled the **same distributed 0.4.0
binary** (`d23869cc…`, pulled from the phone on 2026-11-26) at 23:01. Its data was created tonight: sign-in, cached
recipes and 3 events sent online at 23:02–23:03; then, offline, the draft and 2 pending events. Run and recorded by
Juan Pablo; screenshots and the step log are in [`evidence/sprint4/s4-14-release/`](evidence/sprint4/s4-14-release/).
BQ6 before the run: 642.

### 4.2 Features and inherited flows (each member, same APK)

| Flow | Role | Owner validating | Device | Observed | Status |
|---|---|---|---|---|---|
| Login, refresh, logout, account switch | USER | Juan Pablo | SM-S948U1, 23:01–23:25 | The student signed in at 23:02 under 0.4.0 and was still making online requests at 23:21 without logging in again. The access token lasts 15 min, so it was refreshed, across the upgrade. Then student → logout → analyst → logout → student: each profile shows only its own role | Done |
| Inventory and recipes (BQ3) | USER | Juan Pablo | SM-S948U1, 23:18 | Inventory refresh: 1 item (the migrated draft). Recipes evaluate the 6 catalog recipes and explain the misses (ingredient, diet, time) | Done |
| Restaurant search filters and detail | USER | Juan Pablo | SM-S948U1, 23:13 | Uniandes, 60 min, COP 40,000, vegetarian: 3 restaurants, each "Fits the available time, budget and dietary preferences", no false offline banner (#68). The Garden detail: walk 2 min, total 19 min, review summary | Done |
| Meal comparison and pending selection offline | USER | Juan Pablo | SM-S948U1, 23:16–23:19 | Online comparison: #1 WALK, The Garden, 19 min, COP 14,500. "Choose WALK" offline → sync center "Recommendation seen or chosen · Pending". Reconnect → sent, "0 waiting". Home shows "Your plan: Walk to The Garden" | Done |
| BQ6 and BQ9 reports | ANALYST | Juan Pablo | SM-S948U1, 23:22–23:23 | Both load and are labeled "Includes synthetic demo data" | Done |
| Reviews offline → delivered once; BQ13 | USER / ANALYST | Juan Pablo | SM-S948U1, 23:14–23:23 | The Garden review submitted offline → "Review saved on this device". Force-stop and relaunch offline → still pending in the sync center. Reconnect → server summary 6 → **7 reviews from 7 students**, still 7 after 45 s. BQ13 (Nov 21–27) loads: Green Bowl 5 reviews, mean 4.00, the other four suppressed under 5 reviewers. Same values from the API | Done |
| Alternatives; BQ10 | USER / ANALYST | Natalia | | | Pending |
| Synchronization center; BQ1 | USER / ANALYST | Daniel | API 37 emulator (Daniel has no Android phone), the distributed APK `0337fb3b…` installed with `adb install`, Render, 2026-11-27 23:46–23:53 | **USER:** after sign-in the center shows 0 waiting and the session events sent with 1 attempt. Online comparison 23:49 (#1 WALK, The Garden, 19 min, COP 14,500); airplane mode, "Choose WALK" → center "2 waiting": App usage and Recommendation seen or chosen, *Pending · Not attempted yet*, with the offline notice. Reconnected 23:50:40 → "0 waiting" within ~3 s, both *Sent · 1 attempt(s)*, still dated 23:49. **ANALYST:** BQ1 (2026-10-29…11-27, real data) lists this emulator as `Google sdk_gphone64_arm64 · Android 17 · app 0.5.0`: 0 of 2 sessions crashed, 1 network load 715 ms (the comparison above), so 0.5.0 telemetry reaches the report end to end. *Include demo data*: Demo A 1.00 % not flagged / 2500 ms above limit; Demo B 2.00 % above limit / 2000 ms not flagged; Demo C no data; Demo D 33.33 % and 5000 ms flagged with small-sample labels; Demo E 0.00 % / 1000 ms (S4-09 fixtures). Offline: "Saved Nov 27, 23:52". Screenshots `40`–`47-daniel-*` | Done |
| Role check: analytics as USER | USER | Juan Pablo | SM-S948U1 and API, 23:21 | The student profile has no Analytics section. `GET` feature-adoption, performance and partner-health with the student token → **403** each | Done |
| Network lost during a new flow | USER | Juan Pablo | SM-S948U1, 23:20–23:21 | Alternative meals: SUSHI online (`FOUND`, 23:20:32), then airplane mode. BOWLS → "Offline. No matching saved result". SUSHI → "Offline. Showing historical data · Historical snapshot — not current availability". Reconnect + Refresh → new server response 23:21:24. No crash | Done |

**Test fixture for §4.2 (Neon, 2026-11-27).** The release had to be validated the same night, and every restaurant
closes by 22:00. With the owner's approval, a temporary opening period (Friday 22:00 → 04:00) was **added** to the five
active restaurants; their normal periods were not changed. The previous `opening_hours` values are saved in
[`restaurants-opening-hours-before-2026-11-27.json`](evidence/sprint4/s4-14-release/restaurants-opening-hours-before-2026-11-27.json)
and are restored exactly after the session (*pending: restore time*). Availability-dependent results between 23:12 and
the restore reflect this fixture, not real opening hours. No menu, price or meal was changed.

Daniel has no Android phone: his rows are run on a team member's phone or on the emulator, stating which and who ran them.

### 4.3 Integrated performance smoke check

Short re-run of the three profile-only correctness harnesses on the final `main` commit (no new timing claims; the
paired measurements stay in each optimization report): `OutboxBurstActivity` (`check`),
`RestaurantCacheCheckActivity` (`check`), `Bq10CheckActivity`.

| Harness | Commit / device | Result | Status |
|---|---|---|---|
| S4-11 outbox `check` | `a816687`, API 37 emulator | `MATCH`: 500 events by 1 and by 8 concurrent callers store the same payloads (`46bee4e0…`, as in the S4-11 pair) | Done |
| S4-08 restaurant cache `check` | `a816687`, API 37 emulator | `DONE mode=check PASS` (16 assertions, including fresh-cache `FRESH_CACHE` vs offline `CACHE`) | Done |
| S4-07 BQ10 check | `a816687`, API 37 emulator | `DONE PASS assertions=42` | Done |

Run by Daniel on 2026-11-28 with the `profile` build of `a816687` (`versionCode 5`, `0.5.0-profile`), the commit the
0.5.0 APK contains (§1); `66b1953` only adds documentation and evidence. The same commit also passes
`testDebugUnitTest` (8 tests), `lintDebug` (warnings only) and `assembleDebug`. These harnesses use disposable data and
no backend: they show the integrated build keeps the optimized paths correct, not new timings.

## 5. Backend

| Item | Value |
|---|---|
| CampusMealBack `main` | [`1910b13`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/commit/1910b13) (BQ10 code in `cd1947e`; later commits are documentation) |
| Local checks of `1910b13` (2026-11-27) | `npm run build`, `tsc --noEmit` and `eslint src` pass; 12 migrations compiled |
| Neon | All 12 migrations applied (`docs/SPRINT4_BQ10.md` in CampusMealBack, run by Natalia on 2026-11-25) |
| Render | `GET /health` → `status ok`, `database up` (2026-11-28T02:41Z). The running service answers the BQ10 alternatives and BQ1/BQ13 endpoints used in §4.2, so it contains `cd1947e`; the later backend commits (`b0ca4d3`, `1910b13`) only change documentation, so the running code is that of `1910b13`. The exact deployed commit was not read from the Render dashboard |

All backend changes of Sprint 4 are additive (new tables and endpoints); the Swift client does not use them and keeps
working against the same API.

## 6. Distribution

Firebase App Distribution, as in `SPRINT3_RELEASE.md` §Distribution, to the official recipients given by the course.

| Item | Value |
|---|---|
| Uploaded by / date | Daniel, 2026-11-27 22:55:05 (UTC-5), release `0.5.0 (5)` in the CampusMeal Firebase project; the APK is Natalia's signed build `0337fb3b…` (§1), verified by Daniel before upload (version 5 / 0.5.0, Render URL, certificate `1e1a1c28…`) |
| Release notes | "CampusMeal 0.5.0 (Sprint 4)", the text of §2.2 |
| Recipients | 6 invited: the three team members and three further recipients (addresses kept in the Firebase console, not in the repository) |
| Installation confirmed by | Natalia and Juan Pablo installed 0.5.0 from the Firebase invitation and reported it working (2026-11-28). Earlier, the console showed 6 invited, 1 accepted, 0 downloaded; Juan Pablo's §4.1 run had installed the same APK with `adb install -r` |

## 7. Known limitations

- Daniel validates on a borrowed phone or the emulator (no Android phone of his own).
- Crash capture is JVM-only: native crashes and ANRs are not counted by BQ1 (`SPRINT4_BQ1.md`).
- Restaurant reviews are self-reported; BQ13 scores are hidden below five reviewers.
