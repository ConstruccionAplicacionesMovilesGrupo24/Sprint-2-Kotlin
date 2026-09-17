# BQ2 Inventory Data Implementation

## Purpose and scope

BQ2 (type 2 business question) asks:

> Which items in the current user's inventory will expire within the next three days, and in what order should they be consumed?

The CampusMeal NestJS API computes expiration, filters the items and sets their priority. The Android app requests that result, keeps the last valid answer for offline use and hands it to the presentation layer. It never recomputes priority and never re-sorts the list.

This document covers **Person A (Juan Pablo)'s part of Issue #2**: the data layer and backend integration.
- **Remote:** DTOs and the Retrofit API.
- **Domain:** models and the repository interface.
- **Data:** mappers, the Room entity, DAO, migration and cache, and the offline-capable repository.
- **Other:** authenticated requests and `AppContainer` wiring.

The `InventoryViewModel`, and the Inventory and Empty Inventory screens, belong to Person B (Natalia) and are not part of this change.

## Backend contract

No backend contract is documented in this repository yet, so the app implements this **provisional** contract. It lives only in `InventoryDtos.kt` and `InventoryMappers.kt`, so the final contract can be adopted there.

```http
GET /api/v1/inventory/expiring?withinDays=3
Authorization: Bearer <access-token>
```

```json
{
  "items": [
    {
      "id": "item-001",
      "name": "Milk",
      "quantity": 1.0,
      "unit": "L",
      "expirationDate": "2026-09-16",
      "remainingDays": 1,
      "active": true
    }
  ]
}
```

- **`expirationDate`:** ISO `YYYY-MM-DD`.
- **`remainingDays`:** computed by the backend; `0` means the item expires today.
- **Array order:** the priority set by the backend.
- **`items`:** may be empty.
- **Unknown fields:** ignored.
- **Required fields:** all seven. If any is missing, decoding fails and the response is invalid.

The retrofit path is relative (`inventory/expiring`) because the configured base URL already ends in `/api/v1/`.

## Data flow

1. **Session check.** `OfflineFirstInventoryRepository.getExpiringInventory()` asks `AuthorizationHeaderProvider` for `Bearer <access-token>`. With no session it returns `Unauthorized` and does not call the backend.
2. **Request.** `InventoryApi.getExpiringInventory(authorization, withinDays = 3)` runs inside the shared `apiCall {}`, which turns Retrofit and OkHttp exceptions into `ApiResult`.
3. **Success.** The mappers validate the response and keep only the BQ2 items, in backend order. `RoomInventoryCache.replace()` then stores the items and the sync timestamp in one Room transaction. The repository returns `Success(source = NETWORK)`.
4. **Connectivity failure or HTTP 5xx.** `RoomInventoryCache.read()` returns the last stored answer, which becomes `Success(source = CACHE)` with the stored timestamp. If no sync was ever stored, the result is `Failure(BackendUnavailable)`.
5. **HTTP 401.** The cache is cleared and the repository returns `Unauthorized`.
6. **Other HTTP errors or an invalid body.** The repository returns a typed `Failure` and leaves the cache untouched.

## Main components

Paths are relative to `app/src/main/java/com/campusmeal/android/` unless stated otherwise.

