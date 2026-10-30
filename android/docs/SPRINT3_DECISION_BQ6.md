# Sprint 3 / S3-11: offline comparison history and the BQ6 report

Two parts, both by Daniel: the BQ5 comparison keeps working sensibly without a connection, and analyst accounts can read the new BQ6 report on Android.

## 1. BQ5 comparison history (local storage + connectivity)

| Situation | Behavior |
|---|---|
| Online, comparison succeeds | Shown as current. The backend response is saved **as received** in Room `comparison_history` (Room v7), with the coarse context (campus id, minutes, budget; no coordinates). Last 10 per account. |
| Offline or backend 5xx, a comparison is saved | Shown as **"Previous comparison"** with its date and the context it was made for. It is not a current recommendation: the alternatives **cannot be chosen** and no impression is recorded. "Compare again" retries. |
| Offline, nothing saved | Same error screen as before, with retry. |
| New comparison while offline | Never queued: each `POST meal-decisions/compare` creates a recommendation on the server, so it is only sent when the user asks again with a connection. |
| Back to Decide, or app reopened, within 5 minutes, same context | The same recommendation and the choice made on it are shown again (`RecentDecisionStore`, DataStore `decision.recent.{accountId}`: recommendation id, a context key without coordinates, time, and the selection event id). No new POST and no second impression. The selection status keeps following its outbox event. The context key uses the campus, so it also works for a device location on a campus (restored at the campus centre, see `BUG_CONTEXT_PERSISTENCE.md`). |
| After 5 minutes, or "Recalculate" | A new comparison is requested from the backend. "Recalculate" is shown next to "Compared …" on every current result. |

Android never recalculates scores or ranking: the saved response goes through the same mapper as a live one.

Selections made while a comparison was current still go to the S3-09 outbox and are delivered later (see `SPRINT3_EVENT_SYNC.md`).

## 2. Concurrency (DecisionViewModel)

- A new comparison cancels the one in flight (`compareJob`) and carries a request number, so a late answer never replaces a newer one.
- "Retry" is ignored while a comparison is loading, so it never duplicates the POST.
- A selection is ignored unless the shown result is current (`Content` / `PartialContent`), and a second tap while `PENDING` or `SENT` does nothing.
- Results of another session scope are dropped (`coordinator.isCurrent(scope)`).

## 3. BQ6 report for analysts (caching)

**BQ6:** "Which core CampusMeal features are used fewer than two times per month by at least 80% of monthly active users?"

- Entry: Profile -> **Analytics**, visible only when the stored role is `ANALYST`. That is presentation only; `GET analytics/feature-adoption` still answers 403 to a USER.
- Default month: the previous calendar month in Bogotá (the latest complete one). The last month viewed is saved per account in DataStore (`analytics.featureAdoption.month.{accountId}`).
- Cache: Room `feature_adoption_reports`, key **(account, month)**, TTL **15 min** (`CachePolicy.analyticsReport`), last 12 months per account.
  - Fresh copy -> shown without a request ("Saved …").
  - Stale or missing -> request; if the backend cannot be reached, the copy of **the same account and month** is shown as "Offline · saved … (may be outdated)".
  - Another month without a copy -> "No saved report for … on this device", never another month's figures.
  - 403 -> the account's cached reports are deleted and the screen says the report is analyst-only.
- Changing the month cancels the previous request and drops answers for a month no longer shown.
- The screen shows MAU, events, each feature's low-use users and percentage, and flags features at >= 80%. It also shows "Partial month" and "Includes synthetic demo data" when the backend says so.
- History and reports are personal data of the account: `AccountScopedStore`s registered in `AccountDataCleaner`, deleted on logout and when another account is activated.

## Database

`MIGRATION_6_7` adds `comparison_history` (+ index on `accountId, comparedAtEpochMillis`) and `feature_adoption_reports` (primary key `accountId, month`). Purely additive. Schema: `app/schemas/.../7.json`.

## Manual verification checklist

1. As `demo@campusmeal.local`, compare online, then turn on airplane mode and compare again: "Previous comparison" with the date, no "Choose" buttons.
2. Kill the app, reopen offline, open Decide: the previous comparison is still there.
3. Profile as demo: no Analytics section. As `analyst@campusmeal.local`: Analytics -> 2026-09 shows MEAL_COMPARISON 80% flagged, INVENTORY 70% not flagged, RECIPES 100% flagged (seed data, "Includes synthetic demo data").
4. Airplane mode: 2026-09 still shows ("Saved …" or "Offline · saved …"); 2025-01 shows "No saved report".
5. Log out: history and reports of the account are gone.
