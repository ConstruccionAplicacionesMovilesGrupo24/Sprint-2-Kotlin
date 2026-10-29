package com.campusmeal.android.feature.recipes.data.repository

import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.recipes.data.local.RoomRecipeCatalogStore
import com.campusmeal.android.feature.recipes.data.mapper.CatalogValidation
import com.campusmeal.android.feature.recipes.data.mapper.validate
import com.campusmeal.android.feature.recipes.data.remote.RecipeApi
import com.campusmeal.android.feature.recipes.domain.CatalogError
import com.campusmeal.android.feature.recipes.domain.CatalogRefreshResult
import com.campusmeal.android.feature.recipes.domain.RecipeRepository
import com.campusmeal.android.feature.recipes.domain.model.RecipeCatalogSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local-first catalog (S3-05). One [Mutex] serializes catalog refreshes, so overlapping triggers
 * coalesce: a refresh that waited is skipped when a fetch that started after it was requested already
 * succeeded. HTTP runs with only this lock held; the session coordinator lock is taken only inside
 * `runIfCurrent` for the local commit. Any failure keeps the stored catalog and its original fetch time,
 * and HTTP 401 never deletes it (the session lifecycle owns cleanup).
 */
class OfflineFirstRecipeRepository(
    private val api: RecipeApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val store: RoomRecipeCatalogStore,
    private val coordinator: AccountSessionCoordinator,
    private val cachePolicy: CachePolicy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : RecipeRepository {

    private val mutex = Mutex()

    /** When the last successful fetch started; lets queued refreshes coalesce. */
    @Volatile
    private var lastFetchStartedAt = Long.MIN_VALUE

    override fun observeCatalog(scope: AccountSessionScope): Flow<RecipeCatalogSnapshot?> =
        store.observe().transform { value -> if (coordinator.isCurrent(scope)) emit(value) }

    override suspend fun refreshCatalog(scope: AccountSessionScope, force: Boolean): CatalogRefreshResult {
        val requestedAt = currentTimeMillis()
        return mutex.withLock {
            if (!coordinator.isCurrent(scope)) return@withLock CatalogRefreshResult.SessionEnded
            val metadata = store.metadata()
            val fresh = metadata != null &&
                Freshness.synced(metadata.fetchedAtEpochMillis, cachePolicy.recipeCatalog, currentTimeMillis()) is Freshness.Fresh
            if (metadata != null && (lastFetchStartedAt >= requestedAt || (!force && fresh))) {
                CatalogRefreshResult.AlreadyCurrent
            } else {
                fetchLocked(scope)
            }
        }
    }

    private suspend fun fetchLocked(scope: AccountSessionScope): CatalogRefreshResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return if (coordinator.isCurrent(scope)) CatalogRefreshResult.Unauthorized else CatalogRefreshResult.SessionEnded
        val startedAt = currentTimeMillis()
        val response = when (val result = apiCall { api.getCatalog(authorization) }) {
            is ApiResult.Success -> result.data
            ApiResult.Unauthorized -> return CatalogRefreshResult.Unauthorized
            is ApiResult.NetworkUnavailable -> return failed(CatalogError.Unreachable)
            is ApiResult.HttpError -> return failed(
                when {
                    result.code == 503 -> CatalogError.NotAvailableYet
                    result.code in 500..599 -> CatalogError.Server(result.code)
                    else -> CatalogError.Http(result.code)
                },
            )
            is ApiResult.InvalidResponse -> return failed(CatalogError.InvalidResponse("undecodable body"))
        }
        // The whole response is validated before anything stored is touched.
        val validation = response.validate()
        if (validation is CatalogValidation.Invalid) return failed(CatalogError.InvalidResponse(validation.reason))

        val hash = RoomRecipeCatalogStore.contentHash(response)
        val fetchedAt = currentTimeMillis()
        val stored = store.metadata()
        val outcome = when {
            stored == null || stored.catalogVersion != response.catalogVersion -> {
                coordinator.runIfCurrent(scope) { store.replace(response, hash, fetchedAt) }
                    ?: return CatalogRefreshResult.SessionEnded
                CatalogRefreshResult.Replaced
            }
            stored.contentHash == hash -> {
                coordinator.runIfCurrent(scope) { store.renewFetchedAt(fetchedAt) }
                    ?: return CatalogRefreshResult.SessionEnded
                CatalogRefreshResult.Unchanged
            }
            else -> return failed(CatalogError.SameVersionChanged)
        }
        lastFetchStartedAt = startedAt
        return outcome
    }

    private fun failed(error: CatalogError) = CatalogRefreshResult.Failed(error)
}
