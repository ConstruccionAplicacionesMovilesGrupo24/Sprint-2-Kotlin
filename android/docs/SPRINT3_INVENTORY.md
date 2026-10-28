# S3-03 — Complete Inventory and Drafts with Immediate Local Reads

| Field | Value |
|---|---|
| Issue | S3-03 (Android issue #16) |
| Owner | Juan Pablo (`bedoya0611`) |
| Reviewer | Natalia (`NataliaEspitia`). **Review pending.** |
| Depends on | S3-01 (plan), S3-02 (session/account foundation), backend `GET /api/v1/inventory` (already deployed) |
| Status | Implemented locally, **uncommitted**. Debug build and lint pass. Physical-device checks against the deployed API are in §10. |

`<pkg>` stands for `android/app/src/main/java/com/campusmeal/android`. The related bug fixes (Render timeout, restaurant route continuity) are in [`S3_03_BUGFIX_VALIDATION.md`](S3_03_BUGFIX_VALIDATION.md).

## 1. What changed for the user

- **Inventory shows the complete inventory** (`GET inventory`), including expired items, items beyond three days and zero quantities, grouped as *Expired*, *Due today*, *Next 3 days* and *Later*. Before, it showed only the BQ2 list (`inventory/expiring?withinDays=3`). With nothing due soon, it then implied the whole inventory was empty (plan D-10).
- **Local first.** Inventory and Home render Room immediately and refresh in the background when the data is missing, stale, invalidated or from an earlier Bogotá date. A refresh never replaces stored content with a spinner.
- **Honest states.** Inventory has four distinct cases:
  - "Not synced on this device yet": no snapshot was ever stored here.
  - "Your inventory is empty": a synchronized empty answer, with its age.
  - "Saved copy · updated …": the last refresh failed.
  - "… your last change is not shown yet": a write was confirmed, but the list has not been refreshed since.

  Home always says "As of ⟨date, time⟩" for BQ2 and marks a list computed on an earlier day as such. Its empty message is now "No items expire in the next 3 days."
- **Durable drafts.** The add/edit form is saved on the device as typed, including incomplete or invalid text. It survives navigation, offline use and force-stop, and is restored through *Continue*. Nothing is sent until the user taps *Save*/*Submit*.
- **Writes report what actually happened.** "Item added/updated/consumed" appears only after a server success. Offline, the app refuses with "Not saved: your draft is kept". When the outcome is unknown (timeout or interruption after sending), the draft or consume is marked for reconciliation and is never resent automatically.

## 2. Storage (Room v4)

| Table / key | Contents | Identity |
|---|---|---|
| `inventory_items` (new) | Complete snapshot: `name`, `quantity`, `unit`, `expirationDate`, server `remainingDays` as received, `position` (backend order) | `(accountId, itemId)` |
| `cache_metadata` `inventory.items:{accountId}` (new key) | Complete-snapshot sync time | One per account |
| `expiring_inventory_items` + `inventory.expiring:{accountId}` (S3-02, unchanged) | BQ2 snapshot in priority order | `(accountId, itemId)` |
| `cache_metadata.invalidatedAtEpochMillis` (new, nullable) | Set by a confirmed write. A sync clears it, because it upserts the whole row. | — |
| `inventory_drafts` (new) | `draftId` (`create` or `edit:{itemId}`), `targetItemId`, raw `name`/`quantityText`/`unit`/`expirationDateText`, `updatedAtEpochMillis`, `lastSubmitOutcome`, `lastSubmitAtEpochMillis`, `lastHttpCode`, `lastErrorCode` | `(accountId, draftId)`: one add draft and one draft per item |
| `inventory_pending_consumes` (new) | A consume whose result is not confirmed: `itemName`, `outcome`, `startedAtEpochMillis`. A marker, not a queue. | `(accountId, itemId)` |

- The two snapshots have **separate metadata rows**. A successful response from one endpoint never certifies the other, and BQ2 rows never count as a complete snapshot. A missing metadata row means "never synchronized"; a row with zero items is a valid empty.
- A snapshot is replaced (rows and metadata) in **one Room transaction**, only after the whole response is validated. Validation covers required fields, real calendar dates, finite non-negative quantities and duplicate ids. One bad row rejects the response and keeps the previous snapshot. Inactive rows are left out. The BQ2 mapper and filter are **not** applied to the complete response.
- No tokens, passwords or coordinates are stored in these tables.
- Code:
  - `<pkg>/feature/inventory/data/local/` (`InventoryItemEntity.kt`, `InventoryDraftEntity.kt`, `PendingConsumeEntity.kt`, `RoomInventoryStore.kt`)
  - `<pkg>/feature/inventory/data/mapper/InventoryMappers.kt` (`toCompleteInventoryOrNull`)

### 2.1 Migration

`MIGRATION_3_4` in `<pkg>/core/database/DatabaseMigrations.kt` is registered in `DatabaseMigrations.ALL`. It is **purely additive**:
- it creates the three new tables;
- it runs `ALTER TABLE cache_metadata ADD COLUMN invalidatedAtEpochMillis INTEGER`.

No row is changed or deleted, so the S3-02 session, profile and BQ2 cache survive. The exported schema `android/app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/4.json` was generated by the normal build and is left **uncommitted** for the team. `1.json`–`3.json` and the earlier migrations are untouched. No destructive fallback is used.

The version was 3 when this work started (branch `sprint3-issue-03` at `34b1ff8`). If `main` advances first, renumber before merging (plan §5.5).

## 3. Reads, freshness and refresh

- The repository exposes local-first streams from `<pkg>/feature/inventory/domain/repository/InventoryRepository.kt`: `observeCompleteInventory`, `observeExpiringInventory`, `observeDrafts` and `observePendingConsumes`. Each read emits now and again after every change, using Room's `InvalidationTracker` flow and reading items and metadata in one transaction.
- `refreshCompleteInventory(force)` and `refreshExpiringInventory(force)` update those streams. Without `force`, a fresh snapshot is not refetched. Fresh means:
  - within the shared `CachePolicy.inventory` TTL of **5 minutes**;
  - not invalidated by a write;
  - synchronized on today's Bogotá date.
- **Triggers** (only while the screen is visible, no polling):
  - screen start or return to the foreground (`LifecycleStartEffect`): refresh if due;
  - explicit *Refresh* / *Try again*: forced;
  - reconnect (`ReconnectEffect`): forced only if the last refresh failed;
  - Bogotá date change (see §4).
- **Coalescing.** Each screen keeps one refresh job and ignores a second trigger while it runs. In the repository, a refresh that waited for the inventory lock is skipped when a fetch that *started after it was requested* already succeeded. Observed on the device (§10, W-02): two *Refresh* taps during a PATCH produced no extra GET.
- **Failure.** A failed refresh stores nothing and the previous snapshot keeps its timestamp. A first visit with no snapshot shows the unavailable state with *Try again*, never a fabricated empty list.
- **For BQ3 (S3-05).** `observeCompleteInventory` is the stream BQ3 consumes. `CompleteInventorySnapshot.revision` changes after every sync or confirmed write; it is the `{inventorySyncedAt}` part of the plan's BQ3 key. `usableIngredients(today)` returns items with quantity > 0 that are not expired on today's Bogotá date. No evaluator or placeholder was added.

## 4. Bogotá dates

- `<pkg>/core/common/BogotaCalendar.kt` computes calendar dates in `America/Bogota` with `TimeZone`/`SimpleDateFormat` (minSdk 24, no `java.time`). Day differences compare calendar dates parsed at UTC midnight, never elapsed 24-hour blocks, and never the device's selected zone.
- **BQ2 (Home)** keeps the backend's priority order and the raw `remainingDays` of its snapshot, labelled "As of ⟨date, time⟩". If that sync was on an earlier Bogotá date, Home says so ("an earlier day: days left were counted then and may have changed"), refreshes if online, and offline keeps the original confirmation date. The device never re-scores or re-ranks BQ2, and never relabels yesterday's answer as today's.
- **Complete inventory** computes its own display and eligibility day count from `expirationDate` and today's Bogotá date. Expired, today, tomorrow and later labels, and usable-ingredient eligibility, therefore change at midnight without a request. The stored server `remainingDays` is kept separately and not used for these labels.
- Date changes are detected at the next Bogotá midnight by a single timer in the Inventory ViewModel, and on every return to the foreground: the timer can run late while the device sleeps.

## 5. Drafts

- The form writes the draft about 300 ms after typing stops (`saveDraft`); closing the form flushes it. *Close* keeps it, *Discard draft* deletes it, and *Save* stores and then submits it.
- Drafts are listed on Inventory with "Draft saved on this device ⟨time⟩" and their state. An item with an edit draft shows "Unsaved edit draft".
- After process recreation the open form reopens from `SavedStateHandle`, which holds only the draft id; the fields come from Room. After a force-stop, the draft card's *Continue* restores the exact fields (§10, D-01).
- **Retention.** Seven days without changes, applied when Inventory opens. A draft with an unresolved submission (`SUBMITTING`/`OUTCOME_UNKNOWN`) is kept until the user resolves it, rather than disappearing silently.
- **Account lifecycle.** `RoomInventoryStore` is registered as an `AccountScopedStore`:
  - logout deletes that account's snapshots, drafts and markers;
  - definitive expiry keeps them, unreadable, for the same account's next identification (S3-02 policy);
  - when **another** account becomes active, other accounts' snapshots, drafts and markers are all deleted. Drafts are personal input, and S3-02 left this decision to S3-03.

  Logout itself is unchanged: no flush, no network wait.

## 6. Writes and outcome classification

All writes use a dedicated **mutation client** (`ApiClientFactory.createMutationOkHttpClient`). It has the same pool, timeouts and interceptors as the main client, with two differences:
- `retryOnConnectionFailure(false)`, so OkHttp never resends a write transparently;
- a network interceptor that records, in a per-request `TransmissionTracker` (`@Tag`), whether the request reached a connection.

The session interceptor's single-flight refresh and its **one** retry after an actual 401 are kept, because a 401 means nothing was applied. This was observed on the device (§10, W-01): `POST` → 401 → `auth/refresh` 200 → `POST` 201. The classification lives in `<pkg>/core/network/MutationCall.kt`.

| Situation | Classification | Stored state | User sees |
|---|---|---|---|
| Device offline before sending | Not sent | `NOT_SENT` (consume: no marker) | "You're offline. Not saved: your draft is kept…" |
| DNS, connect or TLS failure (tracker not started); 401 with refresh temporarily unavailable | Not sent | `NOT_SENT` | "Not saved: CampusMeal could not be reached…" |
| Before the request | — | `SUBMITTING` **persisted first** | "Sending…" (Save/Consume disabled) |
| 2xx (204 for consume) | Confirmed | Draft or marker deleted and both snapshots invalidated, in one transaction; then the complete list is refreshed in the same critical section | "Item added/updated" / "… marked as consumed". If that refresh fails: "… The list could not be refreshed yet; tap Refresh". Never an invitation to resubmit. |
| 400, 404, 409, 429 … (4xx except 401/408) | Rejected | `REJECTED` + HTTP code + error `code` | Specific message; the draft stays editable |
| 401 after the interceptor | Rejected (session) | `REJECTED` (401) | "Your session expired. Not saved…" |
| Timeout or connection loss after sending, 408, 5xx, an undecodable 2xx body, cancellation or process death after sending | Outcome unknown | `OUTCOME_UNKNOWN` (consume marker kept) | "We could not confirm whether this was saved…" The list is re-read automatically (a GET, never the write). |
| Consume `404 INVENTORY_ITEM_NOT_FOUND` | Already consumed (plan §3) | Marker deleted, snapshots invalidated, list refreshed | "⟨item⟩ was already consumed." |

- **Reconciliation.** A draft or consume with an unknown outcome blocks another attempt until a **complete sync that finished after that attempt started**. The block holds in the repository too (`NeedsReconciliation`), not only in the UI. After that sync:
  - *create*: the user reviews the refreshed list and resubmits only if the item is missing. There is no idempotency key, so a similar row is never treated as the original POST.
  - *edit*: the card says whether the item already shows the draft's values.
  - *consume*: the card says whether the item is gone (consumed) or still listed (not applied; *Consume* is enabled again).
- **Cancellation** (leaving the screen, session end) after sending records `OUTCOME_UNKNOWN`, written under `NonCancellable`, never "not saved". Before sending it records `NOT_SENT`. If the session already ended, nothing is written. A row left in `SUBMITTING` becomes `OUTCOME_UNKNOWN` the next time Inventory opens (`recoverSubmissions`).
- **No queue, no worker, no automatic replay** on reconnect or reopen (observed on the device, §10). Double taps are ignored in the ViewModel (one busy key per draft or item) and in the repository (in-flight set).

## 7. Serialization and session guards

- One `Mutex` per account in `OfflineFirstInventoryRepository` serializes every complete and BQ2 refresh and every write, for Home and Inventory alike.
- A write and its follow-up refresh run inside the same critical section, through private `…Locked` helpers that never re-acquire the lock. A refresh queued behind a write runs after it (or is coalesced). A response that started before the write can never overwrite it.
- The inventory lock may span HTTP. The session coordinator lock never does: it is taken only inside `runIfCurrent` for the scope check plus the local transaction (authorization header, snapshot replacement, draft and marker states).
- **Lock order** is always inventory lock → coordinator lock. Lifecycle cleanup runs under the coordinator lock and never takes an inventory lock, so logout cannot wait for a slow inventory request.
- Every stream passes values only while its scope is current. Late results after logout or an account change are dropped by `runIfCurrent`, and ViewModels check `isCurrent(scope)` before publishing. A stale scope cannot return cached personal data or resurrect deleted rows.

## 8. Slow responses

Loading states show a cancellable progress indicator. After 8 s they add "The server is taking longer than usual to respond. It may be starting up…", which never claims a confirmed cold start. Room content stays visible during the wait. The timeout policy itself is in [`S3_03_BUGFIX_VALIDATION.md`](S3_03_BUGFIX_VALIDATION.md) §2.

## 9. Files

New:
- `<pkg>/core/common/BogotaCalendar.kt`
- `<pkg>/core/designsystem/SlowResponseNotice.kt`
- `<pkg>/core/network/MutationCall.kt`
- `<pkg>/feature/inventory/data/local/InventoryItemEntity.kt`, `InventoryDraftEntity.kt`, `PendingConsumeEntity.kt`, `RoomInventoryStore.kt`
- `<pkg>/feature/inventory/domain/model/InventorySnapshots.kt`, `InventoryDrafts.kt`
- `app/schemas/…/4.json`
- this document

Removed (replaced by the files above):
- `ExpiringInventoryCache.kt`, `RoomInventoryCache.kt`
- `ExpiringInventoryResult.kt`, `InventoryWrite.kt`

Modified:
- `AppContainer.kt`
- `Freshness.kt`, `CacheMetadata.kt`, `CampusMealDatabase.kt`, `DatabaseMigrations.kt`
- `ApiClientFactory.kt`, `NetworkConfig.kt`
- the inventory API, DTOs, mappers, repository, ViewModel, screen and UI model
- `HomeViewModel.kt`, `HomeScreen.kt`
- `AccountNotIdentifiedScreen.kt`, `AuthScreens.kt` (slow notice)
- `res/values/strings.xml`
- the restaurant files listed in the bug-fix report
- `CampusMealNavHost.kt`

## 10. Verification

Source: branch `sprint3-issue-03`, base `34b1ff8`, dirty (uncommitted S3-03 changes). Tested APK SHA-256: `2adc3273daf7ef0c56de6e766e74723d24f995c0a00c2d35137b177584efbf0d`; the first device steps used the preceding build `2840f97e…`, which differs only in the results-screen fix of the bug-fix report §3.

Device: Samsung SM-S948U1, Android 17. The existing debug install (0.1.0, data present) was updated with `adb install -r`. Network: cellular (`MOBILE[LTE]`, `wifi_on=0`, no VPN, no tethering, no `adb reverse`), except where airplane mode is stated. API: `https://campusmeal-api.onrender.com/api/v1/`, the deployed backend; no local Android-facing backend was used. Evidence: focused UI text dumps and OkHttp request/status lines, with headers redacted and no bodies logged.

### 10.1 Build and host checks

| Check | Result |
|---|---|
| `.\gradlew.bat "-Pcampusmeal.apiBaseUrl=https://campusmeal-api.onrender.com/api/v1/" :app:assembleDebug` (`CAMPUSMEAL_API_BASE_URL` unset; `BuildConfig.API_BASE_URL` = the HTTPS URL ending in `/api/v1/`) | **PASS** |
| `:app:lintDebug` | **PASS**: 0 errors, 17 warnings, the same set as the S3-02 baseline (14 dependency/target notices, 3 `UseKtx` in `KeystoreSessionStorage`). None in S3-03 code. |
| Migration SQL vs exported `4.json`: a host SQLite database built from `3.json` with a profile, a BQ2 row and its metadata, then the `MIGRATION_3_4` statements | **PASS**: all 6 tables match, the 3 existing rows are kept, and the new column is NULL. This is a host check, not Room's on-device validation (see U-01). |
| Temporary JVM harness on the compiled app classes (deleted afterwards), JVM default zone forced to `Asia/Tokyo` | **PASS 39/0**. Bogotá midnight (23:59:59 → 00:00:00 at UTC-5), calendar differences, impossible/leap dates; complete mapper keeps expired, zero and distant items and drops inactive ones; duplicates, impossible dates, negative and NaN quantities are rejected; BQ2 mapper unchanged; `usableIngredients` across midnight; draft validation; mutation classification (see the bug-fix report §2.1) |

### 10.2 Device scenarios

| ID | Scenario | Observed | Result |
|---|---|---|---|
| U-01 | Upgrade with data | Before: `user_version` 3, 1 profile, 2 BQ2 rows, `inventory.expiring` metadata. After `install -r` and launch: `user_version` **4**; profile, BQ2 rows and metadata kept; new tables empty; session kept (no login, Home shows BQ2); no crash. | **PASS** |
| C-01 | Complete inventory | Inventory issued `GET inventory` (200). Created temporary items *S303 Distant* (2026-12-15), *S303 Soon* (2026-10-30) and *S303 Expired* (2026-10-25). Inventory showed them under *Later* ("In 48 days"), *Next 3 days* and *Expired* ("Expired 3 days ago"). Home/BQ2 listed only items ≤ 3 days in backend order ("As of Oct 28, 10:00") and excluded the distant one. No false empty message. | **PASS** |
| R-01 | Offline reads | Airplane mode, force-stop, reopen. Home showed BQ2 "As of Oct 28, 10:00" and Inventory showed the complete snapshot (including *S303 Distant*) immediately. *Refresh* failed (`UnknownHostException`): "Saved copy · updated Oct 28, 09:59" plus "You're offline. Showing the copy saved on this device." | **PASS** |
| R-02 | Synchronized empty snapshot; never-synchronized account | Not exercised on the device: it would need a second, empty account. Covered by the metadata-row logic and the host check "empty response is a valid empty". | **NOT RUN** |
| D-01 | Draft across force-stop (offline) | Typed name, quantity, unit and an incomplete date `2026-10-2`, then force-stopped with the form open. After reopen: card "New item: S303 Expired · Draft saved on this device 10:05 · Not submitted yet". *Continue* restored the exact four fields; *Submit* was not offered while invalid. | **PASS** |
| D-02 | Offline Save | Completed the date and tapped *Save*: "You're offline. Not saved: your draft is kept on this device." No request was sent; the card shows "Not saved". | **PASS** |
| D-03 | Reconnect does not submit | Airplane mode off (cellular): only `GET inventory` 200 (reconnect refresh). No POST or PATCH. Both drafts still in Room. | **PASS** |
| D-04 | Explicit submit clears only that draft | *Submit again* on the create draft: `POST` → 401 → `auth/refresh` 200 → `POST` **201** → `GET inventory`. "Item added." The create draft was deleted; the edit draft for *S303 Distant* remained. | **PASS** |
| W-01 | Rapid double tap | Two taps on *Consume* in one shell command: exactly **one** `DELETE` (204) and one follow-up `GET`; one message. | **PASS** |
| W-02 | Refresh around an edit | *Submit* (edit draft, quantity 12), then *Refresh* twice during the write: `PATCH` 200 → one `GET inventory` 200, with no extra GET (coalesced). The list shows 12; the earlier value did not come back. | **PASS** |
| W-03 | Failed post-write refresh | Not reproduced on the device; needs the follow-up GET to fail right after a successful write. The code path keeps the confirmation and only marks the list stale. | **NOT RUN** |
| X-01 | Unknown outcome by interruption, deployed backend | Edited *S303 Soon* (100 → 1005 g) and force-stopped 250 ms after *Save*. The `PATCH` was sent and no response was recorded; Room had `SUBMITTING`. After reopen: `OUTCOME_UNKNOWN`, card "We could not confirm whether this was saved. Refresh your inventory before trying again." **No automatic PATCH.** *Save* from the form was refused locally ("A previous attempt may already have been saved…"). *Refresh* reconciled it: the server had applied the edit, and the card said "the item already shows these values". Discarded. Across the whole scenario there was **exactly one PATCH**. | **PASS** |
| X-02 | Accepted write with delayed response (local stub) | Host harness: the local server applied the POST and answered after the 2 s read timeout → `OutcomeUnknown(SocketTimeoutException)`, server received it **once**. Not repeated on the device: no Android-facing stub was used. | **PASS (host only)** |
| A-01 | Logout and account isolation | *Log out* deleted all of the account's local rows (0 in every account-owned table; see the bug-fix report §5.2). A second account, cross-account drafts and a late result after logout were not exercised on the device; they are covered by the scope checks in §7. | **PASS** (logout) / **NOT RUN** (second account, late result) |
| T-01 | Bogotá midnight on the device | The device clock was not changed. Covered by the host harness, including a foreign device zone. | **NOT RUN on device** |

### 10.3 Cleanup and residual data

The temporary items were consumed and no synthetic account was created. The test account is left signed out on the phone. Details: [`S3_03_BUGFIX_VALIDATION.md`](S3_03_BUGFIX_VALIDATION.md) §5.2.

### 10.4 Not claimed

Not covered here:
- a second-account isolation run on the device;
- a failed post-write refresh;
- Bogotá midnight on the device;
- an Android-facing stub for accepted-but-delayed writes.

This document also does not claim live backend redeployment, Natalia's review, or completion of the other Sprint 3 strategies (S3-05 BQ3, S3-06 restaurant caches, S3-09 outbox).
