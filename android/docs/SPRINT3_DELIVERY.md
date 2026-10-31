# Sprint 3 Delivery

## 1. Delivered features

| Feature | Sprint origin | BQ | Owner | Main data source |
|---|---|---|---|---|
| Inventory | Sprint 2 + Sprint 3 improvements | BQ2 | Juan Pablo | Backend inventory API + Room |
| Recipes | Sprint 3 | BQ3 | Juan Pablo | Backend recipe catalog + Room + inventory snapshot |
| Restaurant search and detail | Sprint 2 + Sprint 3 improvements | BQ4 | Natalia | Backend restaurant API + Room cache |
| Meal decision | Sprint 2 + Sprint 3 improvements | BQ5 | Daniel | Backend comparison API + Room comparison history |
| Feature adoption analytics | Sprint 3 | BQ6 | Daniel | Backend analytics usage events |
| Explanation selection analytics | Sprint 2 / analytics | BQ8 | Daniel | Backend recommendation analytics |
| Zone demand analytics | Sprint 3 | BQ9 | Natalia | Backend zone-demand aggregation + Room cache |

---

## 2. Business Questions

### BQ2
- Existing Sprint 2 business question.
- Feature: inventory and expiring items.
- Owner: Juan Pablo.

### BQ3
- Original question: which recipes satisfy the current user's available ingredients, dietary preferences and maximum preparation time
- Owner: Juan Pablo.
- Rationale notes:
  - Uses the user's current inventory.
  - Combines ingredient availability, dietary restrictions and preparation time.
  - Evaluated locally against the complete recipe catalog.

### BQ4
- Existing Sprint 2 business question.
- Feature: context-aware restaurant search.
- Owner: Natalia.

### BQ5
- Existing Sprint 2 business question.
- Feature: comparison between Cook, Walk and Order.
- Owner: Daniel.

### BQ6
- Original wording:
  "Which core CampusMeal features are used fewer than two times per month by at least 80% of monthly active users?"
- Type: 3.
- Owner: Daniel.
- Rationale notes:
  - Measures low adoption of core features.
  - Uses monthly active users as denominator.
  - Includes users with zero uses of a feature.
  - Indicates partial months and synthetic data.

### BQ8
- Existing analytics BQ.
- Measures selection rate per recommendation explanation.
- Owner: Daniel.

### BQ9
- Topic: aggregated restaurant demand by zone, category, price/budget and time.
- Type: 4.
- Owner: Natalia.
- Rationale notes:
  - Helps identify patterns of interest in restaurant options.
  - Uses searches, detail openings and recommendation selections.
  - Does not represent verified purchases.
  - Uses coarse zones instead of precise GPS.
  - Applies privacy suppression below five distinct users.

---

## 3. Strategy matrix

| Feature | Local storage | Caching | Concurrency | Eventual connectivity |
|---|---|---|---|---|
| Inventory | Room stores account inventory | Cached inventory snapshot | Repository/ViewModel work off main thread | Existing local data remains usable offline; writes synchronize according to implemented repository behavior |
| Recipes | Recipe catalog in Room; filters in DataStore | Catalog TTL 24h; inventory own freshness policy | `mapLatest`, cancellation and background evaluation | Cached catalog + inventory can be evaluated offline; missing local data produces explicit unavailable state |
| Restaurants | Search/detail responses in Room | Search TTL 5 min; detail TTL 30 min | Previous search job cancelled; late responses discarded | Same-query stale cache can be shown on network/5xx; unrelated cache is not reused |
| Meal decision | Last comparisons stored in Room | Recent recommendation retained briefly | Previous compare job cancelled; request version guards | Previous comparison may be shown offline but is clearly historical and cannot be selected as current |
| Analytics events | Durable Room outbox | N/A | One WorkManager drain per account | Events survive offline/process death and synchronize later with stable UUID |
| BQ6 | Room report cache + filter in DataStore | 15 min report TTL | Previous report request cancelled | Same account/month cache available offline; unrelated month not reused |
| BQ9 | Room report cache + filters in DataStore | 15 min report TTL | Previous request cancelled and late responses discarded | Same account/zone/date query can use local cache; unrelated query produces no-local-data state |

---

## 4. Connectivity and failure scenarios

