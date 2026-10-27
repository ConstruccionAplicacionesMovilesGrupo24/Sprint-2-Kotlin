# S3-02 — Session, Local Profile, and Account Isolation

| Field | Value |
|---|---|
| Issue | S3-02 (Sprint 3 session/account foundation) |
| Owner | Juan Pablo (`bedoya0611`) |
| Reviewer | Natalia (`NataliaEspitia`) — **review pending** |
| Depends on | S3-01 (`android/docs/SPRINT3_PLAN.md`) |
| Status | Implemented locally, uncommitted. Debug build passes. Backend- and device-dependent checks are **pending** (§9). |

`<pkg>` stands for `android/app/src/main/java/com/campusmeal/android`.

## 1. What this issue delivers

1. The account is identified with `GET /api/v1/auth/me`. Its `id` is the `accountId` used for every account-owned record on the device. It is never derived from the email, the name, or decoded JWT claims.
2. A minimal Room `account_profile` table supports offline Home/Profile.
3. One `AccountSessionCoordinator` owns the active `(accountId, generation)` scope. Credentials, local writes, refresh retries, and UI publication are checked against the scope captured when an operation started.
4. A temporary refresh failure keeps the session, the identity, and every cache, and the request takes the offline path.
5. A definitive rejection (refresh `401`/`400`, or a protected `401` after a successful refresh) expires only the session that produced it.
6. Logout completes locally right away. Remote revocation is a separate, best-effort request.
7. Shared building blocks for later issues: `CachePolicy`, `Freshness`, `ConnectivityObserver`, `ReconnectEffect`, `AccountScopedStore`, and `AccountLifecycleListener`.

Out of scope here, as planned: complete inventory and drafts (S3-03), recipes (S3-04/S3-05), contextual restaurant caching (S3-06), reports, comparison history, the outbox and workers (S3-09), and Firebase distribution.

## 2. Components and source paths

| Component | Path | Role |
|---|---|---|
| `AccountSessionScope`, `SessionState`, `AccountSessionCoordinator` | `<pkg>/core/session/AccountSessionCoordinator.kt` | Single `Mutex`; persisted `session.activeAccountId` / `session.generation` (DataStore); all transitions; `runIfCurrent` / `runIfGeneration` for scope-checked local work. |
| `AccountLifecycleListener`, `DeactivationReason`, `AccountScopedStore`, `AccountDataCleaner`, `AccountWork`, `AccountWorkCanceller` | `<pkg>/core/session/AccountLifecycle.kt` | Lifecycle hooks run inside the coordinator lock; per-store deletion; WorkManager cancellation names shared with S3-09. |
| `SessionAuthorizationHeaderProvider` | `<pkg>/core/session/AuthorizationHeaderProvider.kt` | Returns a bearer header **only while** the expected scope/generation is active. |
| `SessionRefreshInterceptor` | `<pkg>/core/session/SessionRefreshInterceptor.kt` | Exact credential-route exclusions, outcome-based refresh, one retry, centralized expiry. |
| `SessionRefreshUnavailableException` | `<pkg>/core/session/SessionRefreshUnavailableException.kt` | `IOException` thrown after a temporary refresh failure; `apiCall` maps it to `NetworkUnavailable`. |
| `KeystoreSessionStorage` | `<pkg>/core/session/KeystoreSessionStorage.kt` | Unchanged encryption. Writes now use `commit()` on `Dispatchers.IO`, so token writes are on disk before the next transition step. |
| `NetworkAuthRepository`, `AuthRepository`, `SessionStatus`, `RefreshOutcome`, `IdentificationResult`, `AccountIdentifier` | `<pkg>/feature/auth/data/NetworkAuthRepository.kt`, `<pkg>/feature/auth/domain/` | Login, registration, identification, refresh, expiry, logout. |
| `AccountNotIdentifiedScreen`, `AccountIdentificationViewModel` | `<pkg>/feature/auth/AccountNotIdentifiedScreen.kt` | Explicit "account not identified" state with retry and logout. |
| `ProfileRepository`, `AccountProfileEntity`, `AccountProfileDao` | `<pkg>/feature/profile/data/ProfileRepository.kt`, `<pkg>/feature/profile/data/local/AccountProfileEntity.kt` | `auth/me`, identification, cached profile. |
| `SessionViewModelStores` | `<pkg>/app/SessionViewModelStores.kt` | ViewModelStore of the authenticated graph, cleared on any scope change. |
| `CampusMealApp` | `<pkg>/app/CampusMealApp.kt` | Status-driven UI; the authenticated graph is keyed by its scope. |
| `CachePolicy`, `Freshness`, `formatSyncedAt` | `<pkg>/core/common/CachePolicy.kt`, `<pkg>/core/common/Freshness.kt` | TTL defaults; fresh/stale/missing; America/Bogota labels. |
| `ConnectivityObserver`, `AndroidConnectivityObserver`, `ReconnectEffect` | `<pkg>/core/network/ConnectivityObserver.kt`, `<pkg>/core/network/ReconnectEffect.kt` | Network hint and reconnect trigger. |
| Room v3 + `MIGRATION_2_3` | `<pkg>/core/database/CampusMealDatabase.kt`, `<pkg>/core/database/DatabaseMigrations.kt`, `android/app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/3.json` | `account_profile`; account-owned `expiring_inventory_items`. |