| File | Main class or function | Responsibility | Relation to Issue #2 |
| --- | --- | --- | --- |
| `feature/inventory/data/remote/InventoryDtos.kt` | `ExpiringInventoryResponseDto`, `InventoryItemDto` | Serializable models of the provisional contract. All fields are required. | Response DTOs |
| `feature/inventory/data/remote/InventoryApi.kt` | `InventoryApi.getExpiringInventory()` | Retrofit `GET inventory/expiring` with an `Authorization` header and `withinDays` (default 3). | InventoryApi, authenticated request |
| `feature/inventory/domain/model/InventoryItem.kt` | `InventoryItem` | Domain item: id, name, quantity, unit, ISO expiration date, remaining days. No Retrofit or Room types. | Domain model |
| `feature/inventory/domain/model/ExpiringInventoryResult.kt` | `ExpiringInventoryResult`, `InventoryDataSource`, `InventoryError`, `EXPIRING_WITHIN_DAYS` | Repository outcome: `Success` with items, source and timestamp; `Unauthorized`; or `Failure` with a typed error. | Results for the presentation layer |
| `feature/inventory/domain/repository/InventoryRepository.kt` | `InventoryRepository` | `getExpiringInventory()` and `clearCache()`. The ViewModel depends on this interface. | InventoryRepository |
| `feature/inventory/data/mapper/InventoryMappers.kt` | `toExpiringItemsOrNull()`, `toDomainOrNull()`, `toEntity()`, `toEntities()`, `toDomain()` | Validation and filtering of the response; conversion between DTO, domain and entity; priority index. | Mapping |
| `feature/inventory/data/local/ExpiringInventoryEntity.kt` | `ExpiringInventoryEntity` | Room row for one cached item. Primary key `itemId`; unique `priorityIndex`. | Room entity |
| `feature/inventory/data/local/ExpiringInventoryDao.kt` | `getItems()`, `insertItems()`, `deleteItems()`, `replaceItems()` | Reads items ordered by `priorityIndex` and replaces the previous result in a transaction. | DAO |
| `feature/inventory/data/local/ExpiringInventoryCache.kt` | `ExpiringInventoryCache`, `CachedExpiringInventory` | Cache interface the repository depends on, so the storage backend stays replaceable. | Offline cache |
| `feature/inventory/data/local/RoomInventoryCache.kt` | `RoomInventoryCache` (`read`, `replace`, `clear`) | Stores the items and the `cache_metadata` timestamp (key `inventory.expiring`) atomically with `withTransaction`. | Last successful result |
| `feature/inventory/data/repository/OfflineFirstInventoryRepository.kt` | `OfflineFirstInventoryRepository` | Session check, request, validation, caching, fallback and HTTP 401 handling. Injectable `currentTimeMillis`. | Repository |
| `core/session/AuthorizationHeaderProvider.kt` | `AuthorizationHeaderProvider`, `SessionAuthorizationHeaderProvider` | Builds `Bearer <access-token>` from `SessionStorage`. Inventory never sees the refresh token. | Authenticated request |
| `core/database/CacheMetadata.kt` (modified) | `CacheMetadataDao.get()`, `CacheMetadataDao.delete()` | New suspend lookup and delete of one metadata key. | Sync timestamp |
| `core/database/DatabaseMigrations.kt` | `DatabaseMigrations.MIGRATION_1_2`, `ALL` | Explicit migration from version 1 to 2. | Database migration |
| `core/database/CampusMealDatabase.kt` (modified) | `CampusMealDatabase`, `create()` | Version 2; registers `ExpiringInventoryEntity`, `expiringInventoryDao()` and the migrations. | Database migration |
| `app/AppContainer.kt` (modified) | `authorizationHeaderProvider`, `inventoryRepository` | Manual wiring of the provider, `InventoryApi`, `RoomInventoryCache` and the repository. | Shared integration point |
| `app/schemas/com.campusmeal.android.core.database.CampusMealDatabase/2.json` | Exported schema | Version 2 schema generated by KSP. `1.json` is kept. | Database migration |

## Mapping and validation

`ExpiringInventoryResponseDto.toExpiringItemsOrNull()` applies these rules, in this order:

1. **Contract check, all or nothing.** Each item must have:
   - a non-blank `id`, `name` and `unit`;
   - a finite `quantity` that is `>= 0`;
   - an `expirationDate` that is a real ISO calendar date (`2026-02-30` and `16/09/2026` are rejected).

   Ids must also be unique. If any item fails, the function returns `null` and the repository reports `InvalidResponse`. A malformed item is never silently dropped.
2. **BQ2 filter.** Items with `active = false` are excluded, and so are items whose `remainingDays` is outside `0..3`.
3. **Order.** The remaining items keep their position in the backend array. Nothing is sorted and `remainingDays` is never recomputed.

Other rules:
- **Dates:** `expirationDate` stays a validated `String`. `java.time` needs API 26, and core library desugaring is not configured for `minSdk 24`.
- **Priority index:** `List<InventoryItem>.toEntities()` sets `priorityIndex` to the position in the filtered list. Room reads items back with `ORDER BY priorityIndex`.

## Offline cache behavior

- **Success.** Items and timestamp (`currentTimeMillis()` at that moment) replace the previous cache in one transaction. The result is `Success(items, NETWORK, timestamp)`.
- **Empty success.** Also replaces the cache. The metadata row records that a sync happened even though the item table is empty.
- **Connectivity failure (`IOException`) or HTTP 5xx.**
  - If a metadata row exists, the result is `Success(cachedItems, CACHE, storedTimestamp)`, even when the cached list is empty.
  - Otherwise it is `Failure(BackendUnavailable(httpCode))`, where `httpCode` is null for connectivity failures.
- **Invalid response or other 4xx.** Typed `Failure`; the previous cache is kept but not returned.
- **Clearing.** `RoomInventoryCache.clear()` deletes the items and only the `inventory.expiring` metadata row; other caches' metadata is kept.
- **User scope.** The cache is **not** scoped per user. The shared session mechanism must call `InventoryRepository.clearCache()` on sign-out.

## Authentication behavior

- **Header.** `SessionAuthorizationHeaderProvider` reads the current `SessionTokens` and returns `Bearer <access-token>`. With no session, or a blank access token, it returns `null`.
- **Missing session.** The repository returns `Unauthorized` without calling the backend.
- **HTTP 401.** `apiCall` maps it to `ApiResult.Unauthorized`. The repository clears the cache, because it may belong to an invalid session, and returns `Unauthorized`. There is no token refresh and no login flow in this issue.
- **Refresh token.** The inventory package receives only the header provider, never `SessionStorage`.
- **Token exposure:**
  - The header value is never logged, and debug HTTP logging redacts `Authorization`.
  - HTTP error bodies are not kept.

## Database changes

