# Sprint 4 — Daniel (S4-10): synchronization center, durable telemetry and BQ1

Tracking issue: [S4-10 / #49](https://github.com/ConstruccionAplicacionesMovilesGrupo24/Sprint-2-Kotlin/issues/49).
Owner: Daniel (@dvargasl2). Reviewer: Juan Pablo (@bedoya0611).
Backend contract and formulas: `docs/SPRINT4_BQ1.md` in CampusMealBack (S4-09). Scope: `SPRINT4_SCOPE.md` §5.3, §6–§9.
Paths below are relative to `app/src/main/java/com/campusmeal/android/`.

## 1. What is new

| View | Who | Where | Reached from |
|---|---|---|---|
| Synchronization center | Every account | `feature/sync/SynchronizationCenterScreen.kt`, `…ViewModel.kt` | Profile → Synchronization |
| App performance (BQ1) | `ANALYST` | `feature/analytics/presentation/PerformanceHealthScreen.kt`, `…ViewModel.kt` | Profile → Analytics → BQ1 |

Inherited, not counted as new: the outbox and its worker, the pending-selection labels and the BQ6 report.

## 2. Synchronization center

- **Account-scoped and bounded.** `OutboxDao.observeOpen` (100) and `observeSent` (20) are SQL projections without `payloadJson`; the counts are a separate `GROUP BY`.
- **Retry.** `OutboxRepository.retry` queues a failed event again with its **original id** (`requeueOwned`, only the account's own row, only while the scope is current) and schedules the existing unique chain. A second tap changes no row. Not offered for reviews (resolved on their screen), HTTP 409, unreadable payloads or expired events: they would end the same way.
- **Inventory is never sent from here.** Drafts and uncertain writes are listed with "Review in Inventory". Those writes, and comparison POSTs, are not idempotent, so they are never replayed automatically.
- **Reasons.** Only a status code, an API error code or a text of this app. Never a server message.
- **Retention (G-6).** `OutboxRepository.applyRetention`, on every drain and when the center opens: pending events become `EXPIRED` at 80 days (reviews at 30), failed and expired rows stay 30 days after closing, sent rows 1 day after delivery. Nothing pending disappears silently.

## 3. Telemetry

| Record | When exactly | Code |
|---|---|---|
| `SESSION_START` | Once per identified session (account + generation) and process, when a screen is visible. A process that WorkManager starts only to deliver events is **not** a session: counting it would lower the crash rate | `core/telemetry/PerformanceTelemetry.kt` |
| `FATAL_CRASH` | Imported at the next visible start of the **same** account | `core/telemetry/FatalCrashHandler.kt`, `CrashMarkerStore.kt` |
| `COMPARISON_LOAD` | Once per explicit load | `feature/decision/presentation/ComparisonLoadTimer.kt` |

All go through the new `PERFORMANCE` outbox kind: one drain per account, versioned envelope `{"v":1,"body":…}`, sent only to `analytics/performance-events`.

### 3.1 Comparison load

- Monotonic clock (`SystemClock.elapsedRealtime`), from the request to the frame after the one that drew the content (`withFrameNanos` in `DecisionScreen`).
- The result decides the outcome, the drawing ends it. Closed loads ignore whatever arrives later, so there is one event per load and none per recomposition.

| Outcome | Case |
|---|---|
| `FRESH_NETWORK` | The backend answered and it was drawn (also "no alternatives available": the user waited for that answer) |
| `CACHED_RESTORE` | Recent decision restored without a request |
| `OFFLINE_HISTORY` | Backend unreachable and a previous comparison was drawn |
| `TIMEOUT` / `ERROR` | Ended without content; `TIMEOUT` when OkHttp reports one |
| `CANCELLED` | Replaced by another request, screen left or app sent to the background before drawing |

A timeout of an account that has history is drawn as history and counts as `OFFLINE_HISTORY`. Coming back to a screen that is still alive is not a load.

### 3.2 Crash capture

- Inside the crashing thread: one small file in `noBackupFilesDir/crash-markers`, forced to disk and renamed. No Room, DataStore, coroutines, JSON or network. The previous handler always runs, so the process ends as before.
- Content: account, session, ids created with the session, device, coarse screen and exception **class**. No message, stack trace, token or location.
- Import: start and crash are admitted with their original ids (so a crash that happened before the start reached Room still has its session) and the file is deleted only after both are durable. Markers of another account stay untouched.
- At most 5 markers; further crashes of a crash loop are not recorded.
- Injection: a button of the synchronization center guarded by `BuildConfig.DEBUG`. The crash is marked `injected` and the backend leaves its whole session out of real usage.

**Coverage gaps.** Native crashes, ANRs, force-stops and system kills are not captured; a device that never reopens the app never delivers; crashes before an account is identified belong to nobody and are not recorded; a full outbox (1,000) rejects telemetry.

## 4. Local storage, cache, threading and connectivity

| Strategy | Implementation |
|---|---|
| Room v11 (`MIGRATION_10_11`, additive) | `outbox_events.lastAttemptAtEpochMillis`, `.closedAtEpochMillis`; table `performance_reports` |
| Local files | Crash markers (§3.2) |
| DataStore | Last BQ1 filters per account (`performanceHealth.lastRange.*`) |
| Cache | BQ1 report by account + range + demo choice + contract version; TTL 15 min, 20 per account, deleted on 403, logout and account change |
| Threading | Marker files read on `Dispatchers.IO`; telemetry admitted on the application scope (`Dispatchers.Default`), never blocking the screen; report job cancelled on filter change |
| Eventual connectivity | Everything is admitted to Room first; both views have explicit offline, empty and error states |

## 5. Evidence

Run on 2026-11-25 on an **emulator** (API 37, debug build) against a **disposable local backend** (CampusMealBack `main` with `seed:bq1`). Screenshots in `docs/evidence/sprint4/s4-10-emulator/`. **The acceptance asks for a physical device: §5.1 is still pending.**

| Case | Observed |
|---|---|
| Upgrade v9 (0.4.0) → v11, offline, with a draft, 2 pending and 4 sent events | Schema 11; draft and every event kept; same ids delivered later |
| Injected crash offline → reopen → reconnect | 1 marker; imported and deleted; server: 1 crash, its session present, `injected` |
| Force-stop | No marker, no crash |
| Lost acknowledgement (35 sent rows set back to pending) | All sent again; server counts unchanged except the new session |
| Double tap on Retry | One pending row, sent once, no duplicate on the server |
| Crash of A, then login as B, then A again | Marker and pending event untouched while B is active; 0 crashes attributed to B; imported and sent as A |
| Retention fixtures | 81-day event and 31-day review → `EXPIRED`; failure closed 31 days ago → purged; others kept |
| Loads | Enter (restored) `CACHED_RESTORE`; recalculate `FRESH_NETWORK` 572 ms; offline `OFFLINE_HISTORY`; leave / background during the load `CANCELLED`; frozen backend `TIMEOUT` 90,028 ms; tab return and rotation: no event |
| BQ1 view | Matches the S4-09 fixtures (1.00 % and 2000 ms not flagged, 2.00 % and 2500 ms flagged, small samples labelled, empty = "No data") |
| BQ1 offline / other range / 403 | Saved copy with its date / explicit "no saved report" / message and 0 saved copies |
| USER account | No BQ1 entry (the backend also answers 403) |

`assembleDebug`, `assembleProfile` and `lintDebug` succeed.

### 5.1 Physical device (pending)

| Case | Device / Android / APK SHA | Observed | Status |
|---|---|---|---|
| Upgrade from the distributed 0.4.0 keeping draft and pending events | | | Pending |
| Crash → reopen → delivered once (Render) | | | Pending |
| Account switch with a pending crash | | | Pending |
| Lost acknowledgement / double retry | | | Pending |
| BQ1 fixtures on Render | | | Pending |

## 6. Differences from the frozen scope

Also written in `SPRINT4_SCOPE.md` §7 and §8:

- Two nullable outbox columns: the issue asks for the last attempt, and "30 days after failing" needs the failing date.
- Expiry uses `occurredAt`, the date the backend checks, instead of `createdAt` (a crash is imported later than it happened).
- Up to 5 crash markers instead of 1.
