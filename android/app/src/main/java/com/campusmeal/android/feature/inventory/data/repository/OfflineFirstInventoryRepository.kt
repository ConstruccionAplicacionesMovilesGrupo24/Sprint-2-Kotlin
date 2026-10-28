package com.campusmeal.android.feature.inventory.data.repository

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryCache
import com.campusmeal.android.feature.inventory.data.mapper.toExpiringItemsOrNull
import com.campusmeal.android.feature.inventory.data.remote.CreateInventoryItemRequestDto
import com.campusmeal.android.feature.inventory.data.remote.ExpiringInventoryResponseDto
import com.campusmeal.android.feature.inventory.data.remote.InventoryApi
import com.campusmeal.android.feature.inventory.data.remote.UpdateInventoryItemRequestDto
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryItemDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryWriteResult
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository

/**
 * Keeps BQ2 available offline. Each call asks the backend first and caches every successful
 * result, including an empty one, under the account of the caller's scope. The cached result is
 * returned only when the backend is unreachable (connectivity failure, HTTP 5xx, or a token refresh
 * that is temporarily unavailable). HTTP 401 never deletes the cache: the session lifecycle owns that
 * cleanup, and token refresh happens in SessionRefreshInterceptor before a 401 reaches this class.
 * Writes go straight to the backend and are not queued offline.
 */
class OfflineFirstInventoryRepository(
    private val api: InventoryApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val cache: ExpiringInventoryCache,
    private val coordinator: AccountSessionCoordinator,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : InventoryRepository {

    override suspend fun getExpiringInventory(scope: AccountSessionScope): ExpiringInventoryResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return ExpiringInventoryResult.Unauthorized

        return when (val response = apiCall { api.getExpiringInventory(authorization, EXPIRING_WITHIN_DAYS) }) {
            is ApiResult.Success -> cacheFreshResult(scope, response.data)
            ApiResult.Unauthorized -> ExpiringInventoryResult.Unauthorized
            is ApiResult.NetworkUnavailable -> cachedResultOrUnavailable(scope, httpCode = null)
            is ApiResult.HttpError ->
                if (response.code in 500..599) {
                    cachedResultOrUnavailable(scope, httpCode = response.code)
                } else {
                    ExpiringInventoryResult.Failure(InventoryError.Http(response.code))
                }
            is ApiResult.InvalidResponse -> ExpiringInventoryResult.Failure(InventoryError.InvalidResponse)
        }
    }

    override suspend fun addItem(scope: AccountSessionScope, draft: InventoryItemDraft): InventoryWriteResult =
        write(scope) { authorization ->
            apiCall {
                api.createItem(
                    authorization,
                    CreateInventoryItemRequestDto(draft.name, draft.quantity, draft.unit, draft.expirationDate),
                )
            }
        }

    override suspend fun updateItem(scope: AccountSessionScope, itemId: String, draft: InventoryItemDraft): InventoryWriteResult =
        write(scope) { authorization ->
            apiCall {
                api.updateItem(
                    authorization,
                    itemId,
                    UpdateInventoryItemRequestDto(draft.name, draft.quantity, draft.unit, draft.expirationDate),
                )
            }
        }

    override suspend fun consumeItem(scope: AccountSessionScope, itemId: String): InventoryWriteResult =
        write(scope) { authorization ->
            // Response<Unit> never throws for non-2xx, so map the status here.
            when (val response = apiCall { api.deactivateItem(authorization, itemId) }) {
                is ApiResult.Success -> when {
                    response.data.isSuccessful -> ApiResult.Success(Unit)
                    response.data.code() == 401 -> ApiResult.Unauthorized
                    else -> ApiResult.HttpError(response.data.code())
                }
                else -> response
            }
        }

    private suspend fun write(
        scope: AccountSessionScope,
        call: suspend (authorization: String) -> ApiResult<*>,
    ): InventoryWriteResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return InventoryWriteResult.Unauthorized
        return when (val response = call(authorization)) {
            is ApiResult.Success -> InventoryWriteResult.Success
            ApiResult.Unauthorized -> InventoryWriteResult.Unauthorized
            is ApiResult.NetworkUnavailable -> InventoryWriteResult.Failure(InventoryError.BackendUnavailable(null))
            is ApiResult.HttpError -> InventoryWriteResult.Failure(
                if (response.code in 500..599) InventoryError.BackendUnavailable(response.code) else InventoryError.Http(response.code),
            )
            is ApiResult.InvalidResponse -> InventoryWriteResult.Failure(InventoryError.InvalidResponse)
        }
    }

    /** Stores the result only if [scope] is still active; a late answer of an ended session is not cached. */
    private suspend fun cacheFreshResult(scope: AccountSessionScope, response: ExpiringInventoryResponseDto): ExpiringInventoryResult {
        val items = response.toExpiringItemsOrNull()
            ?: return ExpiringInventoryResult.Failure(InventoryError.InvalidResponse)
        val syncedAt = currentTimeMillis()
        coordinator.runIfCurrent(scope) { cache.replace(scope.accountId, items, syncedAt) }
        return ExpiringInventoryResult.Success(items, InventoryDataSource.NETWORK, syncedAt)
    }

    private suspend fun cachedResultOrUnavailable(scope: AccountSessionScope, httpCode: Int?): ExpiringInventoryResult {
        val cached = cache.read(scope.accountId)
            ?: return ExpiringInventoryResult.Failure(InventoryError.BackendUnavailable(httpCode))
        return ExpiringInventoryResult.Success(cached.items, InventoryDataSource.CACHE, cached.lastSyncedAtEpochMillis)
    }
}

