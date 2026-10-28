# S3-03 Bug Fixes — Render Timeout, Complete Inventory and Restaurant Route Continuity

| Field | Value |
|---|---|
| Scope | Findings authorized with S3-03 (Android issue #16): (1) 30 s read timeout vs Render Free cold start; (2) Inventory showed only the BQ2 list; (3) restaurant detail lost the search's walking/total estimate and showed a misleading provider warning; (4) investigate the reported walking-time discrepancy |
| Status | Implemented locally, **uncommitted**. **No backend change** was made. Natalia's review is pending. |
| Previous reports (not rewritten) | [`DEPLOYMENT_VALIDATION_ANDROID_USB.md`](DEPLOYMENT_VALIDATION_ANDROID_USB.md) (CHK-02: `GET auth/me` timed out at 30 s while Render was waking); `CampusMealBack/docs/DEPLOYMENT_VALIDATION_RENDER_NEON.md` |
| Inventory details | [`SPRINT3_INVENTORY.md`](SPRINT3_INVENTORY.md) |

`<pkg>` stands for `android/app/src/main/java/com/campusmeal/android`.

## 1. Confirmed causes

| # | Finding | Cause in the source |
|---|---|---|
| 1 | Requests fail while Render wakes up | `NetworkConfig` defaulted to read 30 s with no call timeout. Render documents about a minute for a Free instance to spin up. |
| 2 | Inventory implies an empty inventory | `InventoryApi` read only `GET inventory/expiring?withinDays=3`, and the Inventory screen rendered that list (plan D-10). The backend's `GET /api/v1/inventory` already returns every active item. |
| 3 | Restaurant detail loses walk/total and warns "Walking estimates are currently incomplete" | `RestaurantsService.getDetail(id)` has no origin, so it always returns `walkingMinutes`/`estimatedTotalMinutes` = null and `routeProviderStatus` = `UNAVAILABLE`. Android displayed those originless fields and showed a warning whenever the status was not `AVAILABLE`. |
| 4 | "4 minutes vs 1 minute" walking time | No defect in the adapter code was reproduced (§4). |

## 2. Timeout policy (finding 1)

Effective settings, defined once in `<pkg>/core/network/NetworkConfig.kt` and applied by `ApiClientFactory.createOkHttpClient`:

| Setting | Before | Now |
|---|---|---|
| Connect | 15 s | 15 s |
| Read | 30 s | **90 s** |
| Write | OkHttp default (10 s, implicit) | 10 s (explicit, unchanged) |
| Whole call | none | **120 s** (`callTimeout`; covers connect, refresh retry, write and read) |

- **Every client uses them.** The single `okHttpClient` in `AppContainer` serves auth (`AuthApi`, refresh, logout) and all protected APIs. The mutation client is derived from it with `newBuilder()` and inherits the same timeouts and interceptors (verified on the JVM, §2.1).
- No other `OkHttpClient` is built and no coroutine timeout wraps a network call in `<pkg>`, so nothing still stops a call at 30 s. The only `withTimeoutOrNull` is the location fix in `FusedLocationProvider`.
- **Unchanged:** HTTPS base URL (`BuildConfig.API_BASE_URL` = `https://campusmeal-api.onrender.com/api/v1/` in the tested APK), system TLS and hostname verification, and header logging only in debug with `Authorization`, cookies and refresh headers redacted (no bodies). The refresh and session behaviour is also unchanged.
- **UX.** Loading states keep Room content visible. After 8 s they add "The server is taking longer than usual to respond. It may be starting up; this can take about a minute." (`SlowResponseNotice`), which never claims a confirmed cold start. The notice appears on:
  - "Confirming your account", the screen of the original timeout;
  - Login and Register;
  - Home BQ2 loading;
  - Inventory refresh, with *Cancel*;
  - draft and item "Sending…";
  - restaurant results loading, with *Cancel*, which leaves the screen and cancels the search;
  - restaurant detail loading.
- After a final failure, retry stays explicit. There are no automatic POST/PATCH/DELETE retries, no keep-alive pings, no periodic health requests and no loop to keep Render awake.
- 90 s is an allowance, not a guarantee of availability.

### 2.1 Evidence

| Check | Result |
|---|---|
| JVM harness on the compiled `ApiClientFactory` (temporary, deleted): shared client | connect/read/write/call = **15000/90000/10000/120000 ms**; `retryOnConnectionFailure` = true (reads) |
| Same: mutation client | Same timeouts, same application interceptors, `retryOnConnectionFailure` = **false**, plus `TransmissionTrackingInterceptor` |
| Local delayed server (127.0.0.1, `GET /api/v1/inventory` answers after 35 s), through the app's Retrofit/OkHttp factory | Old policy (read 30 s): **fails after 30 s** (`InterruptedIOException: timeout`). New policy: **200 after 35 s**. **PASS.** This is a local delay, not a reproduction of Render. |
| Mutation classification through the app's `mutationCall` against the local server | 201 → `Applied`; 400 → `Rejected(400, VALIDATION_ERROR)`; 503 → `OutcomeUnknown`; undecodable 2xx → `OutcomeUnknown(UndecodableBody)`; 204 → `Applied`; 404 → `Rejected(404, INVENTORY_ITEM_NOT_FOUND)`; server applied the POST but answered after the read timeout → `OutcomeUnknown`, received **once**; connection refused → `NotSent` (tracker not started). **PASS.** |
| Connection closed by the server after receiving a write | Mutation client → `OutcomeUnknown`, received once. The comparison client with `retryOnConnectionFailure = true` also received it once in this harness: the "no transparent retry" property is shown by configuration, not by a demonstrated difference. |
| **Real Render cold start, phone on cellular** (observed, not induced: the first launch after `install -r`, with no host request beforehand) | 09:53:50 `GET auth/me` and `GET inventory/expiring` → first responses after **32.2 s and 32.9 s**, beyond the old 30 s limit. Then one `POST auth/refresh` 200 and the BQ2 retry 200 (277 ms). Home rendered its stored BQ2 list meanwhile. **PASS.** |

**Observed pre-existing S3-02 defect, not fixed here.** In the cold-start run, both requests returned 401 (expired access token) and only **one** refresh ran, as designed. But `auth/me` was not retried, so Home showed no name until a later refresh. The session stayed active. The cause is `NetworkAuthRepository.kt:372`: after a successful refresh it replaces the token lineage with `listOf(newToken)`, and after a restart the lineage was empty. The second concurrent 401, carrying the previous token, is then classified as `NotCurrentSession` and passed through. S3-03 was told to preserve the existing refresh logic, so this is reported for an S3-02 follow-up: keep the rejected token in the lineage when refreshing.

## 3. Restaurant route continuity (finding 3)

- **Handoff.** When the user taps a row of the results on screen, `RestaurantResultsViewModel.routeFor(id)` builds a `SelectedRoute`. It holds:
  - the account scope;
  - the exact validated request that produced those results;
  - a `RouteEstimateSnapshot` with the restaurant id, `walkingMinutes`, `estimatedTotalMinutes`, the search's `lastUpdatedAt` and its source (network or saved copy).

  It lives in a `remember` of `CampusMealNavHost`, so it is memory only. It is never persisted, logged, put in navigation arguments or saved state, or sent to analytics. It is discarded with the process and with the per-scope graph.
- **Detail.** The detail ViewModel receives the snapshot only if all of these hold:
  - same scope;
  - `request == currentContext`;
  - same restaurant id.

  It still loads fresh address and meals from `GET restaurants/:id`. Walk and total come only from the snapshot, exactly as the backend returned them. Android never recomputes the total, never uses straight-line distance and never calls the provider.
- **Labels.** The route card states its provenance and its own age, separate from "Restaurant details updated ⟨time⟩":
  - "Estimate from your search · Calculated for your search at ⟨time⟩";
  - "Earlier estimate" when the search was a saved copy or is older than `CachePolicy.restaurantSearch` (5 min). A successful detail request never makes it current again.
  - "Walking estimate unavailable" when that restaurant had no estimate in the search. This is a genuine provider gap; address and meals are still shown.
  - "Search from your current location to get a walking estimate" when there is no matching search.
- **Removed:** the blanket "Limited route information" warning driven by the originless detail status. The search screen's own `PARTIAL`/`UNAVAILABLE` banner is unchanged. Each row is judged individually, so a `PARTIAL` search still hands over a valid estimate for the selected restaurant.
- **Refresh route** (shown for earlier, missing or unavailable estimates) re-runs the existing search action with the same context at the current time (`requestedNow()`) and returns to the results. It uses no new endpoint and no location workflow.
- **Superseded loads.** The detail ViewModel cancels a previous load on retry and checks the restaurant id and scope before publishing. The results ViewModel drops late answers of a replaced request and, so that *Back* preserves the shown results, ignores a recomposition with an identical request. A new search always has a new `requestedAt`; *Retry* still re-runs explicitly.
- **Unchanged:** BQ4 order and filtering, BQ5 ranking. S3-06's keyed Room search and detail caches remain Natalia's work.

## 4. Walking-time diagnosis (finding 4)

Method: same public origin (the Uniandes campus centre from `CampusCatalog`, 4.6025, −74.0653, which a manual campus choice sends), seeded public restaurant coordinates (`CampusMealBack/src/database/seeds/restaurants.seed.ts`, unchanged since `f6dec55`), and the configured provider:
- `ROUTE_PROVIDER_MODE=valhalla`, URL empty, so the public FOSSGIS `sources_to_targets` default;
- timeout 5000 ms;
- read from the local `.env` and `.env.render` keys only, no secrets printed.

No user GPS position was used or recorded.

| Restaurant | Straight line | Deterministic adapter | Valhalla, direct (`time` s → min) | Local `ValhallaRouteAdapter` (compiled `dist`, main code) | Deployed API (phone search, manual Uniandes, 10:17 and 10:21) |
|---|---|---|---|---|---|
| Green Bowl | 71 m | 1 min (total 17) | 200 s / 239 m → **3 min (total 21)** | **3 min (total 21)** | **1 min (total 17)** |
| Quinoa Corner | 276 m | 3 min (total 21) | 253 s / 352 m → **4 min (total 23)** | **4 min (total 23)** | **4 min (total 23)** |

Valhalla answered identically on repeated queries, with 5 targets and with the 2 targets the backend sends.

Findings:
- **The adapter code checks out.** It sends `{lat, lon}` in the right order with `pedestrian` costing, and maps matrix cells by `to_index`. It converts Valhalla's `time` (seconds) to minutes with `Math.round`, minimum 1, and the service adds `2 × walk + 15` for the round trip plus dining. Run locally against the real provider, it returns exactly Valhalla's values. Its coordinate columns are `double precision`, so they are numbers, not strings. **No parsing, index, unit or formula defect was reproduced, so the adapter is unchanged.**
- **A pedestrian-network estimate (3–4 min) above a straight-line one (1 min) is expected**: the network distance to Green Bowl is 239 m against 71 m in a straight line. The two estimates were not forced to match, and production was not switched to deterministic.
- **Open point for the team.** The deployed API's Green Bowl value (1/17) matches neither the provider nor the local adapter for this origin, while its Quinoa Corner value matches Valhalla. A plausible explanation is the data or configuration of the deployment: stored Green Bowl coordinates in Neon, the Render environment's `ROUTE_PROVIDER_MODE`, or the deployed build. Checking it needs access to Neon or Render, which this work was not allowed to use. Suggested private checks for the team:
  - compare Green Bowl's `latitude`/`longitude` in Neon with the seed;
  - confirm `ROUTE_PROVIDER_MODE` and the deployed commit in Render.
- Latent hardening, not needed for this finding and therefore not applied: `ValhallaRouteAdapter.mapResponse` would turn a negative `time` into 1 minute and ignore an out-of-range `to_index`. Valhalla does not return those, but a future adapter change could reject negative durations.

No `CampusMealBack` source file was modified, so no backend redeploy is needed for these fixes. The Android handoff works with the API already deployed.

## 5. Validation

APK SHA-256: `2adc3273daf7ef0c56de6e766e74723d24f995c0a00c2d35137b177584efbf0d` (base `34b1ff8`, uncommitted changes). Device: SM-S948U1, Android 17, `install -r` over the existing debug install, cellular data. Inventory scenarios are in [`SPRINT3_INVENTORY.md`](SPRINT3_INVENTORY.md) §10.

| ID | Check | Observed | Result |
|---|---|---|---|
| B-01 | Effective timeouts on both clients | 15/90/10/120 s; mutation client with no connection retry (JVM harness on compiled classes) | **PASS** |
| B-02 | Read delayed beyond 30 s (local server) | Old: fails at 30 s. New: 200 at 35 s. | **PASS** (local delay) |
| B-03 | Real Render cold start on the phone | First responses at 32.2 s and 32.9 s, which completed; previously they would have failed at 30 s | **PASS** (observed once) |
| B-04 | Slow notice on screen | 10:25 `GET restaurants/:id` took 18.9 s (then 401 → refresh 200 → retry 200). The detail screen showed "Loading restaurant... The server is taking longer than usual to respond. It may be starting up; this can take about a minute." (UI dump). The notice was not captured on the other screens. | **PASS** (detail screen) |
| I-01 | Complete inventory on the Inventory screen | Expired, ≤ 3-day and 48-day items shown; Home/BQ2 lists only ≤ 3 days | **PASS** |
| R-01 | Search → detail keeps walk/total and search time | Green Bowl: results 1 min / 17 min; detail 1 min / 17 min, "Calculated for your search at Oct 28, 10:17" (and 10:21 after reinstall). Quinoa Corner 4 / 23 in both. Address and meals loaded (`GET restaurants/:id` 200). No "Limited route information" warning. | **PASS** |
| R-02 | Back preserves results | Before the results fix, *Back* re-sent `POST restaurants/search`. After it, *Back* sent no request and kept the 10:21 results. | **PASS** (after fix) |
| R-03 | Historical estimate and *Refresh route* | A detail opened at 10:27:53 from the 10:21 search showed "Earlier estimate · From your search at Oct 28, 10:21. It may no longer be accurate…" plus *Refresh route*. Tapping it sent `POST restaurants/search` 200 and returned to the results; the next detail showed "Calculated for your search at Oct 28, 10:28". | **PASS** |
| R-04 | No snapshot: direct open after process death | `am kill` on the detail screen, then relaunch: the app reopened at Home, because navigation state is not restored in the authenticated graph (S3-02 design). So the snapshot did not survive, but the "Search from your current location…" state could not be reached on the device. It is covered by the null-snapshot code path. | **NOT RUN** (device) |
| R-05 | Account or context change invalidates the handoff | By construction: the graph is keyed by scope, and a context change creates a new request, which fails the `request == currentContext` check. Not exercised separately on the device. | **NOT RUN** (device) |
| R-06 | Destination genuinely without a route; `PARTIAL` search | The deployed provider returned estimates for every result; a provider failure was not induced on the shared backend | **NOT RUN** |
| P-01 | Provider diagnosis | §4: adapter checks out, no defect reproduced, deployment difference left for the team | **Documented** |

### 5.1 Notes on the route checks

- The fresh/historical label is computed when the detail loads. A detail left open across the five-minute boundary keeps its label until it is reopened or retried. This was observed: a detail opened at 10:25 still said "Estimate from your search" at 10:26:46. It was accepted as a small limitation of this continuity fix.
- Back from detail initially re-ran the search because the results route recomposes with the same request. The one-line guard in `RestaurantResultsViewModel.search` fixed it, and the device check R-02 was repeated with APK `2adc3273…`.

## 5.2 Cleanup and residual data

- The three temporary items (*S303 Distant*, *S303 Soon*, *S303 Expired*) were created only on the existing test account and later consumed through the app (`DELETE` 204). On the backend they remain as **inactive** rows (soft deactivation), as for any consumed item. The account's own items (*milk*, *tomatoes*) were not changed: an edit form opened on *milk* by mistake was closed without changes or a draft.
- No synthetic accounts were created.
- **Logout check (A-01):** *Log out* from Profile showed the login screen immediately. Room then held 0 rows in `account_profile`, `inventory_items`, `expiring_inventory_items`, `inventory_drafts`, `inventory_pending_consumes` and `cache_metadata`. **PASS.** The test account is left **signed out** on the phone; the user signs in again privately when needed.
- Device: the app data and the other user profiles were preserved (no uninstall, `pm clear` or signature change). The only temporary file, `/sdcard/s303_ui.xml` (UI dump), was removed. No host server, container or process from this run is still running.

## 6. Remaining team actions

1. Natalia's review of S3-03 and these fixes.
2. Commit the new Room schema `4.json` together with the migration. If `main` reached v4 first, renumber (plan §5.5).
3. Check privately the deployed Green Bowl coordinates and the Render route-provider configuration (§4).
4. S3-02 follow-up for the token-lineage gap (§2.1).
5. No backend redeploy is needed: no backend source changed.