Adapted call sites (scope parameter, scope-checked publication, no cache deletion on 401): `OfflineFirstInventoryRepository`, `RoomInventoryCache`, `InventoryViewModel`, `HomeViewModel`/`HomeScreen`, `ProfileViewModel`/`ProfileScreen`, `NetworkRestaurantRepository`, the restaurant results and detail ViewModels, `NetworkMealDecisionRepository`, `CompareMealOptionsUseCase`, `RecommendationEventReporter`, `DecisionViewModel`, and `CampusMealNavHost`. `AndroidManifest.xml` now declares `ACCESS_NETWORK_STATE`. `SessionRepository.signOut()` was removed: it was unused and would have bypassed the coordinator.

## 3. Shared APIs for Juan Pablo, Natalia, and Daniel

### 3.1 Capturing and checking a scope

```kotlin
// ViewModels receive the scope of the graph they belong to (CampusMealNavHost passes `sessionScope`).
class MyViewModel(private val repo: MyRepository, private val coordinator: AccountSessionCoordinator,
                  private val scope: AccountSessionScope) : ViewModel() {
    fun load() = viewModelScope.launch {
        val result = repo.load(scope)                    // the repository receives the captured scope
        if (coordinator.isCurrent(scope)) publish(result) // never show another scope's result
    }
}

// Repositories: credentials only for that scope, HTTP outside the lock, scope-checked local write.
val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return Unauthorized
val response = apiCall { api.get(authorization) }         // no lock held here
coordinator.runIfCurrent(scope) { dao.replace(scope.accountId, rows) } // Room transaction inside
```

- `runIfCurrent(scope) { … }` / `runIfGeneration(generation) { … }`: run **local work only** (Room transactions, DataStore edits, reading credentials). They return `null`, without running the block, when the scope is no longer active. Never perform HTTP inside them.
- `isCurrent(scope)`: lock-free check before publishing UI state.
- `currentScopeOrNull()`: the active identified scope, or `null`.
- `sessionState: Flow<SessionState>`: the restored state followed by every transition (for example, S3-09 can observe it to schedule work for the identified scope).

### 3.2 Owning account data

Implement `AccountScopedStore` for every new account-owned table or DataStore key, and register it in `AppContainer` (`AccountDataCleaner(stores = listOf(…))`):

- `deleteAccount(accountId)`: runs on explicit logout.
- `deleteAllExcept(accountId)`: runs before another account becomes active. It must also remove ownerless legacy rows.

Delete only your own rows or keys. Never clear a whole database or DataStore file, because that would also erase the future S3-09 outbox. S3-03 decides whether drafts register `deleteAllExcept`; plan §7.4 only requires purging *read caches* on an account change.

