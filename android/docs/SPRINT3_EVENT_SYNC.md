# Sprint 3 / S3-09: durable event outbox (Room + WorkManager)

Before this change, BQ8 events were retried only in memory (two retries, then lost) and there was no usage event. Now every analytics event is **written to Room first** and delivered by a background worker.

## Flow

1. A feature records an event under its `AccountSessionScope`: `RecommendationEventReporter.record(scope, ...)` (BQ8 impression/selection) or `OutboxRepository.enqueueUsage(scope, feature)` (BQ6). The result is `OutboxAdmission.Admitted` or `Rejected(reason)` (session changed, or more than 1,000 pending events).
2. `OutboxRepository` inserts a row in `outbox_events` (Room **v6**, `MIGRATION_5_6`) inside `AccountSessionCoordinator.runIfCurrent(scope)`, with a stable `clientEventId`, the `auth/me` account id and the request body. Inserting the same id again is a no-op.
3. `WorkManagerOutboxScheduler` enqueues unique work **per account**, named `AccountWork.outboxSyncName(accountId)` and tagged `AccountWork.tag(accountId)` (constraint: connected network, exponential backoff from 30 s, `APPEND_OR_REPLACE` so two drains of the same account never overlap and a trigger during a drain is not lost).
4. `OutboxSyncWorker` calls `OutboxRepository.drain(accountId)`: only while that account is the active session, oldest first, batches of 20, same `clientEventId` on every attempt.
5. Backend: `POST analytics/events` (BQ8) or `POST analytics/usage-events` (BQ6). Both deduplicate on the id, so a replay after a lost acknowledgement stores one row.

## Delivery rules

| Response | Result |
|---|---|
| 2xx (202) | row `SENT` (removed after 1 day) |
| network error, 5xx, 429, 408 | row stays `PENDING`, worker retries with backoff (stops after 8 attempts; re-scheduled at the next session start) |
| 401 | session problem (the HTTP layer already tried a refresh): row stays `PENDING`, no retry loop |
| other 4xx (400, 403, 404...) | row `FAILED` with the reason; skipped, visible in the UI, explicit retry possible |
| unreadable payload | `FAILED` |

## Accounts (S3-02 integration)

- Rows carry the `auth/me` account id from `AccountSessionScope`. Before every send the worker asks `AuthorizationHeaderProvider.getAuthorizationHeader(scope)`, which returns null once that scope ends, so nothing from account A is ever sent with account B's credentials.
- `OutboxRepository` is an `AccountLifecycleListener`. `onAccountActivated` (login, account change, restoration at startup) records one `SESSION_STARTED` per account per process and schedules that account's pending rows. Cancellation on logout/expiry/change is done by `AccountWorkCanceller` through the shared work name.
- The outbox is **not** an `AccountScopedStore`: logout does not delete pending rows; they are sent the next time the same account is active. Rows older than 80 days are dropped (the backend rejects events older than 90).
- Tokens and coordinates are never stored in the outbox.

## Where events are produced

| Event | Where |
|---|---|
| `SESSION_STARTED` | `OutboxRepository.onAccountActivated` (session coordinator); once per account per process |
| `FEATURE_USED` / `MEAL_COMPARISON` | `DecisionViewModel.compare()` once per comparison request |
| BQ8 impression / selection | `DecisionViewModel` through `RecommendationEventReporter` |
| `FEATURE_USED` / `RECIPES` | `RecipesViewModel` (S3-05) through `OutboxRecipeUsageRecorder`, which replaces `OutboxNotIntegratedUsageRecorder`; the visit id is the `clientEventId` |
| `INVENTORY`, `RESTAURANT_SEARCH`, `RESTAURANT_DETAIL` | **not wired yet**: owners of those screens call `container.outboxRepository.enqueueUsage(scope, UsageFeature.X)` once per explicit interaction (never on recomposition) |

## UI

`SelectionStatus` is `NONE`, `PENDING` ("Choice saved on this device. It will sync when you're online."), `SENT` or `FAILED` (Retry puts the same event back in the queue).

## Limits

- WorkManager decides when the work runs; "connected" does not guarantee the backend is reachable. After a force-stop the pending rows are re-scheduled when the app is opened and a session is active.
- Delivery is at-least-once; exactly-once effect comes from the backend deduplication.

## Manual verification checklist (device or emulator, backend running)

1. Sign in, open the comparison, turn on airplane mode, choose an alternative: "saved on this device".
2. Kill the app, reopen it offline: the row is still pending (see `outbox_events` in Database Inspector).
3. Turn the network on: within ~a minute the row becomes `SENT` and the screen shows "Choice saved."
4. Backend: one row in `analytics_events` and one in `usage_events` for that `client_event_id` (psql count).
5. Sign out and sign in as another account: the first account's rows are not sent.
