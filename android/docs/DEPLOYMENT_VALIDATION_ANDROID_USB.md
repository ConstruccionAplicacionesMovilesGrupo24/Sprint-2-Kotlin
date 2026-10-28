# Android physical-device validation against Render (debug APK over USB)

Run marker: `phone-smoke-20261027t1617`. Evidence folder:
[`evidence/render-phone/phone-smoke-20261027t1617/`](evidence/render-phone/phone-smoke-20261027t1617/).

**Result: the installed CampusMeal app talked directly to the deployed Render backend over the
phone's cellular connection. 13 PASS, 0 FAIL, 0 BLOCKED, 2 NOT RUN** (section 4). One cold-start
timeout was observed and recovered (CHK-02).

This is evidence of physical-device integration for one debug build. It does not establish
release signing or Firebase-invitation installs, every Sprint 3 strategy, every failure scenario,
or universal session/outbox correctness.

## 1. Run, device and build

| Item | Value |
|---|---|
| Run window | 2026-10-27 16:13:45–16:28:04 UTC = 11:13:45–11:28:04 America/Bogota |
| Device | Samsung SM-S948U1, Android 17 (API 37), arm64-v8a; alias **usb-phone-1** (serial, IMEI and subscriber ids omitted). The phone has a second user profile (user 150); every command targeted user 0 only. |
| Compatibility | `minSdk 24`, `targetSdk 36`, `compileSdk 37`. The device API 37 is supported. |
| Repository | Local checkout `Sprint-2` (remote `ConstruccionAplicacionesMovilesGrupo24/Sprint-2`, not `Sprint-2-Kotlin`), branch `sprint3-issue-02` |
| Source | `b99786bb1eb2d4972fb06b5ab6c4ae637a30cb84` ("sprint3 issue 02", committed by the team at 11:10:44 Bogotá, before the build started). Tree **clean**. The report and evidence files are the only later additions (untracked). |
| Toolchain | Gradle wrapper 9.5.0, AGP 9.3.2, Kotlin 2.4.20, JDK 21.0.8 (Temurin) running the build; Java/Kotlin target 17 as configured. No toolchain or dependency changes. |
| Build | `.\gradlew.bat "-Pcampusmeal.apiBaseUrl=https://campusmeal-api.onrender.com/api/v1/" :app:assembleDebug`. The override applied to this process only: `android/local.properties` was **not** edited, and `CAMPUSMEAL_API_BASE_URL` was absent from the environment (checked, and removed from the build process just in case). |
| Effective URL | Generated `app/build/generated/source/buildConfig/debug/com/campusmeal/android/BuildConfig.java`: `API_BASE_URL = "https://campusmeal-api.onrender.com/api/v1/"`. It is wired through `DefaultAppContainer.networkConfig` → Retrofit `baseUrl`. Every logged request used this host. |
| Variant / package | `debug`; `com.campusmeal.android`, versionName `0.1.0`, versionCode `1` (aapt2 badging) |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk`, 17,162,566 bytes, SHA-256 `79a0253775d387d983b52f093a4078dc3438136ebfd67cf0cb31c6d437ab771f`; signer "CN=Android Debug", cert SHA-256 `12f48ec5…faa7` (existing local debug key) |
| Install | `adb install -r --user 0` → `Success` (preserving update over the existing 0.1.0 debug install from 2026-10-13; same signature, no uninstall, no `pm clear`). The installed `base.apk` SHA-256, computed **on the device**, equals the built APK hash. No APK was pulled. |
| TLS / cleartext | Main `network_security_config`: cleartext off. The debug override allows cleartext only to `10.0.2.2`/`localhost`/`127.0.0.1`, which this run did not use. No trust-all or hostname bypass. |
| Logging | Debug OkHttp `HttpLoggingInterceptor` at `HEADERS` (never BODY), with `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` redacted by the app. Logcat was always filtered by the CampusMeal PID, and the filter was updated after each restart. |

Only one APK was used; no fix was needed, so there was no rebuild.

## 2. Network path

| Check | Evidence | Status |
|---|---|---|
| Wi-Fi off, mobile data on, USB tethering off, VPN disconnected | Requested from the user, who confirmed the actions. Before the change the default network was Wi-Fi with an active VPN. | user-reported |
| Default transport | `dumpsys connectivity` at 16:28 UTC: `Active default network: 136`, `Transports: CELLULAR`, `VALIDATED`, `NOT_VPN`; `settings get global wifi_on` = `0` (also 0 right after the user's change at ~16:12) | independently observed |
| App traffic by transport | `dumpsys netstats detail`, CampusMeal uid 10337, bucket starting 16:00 UTC (covers the whole run): traffic only on `type=0` (MOBILE), `ratType=13` (LTE), `transports={0}` (cellular), `defaultNetwork=true`; about 27 KB received / 12 KB sent; **no Wi-Fi bucket** for the app in that hour | independently observed |
| No local-server dependency | `adb reverse --list` and `adb forward --list` were empty before and after; no proxy; no host NestJS used. Every request went to `campusmeal-api.onrender.com`. | observed |

Host-side checks were not used as evidence of app behavior.

## 3. Analytics and data side effects (from source, then observed)

- `DecisionViewModel.compare` automatically sends one `RECOMMENDATION_IMPRESSION` to
  `POST /analytics/events` after showing a comparison. A selection is sent only when the user taps
  "Choose …". This run made **one comparison, observed one impression (`202`), and no selection**.
- The Android app sends no BQ6 usage events.
- "Consume" calls `DELETE /inventory/{id}` (backend soft delete).

## 4. Flows

Times are Bogotá (device clock) on 2026-10-27; UTC = +5 h. HTTP lines come from
[`okhttp-excerpt.log`](evidence/render-phone/phone-smoke-20261027t1617/okhttp-excerpt.log).

| ID | Flow | Steps | Expected | Observed UI / HTTP | Status | Evidence |
|---|---|---|---|---|---|---|
| CHK-01 | Launch | `am start --user 0 -n com.campusmeal.android/.app.MainActivity` after the update | opens without crash | `Status: ok`, cold launch 379 ms, PID 30838; no `AndroidRuntime`/FATAL lines | PASS | — |
| CHK-02 | Cold start + existing session | The app restored a session stored before this run (issued by the team's earlier non-Render backend) and called the API | first request may be slow; honest error then recovery | 11:13:58 `GET /auth/me` → **`SocketTimeoutException` after 30 s** (Render waking; the app's read timeout is 30 s). The UI showed "Confirming your account … Try again / Log out". One tap on **Try again** at 11:16:13 → `GET /auth/me` **401** (251 ms) → the interceptor tried `POST /auth/refresh` **401** → the app ended that session and showed "Your session expired". This is the normal handling for credentials Render does not recognize; the run did not clear app data. | PASS (timeout recorded) | e01, e02 |
| CHK-03 | Registration / authentication | "Create account" UI. The agent typed the synthetic name `Phone Smoke 20261027t1617` and email `phone-smoke-20261027t1617@campusmeal.local`; the **user typed the password** (never seen or logged by the agent) and submitted | `201`, then signed in as that account | 11:19:21 `POST /auth/register` **201** (1789 ms) → `GET /auth/me` **200**. Home: "Good morning, Phone Smoke". | PASS | e03 |
| CHK-04 | Home / Profile | Open Profile | fresh authenticated request, correct account | `GET /auth/me` **200** at 11:19:46. Profile shows the name, the email (lowercase, same as the normalized contract form) and "Student account" (USER). | PASS | e04 |
| CHK-05 | Inventory read (empty) | Inventory tab | valid empty state from a fresh read | `GET /inventory/expiring?withinDays=3` **200** (153 ms); "Your inventory is empty" | PASS | — |
| CHK-06 | Inventory create | Add item `phone-smoke-20261027t1617 milk`, 1 L, expires 2026-10-28 (tomorrow in Bogotá) | `201` + fresh read shows it | 11:21:31 `POST /inventory` **201** → `GET …/expiring` **200**; list: "1 L · Expires 2026-10-28 · In 1 day · Synced just now". Item id `d5145208-4103-4df6-95de-6bfc93000aad`. A first attempt closed the dialog before Save (agent tap outside the dialog); no request was sent. | PASS | e06 |
| CHK-07 | Inventory edit | Edit quantity 1 → 2 | `PATCH` 200 + fresh read | 11:22:33 `PATCH /inventory/d5145208-…` **200** → `GET …/expiring` **200**; list "2 L" | PASS | e07 |
| CHK-08 | Meal comparison | Context: Uniandes **chosen manually**, 60 min, budget 25,000 COP, no dietary filter → compare | `200`, ranked alternatives + explanation | 11:24:11 `POST /meal-decisions/compare` **200** (951 ms), then `POST /analytics/events` **202** (automatic impression). UI: "Cooking is recommended because it is the cheapest option that fits your available time."; #1 COOK score 73, 20 min, $0, uses the smoke milk (expires tomorrow); #2 WALK score 51, 17 min, $14,500, The Garden · 1 min walk. No ORDER alternative. No "Choose" tapped. | PASS | e08 |
| CHK-09 | Restaurant search | Home → Find restaurants (same manual context) | `200`, real results or valid empty/provider state | 11:24:59 `POST /restaurants/search` **200** (550 ms): "3 restaurants match your context". The Garden (walk 1 min, total 17 min, from $14,500), Green Bowl (walk 4 min, total 23 min, from $17,000), Quinoa Corner. No "Route information is limited" banner, which the app shows for `PARTIAL`/`UNAVAILABLE`, so the status was `AVAILABLE` (inferred from the UI). | PASS | e10 |
| CHK-10 | Restaurant detail | View restaurant → The Garden | `200`, content loaded | 11:25:39 `GET /restaurants/4ae6f2a7-…` **200** (199 ms): address, 2 meals with prices. Walk/Total "Unavailable" plus "Limited route information". Expected: the detail endpoint has no origin and returns `UNAVAILABLE`. | PASS | e11 |
| CHK-11 | Reopen | `am force-stop --user 0 com.campusmeal.android`, start again | session restored + fresh request | New PID 12661, cold launch 355 ms; no login prompt; 11:26:01 `GET /auth/me` **200** and `GET …/expiring` **200**; Home shows the account and the item (2 L). The meal context was not restored (kept in memory by design). | PASS | — |
| CHK-12 | Inventory consume (cleanup) | Inventory → Consume | `DELETE` 204 + fresh read empty | 11:26:53 `DELETE /inventory/d5145208-…` **204** → `GET …/expiring` **200**; "Item marked as consumed", then "Your inventory is empty" | PASS | e12 |
| CHK-13 | Logout | Profile → Log out; then force-stop + reopen | backend session revoked; local session cleared | 11:27:27 `POST /auth/logout` **204**; login screen. After the reopen (PID 14636) the app stays on "Welcome back" and sends **no** authenticated request. The access token issued earlier stays valid on the server until its 15-minute expiry (stateless, per the backend contract); the app no longer holds it in its active session. | PASS | e13 |
| CHK-14 | Optional offline/reconnect | — | — | Not run, to avoid another network-toggle round with the user | NOT RUN | — |
| CHK-15 | Second inventory item | — | — | Not needed; one item covered create/edit/consume | NOT RUN | — |

### Walking-time provider

The app's Uniandes coordinate (`MealLocation.kt`: 4.6025, −74.0653) is the backend contract's
example origin. For Green Bowl the app showed **4 min**, while the backend's `deterministic`
fallback formula gives 1 min from that origin. The value therefore did not come from the
deterministic fallback, which is consistent with Valhalla. The outbound Valhalla call itself was
not observed, so the provider is **indirectly evidenced, not directly verified**.

## 5. Findings (not deployment defects, no change made)

1. **Cold start vs. read timeout.** The first request after Render's spin-down failed at the
   app's 30 s read timeout; Render can take about a minute to wake. The UI handled it with a clear
   retry screen. The team may want to tell users to retry after a moment, or revisit the timeout
   or retry policy in a future issue.
2. **Inventory screen only shows the next 3 days.** `InventoryApi` exposes only
   `GET inventory/expiring?withinDays=3`; there is no `GET /inventory` call. An item expiring in
   more than 3 days would not appear, and the screen would say "Your inventory is empty". Found in
   source; not exercised on the phone. Worth an issue if the screen is meant to list the full
   inventory.
3. **Detail view shows "Unavailable" walking time** even right after a search that had one. This
   matches the backend contract (the detail request has no origin); it is a UX choice, not a
   failure.
4. Debug builds log full request URLs, including inventory and restaurant UUIDs, at HEADERS
   level. Tokens and cookies are redacted. Release builds have no logging interceptor.

## 6. Test data and residual records

| Record | Cleanup | Residual |
|---|---|---|
| Account `phone-smoke-20261027t1617@campusmeal.local` (USER) | Logged out (session revoked). The password exists only with the user; there is no public account-deletion endpoint. | **Remains** in Neon |
| Inventory item `d5145208-4103-4df6-95de-6bfc93000aad` | Consumed through the UI (`DELETE` 204), confirmed by a fresh read | Inactive row remains |
| 1 recommendation run (from CHK-08) | No public delete endpoint | **Remains** |
| 1 `RECOMMENDATION_IMPRESSION` analytics event (`202`) | Cannot be deleted through the API | **Remains**: BQ8 for 2026-10 will count one COOK-explanation impression with no selection from this test account. It is a test interaction, not real student adoption. |
| Previously stored session on the phone (from the team's earlier backend) | Ended by the app itself after Render answered `401` (CHK-02) | Old local session no longer active; other app data untouched |

The recommendation id was not logged (bodies are never logged), so it is identified only by the
account and the time 16:24:12 UTC. No external order, payment, email or message was initiated.
Demo accounts and shared demo data were not used or modified.

## 7. Manual actions taken by the user

1. Turned Wi-Fi off, kept mobile data on, kept USB tethering off, disconnected the VPN.
2. Typed the registration password (both fields), accepted the terms and tapped "Create account".

Restore the phone's previous Wi-Fi/VPN settings when convenient.

## 8. Not established by this run

- Release-signed builds, Firebase App Distribution invitations, and release HTTPS configuration
  (`campusmeal.releaseApiBaseUrl`).
- Offline cache and reconnect behavior, outbox and process-death recovery beyond one force-stop
  reopen, WorkManager, and switching between accounts on the same phone.
- Access-token expiry and automatic refresh of a **valid** session through the Android
  interceptor. CHK-02 only showed the refresh attempt for a session Render did not recognize.
- Direct verification of the Valhalla call.
- Which backend commit is deployed on Render (no build metadata exposed).
- iOS.

HTTP-level backend checks are recorded separately in the backend repository
(`CampusMealBack/docs/DEPLOYMENT_VALIDATION_RENDER_NEON.md`, 48 requests, 47 PASS + 1 runner
expectation failure). That report was not modified and is not counted as evidence here.
