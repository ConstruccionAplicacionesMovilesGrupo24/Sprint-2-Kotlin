# Issue #6 — Backend-facing external route integration (Juan Pablo)

This document covers **Person A (Juan Pablo)'s part of Issue #6**: the restaurant contracts, domain
models, `RestaurantApi` and `RestaurantRepository`. Person B (Natalia) owns
`RestaurantResultsViewModel` and the Results, Detail, No Results and Routes Unavailable screens.

```text
Android → CampusMeal NestJS → external route provider → NestJS → Android
```

Android never calls the route provider and holds no provider key. It only relays what NestJS says,
including the provider's status.

## Files

| File | Main types | Responsibility |
| --- | --- | --- |
| `feature/restaurants/domain/RestaurantModels.kt` | `RestaurantSummary`, `RestaurantResults`, `RestaurantDetail`, `RouteProviderStatus`, results and errors | Domain models; no provider-specific types. |
| `feature/restaurants/data/RestaurantApi.kt` | `RestaurantApi`, response DTOs | `POST restaurants/search`, `GET restaurants/{id}`. |
| `feature/restaurants/data/RestaurantMappers.kt` | `toResultsOrNull()`, `toDetailOrNull()` | Validation and mapping, including walking and total time. |
| `feature/restaurants/data/RestaurantRepository.kt` | `RestaurantRepository`, `NetworkRestaurantRepository` | Authenticated calls, error mapping, cache of the last search. |
| `app/AppContainer.kt` (modified) | `restaurantRepository` | Wiring. |

**Request DTO reused, not redefined.** `RestaurantSearchRequestDto` (location, campus, time, budget,
dietary preferences, delivery, app-generated `requestedAt`) already exists from Issue #3, built by
`ContextViewModel`. `search()` takes it as-is, so the context the user validated is exactly what
NestJS receives.

## Response contract (provisional)

Search: `restaurants[]`, `lastUpdatedAt` (ISO-8601 UTC), `routeProviderStatus`
(`AVAILABLE` / `PARTIAL` / `UNAVAILABLE`). Each restaurant: `id`, `name`, `category`,
`openingStatus` (`OPEN` / `CLOSING_SOON` / `CLOSED`), `walkingMinutes`, `estimatedTotalMinutes`,
`minimumMealPrice`, `dietaryTags`, `averageRating`, `recommendationReason`.

Detail: `restaurant` (same shape), `address`, `meals[]` (`id`, `name`, `price`, `dietaryTags`),
`lastUpdatedAt`, `routeProviderStatus`.

## Mapping rules

- A restaurant missing a required field — id, name, category, a known opening status, or a
  non-negative minimum price — is dropped. The rest keep the backend order.
- **Walking and total time are nullable per restaurant**: null means the provider gave no estimate
  for it, and the restaurant still shows. Negative values become null.
- A rating outside 0–5, an unknown dietary tag or a blank reason are ignored, not fatal.
- **`routeProviderStatus` is preserved** when it is one of the three known values. When it is
  missing or unknown it is derived from the estimates present (all → `AVAILABLE`, some →
  `PARTIAL`, none → `UNAVAILABLE`), so the app never claims routes it does not have.
- Restaurants returned but none valid → `InvalidResponse`. An empty list is a valid "no results".
- `lastUpdatedAt` falls back to the fetch time when absent.

## Repository behaviour

| Situation | `search()` result |
| --- | --- |
| 2xx | `Success(NETWORK)`, and the response is cached |
| Connectivity failure or 5xx, cache present | `Success(CACHE)` with the cached last-updated time |
| Connectivity failure or 5xx, no cache | `Failure(BackendUnavailable)` |
| No session, or 401 | `Unauthorized`; the cache is cleared |
| Other 4xx | `Failure(Http(code))` |
| Undecodable or all restaurants invalid | `Failure(InvalidResponse)`; the cache is kept |

Retry is simply calling `search()` again with the same request. `getRestaurant()` has the same error
mapping without a cache.

**Cache.** The last successful search is stored as one JSON value in the preferences DataStore, not
in Room: no table, DAO or migration is needed for a single value. Only the backend's answer is
stored — never the request — so no coordinates are persisted.

## Mapping to Natalia's UI states

| UI state | From |
| --- | --- |
| `Loading` | ViewModel, while `search()` runs |
| `Content` | `Success`, non-empty, status `AVAILABLE` |
| `NoResults` | `Success` with an empty list |
| `RoutesUnavailableWithPartialResults` | `Success` with status `PARTIAL` or `UNAVAILABLE` |
| `OfflineWithCache` | `Success` with `source == CACHE`; show `lastUpdatedAtEpochMillis` |
| `Error` | `Failure` |
| `Unauthorized` | `Unauthorized` |

## Security

- No route-provider key, SDK or URL in Android; every call goes to the CampusMeal base URL.
- DTOs are CampusMeal's own; provider-specific models never reach the app.
- Requests use the CampusMeal session (`Authorization: Bearer`), separate from the provider's
  credentials, which stay in NestJS. A 401 goes through the session-expiry flow from Issue #5.
- The cache stores no coordinates, and this layer emits no analytics.

## Validation

No automated tests (prototype rules), so the MockWebServer tests in the checklist are not included.
`assembleDebug` and `lintDebug` pass with 0 errors.

**Not exercised at runtime yet**: no screen calls `restaurantRepository` until Natalia's
`RestaurantResultsViewModel` exists, and `SetContextScreen` currently hands the built request to the
Restaurants placeholder. Verify it by hand during integration against a local stub of the two
endpoints, including a `PARTIAL` answer, a 5xx after a successful search (cache) and a 401.

## Pending

- Confirm the provisional contract with the backend team.
- `DietaryPreference` is still defined twice, in `feature/context` (used here) and in
  `feature/decision`; unify them during integration.