| Flow | Online | Offline with cache | Offline without cache | Backend 5xx / interruption | Reconnection | Account change |
|---|---|---|---|---|---|---|
| Inventory | Backend data displayed and stored | Local inventory remains available | Explicit unavailable/empty state depending on stored data | Local data retained | Refreshes when connectivity returns | Account-scoped data is isolated/cleaned |
| Recipes | Catalog + inventory refreshed | Recipes evaluated from Room | Explicit missing local-data state | Stored catalog remains; error does not replace it | Refresh retries due data | Account filters/inventory isolated |
| Restaurant search | Network result saved under exact context | Same exact cached query may be shown | No unrelated result is reused | Exact stale cache may be used | Explicit retry/reload | Cache is account-scoped |
| Restaurant detail | Online detail saved | Cached detail available | Local-data unavailable state | Stored detail may remain visible | Retry network | Account-scoped |
| Meal decision | New comparison from backend | Previous comparison shown as historical | Retry required | Previous comparison retained | User explicitly compares again | History scoped to account |
| Analytics outbox | Events delivered | Events remain pending in Room | Event still stored locally | Retryable responses stay pending | WorkManager drains pending events | Events never sent with another account's credentials |
| BQ6 | Fresh report requested/cached | Matching month cache shown | No saved-report state | Existing cached report retained | Refresh attempted | Reports are account-scoped |
| BQ9 | Fresh report requested/cached | Exact zone/date cache shown | No saved-report state | Existing exact cached report retained | Refresh attempted | Reports are account-scoped |

---

## 5. Individual code contributions

### Juan Pablo

#### Local storage
Relevant implementation:
- Recipe catalog Room entities and DAO.
- Complete inventory persistence.
- Recipe filters in DataStore.

Relevant files:
- `feature/recipes/data/local/RecipeCatalogEntities.kt`
- `feature/recipes/data/local/RoomRecipeCatalogStore.kt`
- `feature/recipes/data/local/RecipeFilterStore.kt`

#### Caching
- Recipe catalog TTL: 24 hours.
- Inventory has its own freshness policy.
- Cached catalog is not replaced by failed responses.

#### Concurrency
- Recipe evaluation uses `mapLatest`.
- Expensive evaluation executes away from the main thread.
- New input cancels obsolete evaluation.

#### Eventual connectivity
- Cached recipe catalog and inventory support offline evaluation.
- Missing cache results in explicit local-data-unavailable state.

Supporting document:
- `android/docs/SPRINT3_BQ3.md`

---

### Natalia

#### Local storage
Relevant implementation:
- Restaurant search cache in Room.
- Restaurant detail cache in Room.
- BQ9 report responses in Room.
- BQ9 filters in DataStore.

Relevant files/classes:
- `RestaurantCache.kt`
- `RestaurantRepository.kt`
- `ZoneDemand.kt`
- `ZoneDemandRepository.kt`
- `ZoneDemandReportViewModel.kt`

#### Caching
Restaurant:
- Search cache TTL: 5 min.
- Detail cache TTL: 30 min.
- Search cache key includes account and contextual query values.

BQ9:
- Report cache TTL: 15 min.
- Key includes account, zone, date range and contract version.

#### Concurrency
Restaurant:
- Active search job is cancelled when a new search begins.
- Old responses are ignored if the query is no longer current.

BQ9:
- Active report request is cancelled when filters change.
- Result is discarded when its filters/session no longer match current state.

#### Eventual connectivity
Restaurant:
- Exact stale cache can be used on connectivity failure/5xx.
- Cache from another context is never presented as current.

BQ9:
- Exact same report query can be displayed from Room offline.
- A different zone/date combination without cache shows a no-local-data state.

Relevant commits:
- `e1711fb` — restaurant usage instrumentation.
- `9867298` — context-aware restaurant caching.
- `7c8783c` — BQ9 data layer.
- `f799911` — BQ9 analyst report UI/navigation.

---

### Daniel

#### Local storage
- Comparison history persisted in Room.
- Analytics event outbox persisted in Room.
- BQ6 reports persisted per account/month.

Relevant files/classes:
- `OutboxRepository`
- `OutboxSyncWorker`
- comparison history entities/repository
- feature adoption report repository

#### Caching
- BQ6 report cache TTL: 15 min.
- Same account/month cache is reused offline.

#### Concurrency
- Only one outbox drain per account.
- Comparison jobs are cancelled/replaced.
- Late comparison results cannot replace newer state.