### 3.3 Lifecycle integration points (S3-09)

`AccountLifecycleListener` callbacks run inside the coordinator lock:

| Callback | When | Current implementation | S3-09 integration |
|---|---|---|---|
| `onAccountDeactivated(accountId, reason)` | Logout, expiry, credential replacement, account change | `AccountWorkCanceller` cancels unique work `event-outbox-sync:{accountId}` and tag `account:{accountId}` (`AccountWork`). `AccountDataCleaner` deletes read caches on `LOGOUT` only. | No change: outbox rows are **not** deleted by any S3-02 code. |
| `onAccountActivating(accountId)` | Before an identified account is published | `AccountDataCleaner` purges other accounts' read caches. | — |
| `onAccountActivated(scope)` | After login/registration identification, an account change, or restoration of an identified session at startup | No listener yet. | Schedule that account's pending events with `scope.generation`. Foreground re-scheduling is S3-09's own trigger. |

Event delivery is **pending S3-09**. No outbox table or worker exists. `RecommendationEventReporter` is still memory-only, but each retry now uses only the credentials of the scope it started under, and it stops once that scope ends.

### 3.4 Utilities

- `CachePolicy` (`container.cachePolicy`): `inventory` 5 min, `recipeCatalog` 24 h, `restaurantSearch` 5 min, `restaurantDetail` 30 min, `analyticsReport` 15 min.
- `Freshness.of(syncedAt?, ttl)` returns `Missing`, `Fresh`, or `Stale`; `Freshness.synced(syncedAt, ttl)` returns `Fresh` or `Stale`. `Missing` means never synchronized, which is not the same as a synchronized empty result. Home's offline inventory hint uses it.
- `formatSyncedAt(epochMillis)`: "Oct 21, 14:05" in America/Bogota. Instants stay UTC in storage.
- `container.connectivityObserver.isOnline` registers a single default-network callback while collected (`WhileSubscribed(5 s)`), unregisters it afterwards, and never polls. It is a hint: HTTP results stay authoritative.
- `ReconnectEffect(online) { … }` runs once per offline-to-online transition while the caller is composed, never for the initial value.

## 4. Identity and bootstrap

| Situation | Result |
|---|---|
| Login or registration succeeds (both return a token pair per `CampusMealBack:docs/API_CONTRACT.md` §5) | Under the lock, the coordinator first persists a new generation with **no** active identity, then stores the tokens (`replaceCredentials`). `auth/me` then runs outside the lock. `publishIdentity` writes `account_profile` and publishes `session.activeAccountId` last. |
| `auth/me` fails after tokens were stored | `SessionStatus.AccountNotIdentified`: an explicit screen with "Try again" and "Log out". No account-owned data is readable. Each foreground return (`LifecycleStartEffect`), reconnect (`ReconnectEffect`), or tap makes at most one request. |
| Startup with tokens and a persisted identity | The identity is restored before any account-owned read. Offline reading works with no new authentication. |
| Startup with tokens but no identity | `AccountNotIdentified`. |
| Startup with an identity but no tokens (interrupted logout/expiry) | The identity is cleared, giving a signed-out state. |
| A later `auth/me` returns a different `id` | An account transition: the old scope is invalidated (generation +1, work cancelled) before the new account is published. |
| A late login, registration, or `auth/me` completion after logout or a newer attempt | Discarded: the generation captured when it started no longer matches. Superseded login tokens are revoked in the background. |

Process interruption is handled conservatively. The persisted identity is cleared before new credentials are written, and published only after its profile write. Token writes are committed before the next step. An interrupted transition therefore ends unidentified or signed out, never with account A's identity paired with account B's tokens.

## 5. Refresh classification and interceptor behavior