- **Version:** 1 → 2 (`CampusMealDatabase`, `exportSchema = true`).
- **New table:** `expiring_inventory_items`:
  - columns `itemId TEXT NOT NULL` (primary key), `name TEXT`, `quantity REAL`, `unit TEXT`, `expirationDate TEXT`, `remainingDays INTEGER` and `priorityIndex INTEGER`, all `NOT NULL`;
  - unique index `index_expiring_inventory_items_priorityIndex`.
- **Timestamp storage:** the existing `cache_metadata` table, which gains `get(cacheKey)` and `delete(cacheKey)`.
- **Migration:**
  - `DatabaseMigrations.MIGRATION_1_2` creates the table and index with the exact SQL from the generated schema, and keeps the existing metadata rows.
  - `CampusMealDatabase.create()` registers it through `addMigrations(*DatabaseMigrations.ALL)`.
  - No destructive fallback is configured.
- **Schemas:** KSP generated `app/schemas/.../2.json`; `1.json` is unchanged. Both exported schemas stay committed as the reviewable schema history for future migrations.

## Prototype validation

This project currently prioritizes rapid prototype development. Automated unit,
instrumented and Compose tests are not included. Validation is performed through:

- Successful debug compilation.
- Android lint.
- Manual execution of the main application flows on an emulator or device.

Manual checks worth running for this data layer: open the app with the backend
reachable (fresh data is cached), then with the backend stopped (the cached result
is served), and with an expired or missing session (`Unauthorized`, cache cleared).

## Integration contract for Natalia

`AppContainer.inventoryRepository` exposes `InventoryRepository`. Pass it to the future `InventoryViewModel` through its constructor. A suggested mapping to the shared `UiState`:

| Repository result | Suggested UI state |
| --- | --- |
| Call in progress | `Loading` |
| `Success(items, NETWORK, t)` with items | `Content` |
| `Success(emptyList(), NETWORK, t)` | `Empty` (Empty Inventory screen) |
| `Success(items, CACHE, t)` | `OfflineWithCache(items, lastUpdatedEpochMillis = t)`; an empty cached list may use the Empty design with an offline notice |
| `Unauthorized` | `Unauthorized`; also notify the shared session mechanism |
| `Failure(BackendUnavailable / Http / InvalidResponse)` | `Error` |

Each `InventoryItem` carries `name`, `quantity`, `unit`, `expirationDate` and `remainingDays`:
- **Sections:** `remainingDays == 0` is "today"; `1..3` is "within three days".
- **Urgency without color:** a text label built from `remainingDays`, for example "Expires today" or "2 days left".
- **Order:** keep the list order as received; do not re-sort.
- **Sync time:** `lastSyncedAtEpochMillis` gives the last synchronization time for both sources.

**"Later" section.** This endpoint returns only items within three days, so the Figma "later" section has no data source yet. Do not invent items and do not call an undocumented endpoint. It needs a separate, documented backend contract.

**Shared integration point.** `AppContainer` was created in Natalia's part of Issue #1. This change only adds `authorizationHeaderProvider` and `inventoryRepository`; every existing property is unchanged.

## Validation results

Run on 2026-09-15 from `android/` on Windows 11:
- **JDK:** the Gradle daemon runs on JDK 17 (`gradle/gradle-daemon-jvm.properties`); the launcher JVM is 21.
- **Android SDK:** `android-37.0` platform.
- **Device:** `Tusky_API_36` emulator.

| Command | Result |
| --- | --- |
| `./gradlew --version` | Exit 0. Gradle 9.5.0; daemon JVM "Compatible with Java 17". |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL. KSP generated `2.json`; its table and index SQL match `MIGRATION_1_2`. |
| `./gradlew :app:lintDebug` | BUILD SUCCESSFUL. 0 errors; the same 6 warnings as before this change, all about the pinned versions (newer AGP, Gradle, Compose BOM and OkHttp available; targetSdk 36). |

Static checks:
- **Build:** no `org.jetbrains.kotlin.android` or kapt in the build configuration; Room uses `ksp(...)`; no pinned version changed.
- **Libraries and database:** no Hilt or Firebase; no `fallbackToDestructiveMigration`.
- **Security:** no credentials; no logging calls in the new code; `Authorization` is still redacted in `ApiClientFactory`; no absolute `@GET("/…")` path.
- **Ownership:** Natalia's files (`MainActivity`, `CampusMealApp`, theme, navigation, `UiState`, manifest) are unmodified.

## Pending shared work

- **Natalia:**
  - `InventoryViewModel` and presentation models.
  - Inventory and Empty Inventory screens based on Figma, with the today and within-three-days sections, urgency indicators that don't rely on color, and the last sync time.
  - Consume, edit and add navigation actions.
  - A visual run on a device.
- **Backend:**
  - Confirm or replace the provisional contract.
  - Define the data source for the "later" section.
- **Shared session mechanism (later issue):**
  - React to `Unauthorized`.
  - Call `InventoryRepository.clearCache()` on sign-out.
  - Token refresh and Keystore-backed `SessionStorage`.
- **Git:**
  - This work depends on `core/network/ApiResult.kt`, which was added during the Issue #1 follow-up and is not committed yet. Commit that first, or together with this change.
  - Each person reviews the other's code before Issue #2 is closed.
