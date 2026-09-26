package com.campusmeal.android.feature.inventory.data.repository

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
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
 * result, including an empty one. The cached result is returned only when the backend is unreachable
 * (connectivity failure or HTTP 5xx). HTTP 401 clears the cache because it may belong to an invalid
 * session; token refresh happens in SessionRefreshInterceptor before a 401 reaches this class.
 * Writes go straight to the backend and are not queued offline.
 */
class OfflineFirstInventoryRepository(
    private val api: InventoryApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val cache: ExpiringInventoryCache,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : InventoryRepository {

    override suspend fun getExpiringInventory(): ExpiringInventoryResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
            ?: return ExpiringInventoryResult.Unauthorized

        return when (val response = apiCall { api.getExpiringInventory(authorization, EXPIRING_WITHIN_DAYS) }) {
            is ApiResult.Success -> cacheFreshResult(response.data)
            ApiResult.Unauthorized -> {
                cache.clear()
                ExpiringInventoryResult.Unauthorized
            }
            is ApiResult.NetworkUnavailable -> cachedResultOrUnavailable(httpCode = null)
            is ApiResult.HttpError ->
                if (response.code in 500..599) {
                    cachedResultOrUnavailable(httpCode = response.code)
                } else {
                    ExpiringInventoryResult.Failure(InventoryError.Http(response.code))
                }
            is ApiResult.InvalidResponse -> ExpiringInventoryResult.Failure(InventoryError.InvalidResponse)
        }
    }

    override suspend fun addItem(draft: InventoryItemDraft): InventoryWriteResult =
        write { authorization ->
            apiCall {
                api.createItem(
                    authorization,
                    CreateInventoryItemRequestDto(draft.name, draft.quantity, draft.unit, draft.expirationDate),
                )
            }
        }

    override suspend fun updateItem(itemId: String, draft: InventoryItemDraft): InventoryWriteResult =
        write { authorization ->
            apiCall {
                api.updateItem(
                    authorization,
                    itemId,
                    UpdateInventoryItemRequestDto(draft.name, draft.quantity, draft.unit, draft.expirationDate),
                )
            }
        }

    override suspend fun consumeItem(itemId: String): InventoryWriteResult =
        write { authorization ->
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

    private suspend fun write(call: suspend (authorization: String) -> ApiResult<*>): InventoryWriteResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
            ?: return InventoryWriteResult.Unauthorized
        return when (val response = call(authorization)) {
            is ApiResult.Success -> InventoryWriteResult.Success
            ApiResult.Unauthorized -> {
                cache.clear()
                InventoryWriteResult.Unauthorized
            }
            is ApiResult.NetworkUnavailable -> InventoryWriteResult.Failure(InventoryError.BackendUnavailable(null))
            is ApiResult.HttpError -> InventoryWriteResult.Failure(
                if (response.code in 500..599) InventoryError.BackendUnavailable(response.code) else InventoryError.Http(response.code),
            )
            is ApiResult.InvalidResponse -> InventoryWriteResult.Failure(InventoryError.InvalidResponse)
        }
    }

    override suspend fun clearCache() {
        cache.clear()
    }

    private suspend fun cacheFreshResult(response: ExpiringInventoryResponseDto): ExpiringInventoryResult {
        val items = response.toExpiringItemsOrNull()
            ?: return ExpiringInventoryResult.Failure(InventoryError.InvalidResponse)
        val syncedAt = currentTimeMillis()
        cache.replace(items, syncedAt)
        return ExpiringInventoryResult.Success(items, InventoryDataSource.NETWORK, syncedAt)
    }

    private suspend fun cachedResultOrUnavailable(httpCode: Int?): ExpiringInventoryResult {
        val cached = cache.read()
            ?: return ExpiringInventoryResult.Failure(InventoryError.BackendUnavailable(httpCode))
        return ExpiringInventoryResult.Success(cached.items, InventoryDataSource.CACHE, cached.lastSyncedAtEpochMillis)
    }
}