| Refresh answer | `RefreshOutcome` | Effect |
|---|---|---|
| `200` with two non-blank tokens | `Refreshed` | Tokens are stored only if the session's generation is still active; the protected request is retried **once** with that token. |
| No connection, timeout, `408`, `429`, `5xx`, undecodable body, blank tokens | `TemporarilyUnavailable` | Tokens, identity, profile, and caches are kept. The interceptor closes the 401 and throws `SessionRefreshUnavailableException`, so the caller sees `NetworkUnavailable` and uses its cache. |
| `401`, or `400` (unusable stored token) | `Rejected` | That session is invalidated through the coordinator, giving `SessionStatus.Expired`. |
| Any other status (for example `403`, `404`, `409`) | `TemporarilyUnavailable` | Treated **conservatively as a protocol problem**, not as a credential rejection, so a server bug cannot sign users out. |
| The rejected token belongs to a session that is no longer active | `NotCurrentSession` | No refresh, retry, or expiry; the 401 reaches the stale caller, whose result is discarded. |

The interceptor also follows these rules:

- **Exclusions.** Only the exact routes `auth/login`, `auth/register`, `auth/refresh`, and `auth/logout` skip refresh. `GET auth/me` is refreshed like any protected call.
- **Single flight.** `refreshMutex` and the "already replaced by another request" check are kept. A small in-memory token lineage (up to the last 5 access tokens of the current generation) traces a 401 back to the session that sent it. A request started under A is therefore never retried with B's token, and a failure of A never expires B.
- **One retry.** If the retry still gets `401`, `expireSession(generation)` ends that session only. There is no loop.
- `403` is never refreshed and never expires the session.
- Discarded responses are closed.
- Cancellation stays cancellation: `apiCall` calls `ensureActive()` before mapping an `IOException`.
- `runBlocking` remains on OkHttp's thread only, as allowed for this MVP.
- Feature repositories **no longer delete caches on 401** (inventory reads and writes, restaurant search). Session lifecycle code owns cleanup.

## 6. Logout versus expiry

| | Explicit logout | Definitive expiry |
|---|---|---|
| Trigger | The user taps "Log out" (Profile or the not-identified screen) | Refresh `Rejected`, or a protected `401` after a successful refresh |
| Transition | One coordinator transition: capture and clear tokens, then generation +1 and no identity | Same, for the expected generation only |
| Work | `AccountWorkCanceller` cancels the account's unique/tagged work | Same |
| Local data | Read caches of the departing account deleted: `account_profile`, its `expiring_inventory_items` and metadata, `restaurants.lastSearch.{accountId}` | **Kept on disk** but unreadable until the **same** account is identified again; purged if another account is identified first |
| UI | Signed-out graph immediately | Session Expired modal |
| Network | Afterwards, outside the lock: best-effort `POST auth/logout` with the captured credentials, in an application scope; never awaited; its result changes nothing | None |

There is no pre-logout event flush and no five-second wait. Remote revocation cannot be confirmed offline: the server refresh session may stay valid until it expires.

## 7. Account isolation

- **Room.** `expiring_inventory_items` has `accountId`, primary key `(accountId, itemId)`, and a unique index `(accountId, priorityIndex)` that preserves backend order per account. Its metadata key is `inventory.expiring:{accountId}`. Read and replace run in Room transactions, and the metadata row survives an empty list, so a synchronized empty BQ2 result stays distinct from no cache.
- **DataStore.** The restaurant snapshot moved to `restaurants.lastSearch.{accountId}` and is written only under its search's scope. The ownerless Sprint 2 key `restaurants.lastSearch` is removed by `deleteAllExcept` when an account is identified. **Limitation (S3-06):** this is still one last-search snapshot per account, not keyed by search context and without a TTL. Offline it can show the account's last search for a different context. It is not a context-compatible cache.
- **UI state.** `CampusMealApp` keys the authenticated graph by `AccountSessionScope` and provides a `SessionViewModelStores` owner. Any scope change, even between two authenticated sessions, discards the NavController and clears every ViewModel, cancelling their work. The ViewModels also check `isCurrent(scope)` before publishing. The expired state no longer composes the main graph behind the modal (see §10).
- **Generation.** The generation is not stored on cache rows. Account id owns durable records; generation protects operations and results. A new session of the same account reads its retained data under its new scope.