#### Eventual connectivity
- Events are inserted into Room before attempting delivery.
- WorkManager retries when network becomes available.
- Stable `clientEventId` prevents duplicate effective ingestion.
- Previous comparison is available offline but never presented as a current recommendation.

Supporting docs:
- `android/docs/SPRINT3_EVENT_SYNC.md`
- `android/docs/SPRINT3_DECISION_BQ6.md`

---

## 6. Cross-member reviews

### Natalia reviews Juan Pablo — Inventory and Recipes

Rewrite these notes in your own words:

- Local storage:
  - Recipe catalog and inventory snapshots are persisted in Room.
  - Recipe filters use DataStore.
- Caching:
  - Catalog and inventory have independent freshness policies.
  - A failed refresh keeps valid previously stored data.
- Concurrency:
  - Recipe evaluation is cancelled when inputs change.
  - Evaluation occurs outside the UI thread.
- Eventual connectivity:
  - When catalog + inventory exist locally, recipe matching works offline.
  - When either is missing, the UI explicitly asks the user to connect rather than inventing results.
- Alternative:
  - Backend-side filtering could reduce client work, but would remove offline evaluation capability and introduce more network dependence.
- Limitation:
  - Results depend on the latest locally stored inventory/catalog versions.

### Juan Pablo reviews Daniel — Outbox and History

To be completed/reviewed by Juan Pablo.

Topics:
- Room outbox.
- Stable UUID.
- WorkManager unique work.
- Account isolation.
- Offline comparison history.
- Retry/backoff.
- Limitation: WorkManager execution timing is controlled by Android.

### Daniel reviews Natalia — Restaurant Search and BQ9

To be completed/reviewed by Daniel.

Topics:
- Room search/detail caching.
- Exact contextual cache keys.
- Cancellation of superseded requests.
- BQ9 per-query report cache.
- Suppressed cells.
- Offline exact-query behavior.
- Limitation: stale values may be displayed as stale/offline information.

---

## 7. BQ9 deployed verification

Backend:
`https://campusmeal-api.onrender.com/api/v1/`

Endpoint tested:
`GET /api/v1/analytics/zone-demand?from=2026-10-30&to=2026-10-30`

Observed result:
- HTTP 200
- `contractVersion = 1`
- `timezone = America/Bogota`
- `containsSyntheticData = true`
- `minDistinctUsersPerCell = 5`
- `suppressedCells = 8`
- interpretation: interest indicators, not verified purchases

### bogota-centro
- Searches: 16
- Detail openings: 16
- Selections: 14
- Distinct users: 6

### bogota-chapinero
- Searches: 14
- Detail openings: 14
- Selections: 14
- Distinct users: 5

Category verification:
- BOWLS: 5 selections
- SUSHI: 5 selections
- SALADS: suppressed

Other suppression:
- HIGH budget / price range: suppressed
- 18:00 time cell: suppressed

Synthetic data was intentionally generated for verification and must not be interpreted as observed student demand.

Backend evidence:
- `CampusMealBack/docs/SPRINT3_BQ9.md`

---


## 8. Synthetic and real data

### BQ6
- The backend demo dataset is explicitly marked synthetic.
- The report exposes whether synthetic records are included.
- Partial months are explicitly indicated.
- Results must not be presented as real observed production adoption.

### BQ9
- Verification uses a deterministic synthetic dataset.
- The report exposes `containsSyntheticData`.
- Cells with fewer than five distinct users are suppressed.
- Suppressed values are not equivalent to zero demand.
- Searches, detail openings and selections are interest indicators rather than purchases.

---

## 9. Supporting documentation

### Android
- `android/docs/SPRINT3_BQ3.md`
- `android/docs/SPRINT3_EVENT_SYNC.md`
- `android/docs/SPRINT3_DECISION_BQ6.md`
- `android/docs/SPRINT3_RELEASE.md`
- `android/docs/SPRINT3_BQ9.md`

### Backend
- `docs/SPRINT3_BQ9.md`
- deployment documentation for Render + Neon

### Deployment
Public backend:
`https://campusmeal-api.onrender.com/api/v1/`

Android application id:
`com.campusmeal.android`

Distributed version:
`versionCode 4`
`versionName 0.4.0`

See:
`android/docs/SPRINT3_RELEASE.md`
