# Sprint 4 / S4-14: integrated release 0.5.0

Issue: [S4-14 / #51](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/51).
Owner: Daniel (@dvargasl2). Reviewer: Juan Pablo (@bedoya0611). Release build and signing: Natalia (@NataliaEspitia),
who holds the key of the distributed 0.4.0.

**Status: draft.** Every value marked *Pending* is filled only after it actually happens. Nothing below claims a
build, an installation or a distribution that has not been recorded.

## 1. Version

| | Distributed before | This release |
|---|---|---|
| `versionCode` / `versionName` | 4 / `0.4.0` (commit [`a3fb056`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/commit/a3fb056), 2026-10-31, built and distributed by Natalia) | **5 / `0.5.0`** |
| Room schema | 9 | **12** |
| Android source commit | `a3fb056` | *Pending: the `main` commit Natalia builds* |
| APK SHA-256 | *Pending: from the 0.4.0 APK in Firebase, if available* | *Pending* |
| Signing certificate SHA-256 | *Pending* | *Pending: must equal the 0.4.0 one* |

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
a still-fresh restaurant search (PR #68, if merged before the build).

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
| Install over 0.4.0 | Accepted (same signature), no data reset | | | Pending |
| Session | Still signed in, or a normal re-login; no crash | | | Pending |
| Draft and pending events | Kept; pending events delivered once after reconnecting | | | Pending |
| Room schema | 12 | | | Pending |

### 4.2 Features and inherited flows (each member, same APK)

| Flow | Role | Owner validating | Device | Observed | Status |
|---|---|---|---|---|---|
| Login, refresh, logout, account switch | USER | | | | Pending |
| Inventory and recipes (BQ3) | USER | | | | Pending |
| Restaurant search filters and detail | USER | | | | Pending |
| Meal comparison and pending selection offline | USER | | | | Pending |
| BQ6 and BQ9 reports | ANALYST | | | | Pending |
| Reviews offline → delivered once; BQ13 | USER / ANALYST | Juan Pablo | | | Pending |
| Alternatives; BQ10 | USER / ANALYST | Natalia | | | Pending |
| Synchronization center; BQ1 | USER / ANALYST | Daniel | | | Pending |
| Role check: analytics as USER | USER | | | 403 / no entry | Pending |
| Network lost during a new flow | USER | | | | Pending |

Daniel has no Android phone: his rows are run on a team member's phone or on the emulator, stating which and who ran them.

### 4.3 Integrated performance smoke check

Short re-run of the three profile-only correctness harnesses on the final `main` commit (no new timing claims; the
paired measurements stay in each optimization report): `OutboxBurstActivity` (`check`),
`RestaurantCacheCheckActivity` (`check`), `Bq10CheckActivity`.

| Harness | Commit / device | Result | Status |
|---|---|---|---|
| S4-11 outbox `check` | | | Pending |
| S4-08 restaurant cache `check` | | | Pending |
| S4-07 BQ10 check | | | Pending |

## 5. Backend

| Item | Value |
|---|---|
| CampusMealBack `main` | [`1910b13`](https://github.com/ConstruccionAplicacionesMovilesGrupo24/CampusMealBack/commit/1910b13) (BQ10 code in `cd1947e`; later commits are documentation) |
| Local checks of `1910b13` (2026-11-27) | `npm run build`, `tsc --noEmit` and `eslint src` pass; 12 migrations compiled |
| Neon | All 12 migrations applied (`docs/SPRINT4_BQ10.md` in CampusMealBack, run by Natalia on 2026-11-25) |
| Render | `GET /health` → `status ok`, `database up` (2026-11-28T02:41Z). *Pending: deployed commit, confirmed in the Render dashboard (`cd1947e` or later)* |

All backend changes of Sprint 4 are additive (new tables and endpoints); the Swift client does not use them and keeps
working against the same API.

## 6. Distribution

Firebase App Distribution, as in `SPRINT3_RELEASE.md` §Distribution, to the official recipients given by the course.

| Item | Value |
|---|---|
| Uploaded by / date | *Pending* |
| Release notes | §2.2 |
| Recipients | *Pending (official addresses only)* |
| Installation confirmed by | *Pending: only after it actually happens* |

## 7. Known limitations

- Daniel validates on a borrowed phone or the emulator (no Android phone of his own).
- Crash capture is JVM-only: native crashes and ANRs are not counted by BQ1 (`SPRINT4_BQ1.md`).
- Restaurant reviews are self-reported; BQ13 scores are hidden below five reviewers.