## 8. Room migration

- Version **2 → 3** (`MIGRATION_2_3`, registered in `DatabaseMigrations.ALL`). Schema exported to `android/app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/3.json` (left uncommitted). `1.json`/`2.json` and `MIGRATION_1_2` are unchanged. No destructive fallback.
- Creates `account_profile` (`accountId` PK, `name`, `email`, `role`, `fetchedAt`).
- Rebuilds `expiring_inventory_items` with `accountId`. **Limited cache loss:** v2 rows have no owner and cannot be attributed safely, so they are discarded together with the ownerless `inventory.expiring` metadata row. The next online BQ2 load refills the cache. Other `cache_metadata` rows and all other data are preserved.
- After an upgrade, stored tokens have no persisted identity, so the app shows `AccountNotIdentified` until `auth/me` answers. Nothing is assigned to the current user by guesswork.

## 9. Validation

### 9.1 Executed checks

| Check | Command or method | Result |
|---|---|---|
| Baseline debug build (before changes) | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Debug build with S3-02 | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL`; no Kotlin warnings with `--rerun-tasks` on `compileDebugKotlin` |
| Android lint | `./gradlew :app:lintDebug` | `BUILD SUCCESSFUL`; 0 errors, 17 warnings. 14 are dependency/target-version notices (upgrades are out of scope). 3 are `UseKtx` style hints on the existing `SharedPreferences.edit()` chains in `KeystoreSessionStorage`. No other warning is in an S3-02 file. |
| Migration SQL versus exported v3 schema | Host script: build a v2 database from `2.json`, seed an ownerless inventory row, the `inventory.expiring` metadata row and an unrelated metadata row, apply the `MIGRATION_2_3` SQL, compare `PRAGMA table_info`/`index_list` with `3.json` | **PASS**: all three tables and the index match; 0 legacy rows; only `unrelated.dataset` metadata left. This is a host-side SQL check, **not** Room's on-device validation. |
| Whitespace | `git diff --check`; `git diff --no-index --check` for new files | No issue in S3-02 changes. The only finding is a pre-existing, uncommitted extra blank line at the end of `OfflineFirstInventoryRepository.kt`. It is the user's own change and was preserved, not "fixed". |

### 9.2 Manual scenarios

The backend was not running locally, Docker was not running, and no physical device was attached during implementation. Backend-dependent scenarios therefore remain **pending**. "Code review" rows describe reasoning about the source, **not** an executed check. Final Sprint 3 acceptance requires physical-device evidence.

| Scenario | Steps | Expected | Observed | Evidence / status |
|---|---|---|---|---|
| Login / registration | Log in and register against the backend | Flows work; identity from `auth/me`; no other account's data | Not run (no backend) | **Pending** |
| Tokens received, identification interrupted | Log in, block `auth/me` (stop backend after login) | `AccountNotIdentified` with retry/logout; no account data | Not run | **Pending** · code review: `NetworkAuthRepository.authenticate` → `identify` |
| Offline reopening after identified login | Log in, load Home/Profile, airplane mode, reopen | Own cached profile and BQ2 data with timestamps | Not run | **Pending** |
| Offline startup without identified account/cache | Start with no stored tokens, network disabled, submit a login | Login screen; login shows connection error; nothing persisted | Emulator (§9.3): login screen; "We could not reach CampusMeal…"; token file empty; no session keys in DataStore; no crash | **Executed on emulator**; physical device pending |
| Expired access token, valid refresh token | Wait >15 min (or shorten `JWT_ACCESS_TTL`), open Profile | `auth/me` refreshes and retries once | Not run | **Pending** · code review: route exclusion is exact |
| Concurrent protected 401s | Home (profile + inventory in parallel) with an expired token | One refresh; both retried with the new token | Not run | **Pending** |
| Temporary refresh failures | Refresh with backend down / 5xx / 429 | Session and caches kept; offline states shown | Not run | **Pending** · code review: §5 table |
| Definitive refresh rejection | Revoke the refresh session (logout from another client) | Expired modal; account data unreadable | Not run | **Pending** |
| Protected 401 after successful refresh | Deactivate the user server-side mid-session | Centralized expiry, no loop | Not run | **Pending** |
| Logout during slow request / identification | Throttle network, log out mid-request | Immediate local logout; late result ignored | Not run | **Pending** · code review: generation checks |
| A's request/refresh then login as B | Log out A during a slow call, log in as B | No A data/expiry in B; no retry with B's token | Not run | **Pending** · code review: token lineage + `runIfGeneration` |
| Cached empty BQ2 response | Account with no expiring items, then offline | "Nothing expires…" from cache, not "unavailable" | Not run | **Pending** · code review: metadata row survives empty list |
| Upgrade with existing local data | Install `main` APK, sync BQ2, install this APK | Migration opens; ownerless caches discarded; `AccountNotIdentified` until `auth/me` | Host SQL check only (§9.1) | **Pending on device** |

### 9.3 Emulator smoke test (2026-10-27)

Environment: AVD `Tusky_API_36` (Android 16), headless. No backend. The S3-02 debug APK was installed with `adb install -r` over an existing CampusMeal 0.1.0 build that had been installed on the emulator on 2026-10-06. That app had a v2 `campusmeal.db` with no cached rows and no stored tokens.

| Step | Observed |
|---|---|
| Launch | `am start` status ok. The login screen ("Welcome back") rendered. No `FATAL`/crash-buffer entries. A "System UI isn't responding" dialog appeared; `dumpsys` attributed it to `com.android.systemui` (cold headless boot), not to CampusMeal. |
| Wi-Fi and mobile data disabled; placeholder credentials submitted | "We could not reach CampusMeal. Check your connection and try again." `campusmeal_secure_session.xml` stayed an empty map, and the DataStore file was not written (no session generation/identity persisted for a failed login). No crash. |
| Database | Still `user_version` 2: Room opens the database lazily, and no account was signed in, so `MIGRATION_2_3` did **not** run on the device. The on-device upgrade check remains pending. |

The emulator network was re-enabled and the emulator shut down afterwards. The emulator now has the S3-02 debug build installed instead of the earlier 0.1.0 build.

## 10. Differences from the plan and from the earlier baseline

- **Expired state UI.** Plan §7.6 describes the existing Session Expired modal "over the last screen". It now appears without the main graph behind it, so the ended session's screens and ViewModels are discarded rather than left running behind the dialog.
- **Restaurant legacy key.** Plan §5.3 assigns removal of `restaurants.lastSearch` to S3-06. S3-02 already removes it (ownerless) and uses the interim per-account key `restaurants.lastSearch.{accountId}`. S3-06 replaces that key with `restaurant_search_cache`.
- **Unexpected refresh statuses.** Plan §7.5 lists only the `Rejected` and `TemporarilyUnavailable` cases. Statuses outside both lists are treated as `TemporarilyUnavailable` (protocol problem), and a fourth internal outcome, `NotCurrentSession`, covers 401s of ended sessions.
- **Restored-session hook.** `onAccountActivated` is also called when an identified session is restored at startup, so S3-09 has a single scheduling hook.
- **No pre-logout flush.** The local plan already states there is no pre-logout flush (§7.4), so there was no discrepancy on that rule.
- **Repository name.** The local clone's `origin` is still `ConstruccionAplicacionesMovilesGrupo24/Sprint-2.git`, not `Sprint-2-Kotlin`. It was not changed.

## 11. Remaining work before acceptance

- Run §9.2 against the shared backend on an emulator, then on a **physical device** (plan §9), recording real observations and screenshots.
- On-device upgrade check from the previous `main` APK with data present.
- Natalia's review of this issue.
- S3-03 can now build `inventory_items`/`inventory_drafts` as `AccountScopedStore`s. S3-09 can add its listener (`onAccountActivated`) and outbox. S3-06 replaces the interim restaurant key.
