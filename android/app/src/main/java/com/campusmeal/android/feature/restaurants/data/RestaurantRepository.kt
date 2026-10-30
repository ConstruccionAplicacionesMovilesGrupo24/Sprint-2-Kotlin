package com.campusmeal.android.feature.restaurants.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetailResult
import com.campusmeal.android.feature.restaurants.domain.RestaurantError
import com.campusmeal.android.feature.restaurants.domain.RestaurantSearchResult
import com.campusmeal.android.feature.restaurants.domain.ResultSource
import kotlinx.serialization.SerializationException
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.feature.restaurants.data.RestaurantSearchCacheEntity

/** The BQ4 / Issue #6 boundary the results ViewModel depends on. */
interface RestaurantRepository {

    /**
     * Sends the validated context to NestJS for the account of [scope]. Call it again to retry, e.g.
     * after routes were unavailable.
     */
    suspend fun search(
        scope: AccountSessionScope,
        request: RestaurantSearchRequestDto,
        forceRefresh: Boolean = false,
    ): RestaurantSearchResult

    suspend fun getRestaurant(
        scope: AccountSessionScope,
        restaurantId: String,
        forceRefresh: Boolean = false,
    ): RestaurantDetailResult
    
    
}
/**
 * Search asks the backend first and caches every successful answer, including an empty one. The
 * cached answer is returned only when the backend is unreachable (connectivity failure, HTTP 5xx, or a
 * token refresh that is temporarily unavailable), with its original last-updated time. HTTP 401 never
 * deletes it: the session lifecycle owns that cleanup.
 *
 * The snapshot belongs to one account (`restaurants.lastSearch.{accountId}`) and is written only while
 * the search's scope is still active. It is still a single last-search snapshot, not keyed by search
 * context: context keys, TTLs and detail caching are S3-06.
 *
 * The cache holds restaurant data only — never the request, so no coordinates are persisted. It lives
 * in the preferences DataStore rather than Room: one JSON value needs no table or migration.
 */
class NetworkRestaurantRepository(
    private val api: RestaurantApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val preferences: DataStore<Preferences>,
    private val coordinator: AccountSessionCoordinator,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val database: CampusMealDatabase,
    private val cachePolicy: CachePolicy,
) : RestaurantRepository, AccountScopedStore {
    private val cacheDao = database.restaurantCacheDao()
    private val json = ApiClientFactory.json

   override suspend fun search(
    scope: AccountSessionScope,
    request: RestaurantSearchRequestDto,
    forceRefresh: Boolean,
): RestaurantSearchResult {
        val contextKey = request.cacheContextKeyOrNull()

        if (!forceRefresh && contextKey != null) {
            freshCachedSearch(scope, contextKey)?.let {
                return it
            }
        }
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return RestaurantSearchResult.Unauthorized

        return when (val response = apiCall { api.search(authorization, request) }) {
            is ApiResult.Success -> {
                val now = currentTimeMillis()

                val results =
                    response.data.toResultsOrNull(
                        now,
                        ResultSource.NETWORK,
                    ) ?: return failure(RestaurantError.InvalidResponse)

                if (contextKey != null) {
                    val entity =
                        RestaurantSearchCacheEntity(
                            accountId = scope.accountId,
                            contextKey = contextKey,
                            responseJson =
                                json.encodeToString(
                                    RestaurantSearchResponseDto.serializer(),
                                    response.data,
                                ),
                            fetchedAtEpochMillis = now,
                        )

                    coordinator.runIfCurrent(scope) {
                        cacheDao.upsertSearch(entity)
                    }
                }

                RestaurantSearchResult.Success(results)
            }
            ApiResult.Unauthorized -> RestaurantSearchResult.Unauthorized
            is ApiResult.NetworkUnavailable -> {
                if (contextKey != null) {
                    cachedSearch(scope, contextKey)?.let {
                        return it
                    }
                }

                failure(RestaurantError.BackendUnavailable(null))
            }
            is ApiResult.HttpError -> {
                if (response.code in 500..599) {
                    if (contextKey != null) {
                        cachedSearch(scope, contextKey)?.let {
                            return it
                        }
                    }

                    failure(
                        RestaurantError.BackendUnavailable(
                            response.code,
                        ),
                    )
                } else {
                    failure(RestaurantError.Http(response.code))
                }
            }
            is ApiResult.InvalidResponse -> failure(RestaurantError.InvalidResponse)
        }
    }

    override suspend fun getRestaurant(
        scope: AccountSessionScope,
        restaurantId: String,
        forceRefresh: Boolean,
    ): RestaurantDetailResult {
        if (!forceRefresh) {
            freshCachedDetail(scope, restaurantId)?.let {
                return it
            }
        }
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return RestaurantDetailResult.Unauthorized

        return when (val response = apiCall { api.getRestaurant(authorization, restaurantId) }) {
           is ApiResult.Success -> {
                val now = currentTimeMillis()

                val detail =
                    response.data.toDetailOrNull(now)
                        ?: return RestaurantDetailResult.Failure(
                            RestaurantError.InvalidResponse,
                        )

                val entity =
                    RestaurantDetailCacheEntity(
                        accountId = scope.accountId,
                        restaurantId = restaurantId,
                        responseJson =
                            json.encodeToString(
                                RestaurantDetailResponseDto.serializer(),
                                response.data,
                            ),
                        fetchedAtEpochMillis = now,
                    )

                coordinator.runIfCurrent(scope) {
                    cacheDao.upsertDetail(entity)
                }

                RestaurantDetailResult.Success(detail)
            }
            ApiResult.Unauthorized -> RestaurantDetailResult.Unauthorized
            is ApiResult.NetworkUnavailable -> {
                cachedDetail(scope, restaurantId)?.let {
                    return it
                }

                RestaurantDetailResult.Failure(
                    RestaurantError.BackendUnavailable(null),
                )
            }
            is ApiResult.HttpError -> {
                if (response.code in 500..599) {
                    cachedDetail(scope, restaurantId)?.let {
                        return it
                    }

                    RestaurantDetailResult.Failure(
                        RestaurantError.BackendUnavailable(
                            response.code,
                        ),
                    )
                } else {
                    RestaurantDetailResult.Failure(
                        RestaurantError.Http(response.code),
                    )
                }
            }
            is ApiResult.InvalidResponse ->
                RestaurantDetailResult.Failure(
                    RestaurantError.InvalidResponse,
                )
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        cacheDao.deleteSearches(accountId)
        cacheDao.deleteDetails(accountId)

        preferences.edit {
            it.remove(cacheKey(accountId))
        }
    }

    /** Also removes the ownerless Sprint 2 key `restaurants.lastSearch`, which no account can claim. */
    override suspend fun deleteAllExcept(accountId: String) {
        cacheDao.deleteSearchesExcept(accountId)
        cacheDao.deleteDetailsExcept(accountId)

        val keep = cacheKey(accountId)

        preferences.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    (
                        key.name == LEGACY_CACHE_KEY ||
                            key.name.startsWith("$LEGACY_CACHE_KEY.")
                    ) && key != keep
                }
                .forEach { prefs.remove(it) }
        }
    }

    
    /**
     * Returns the cached search results for the given context key, if they are fresh.
     */
    private suspend fun cachedSearch(
        scope: AccountSessionScope,
        contextKey: String,
    ): RestaurantSearchResult.Success? {
        if (!coordinator.isCurrent(scope)) return null

        val entity =
            cacheDao.getSearch(
                accountId = scope.accountId,
                contextKey = contextKey,
            ) ?: return null

        return try {
            val response =
                json.decodeFromString(
                    RestaurantSearchResponseDto.serializer(),
                    entity.responseJson,
                )

            val results =
                response.toResultsOrNull(
                    fallbackTimeMillis = entity.fetchedAtEpochMillis,
                    source = ResultSource.CACHE,
                ) ?: return null

            RestaurantSearchResult.Success(results)
        } catch (_: SerializationException) {
            null
        }
    }
    private suspend fun freshCachedSearch(
        scope: AccountSessionScope,
        contextKey: String,
    ): RestaurantSearchResult.Success? {
        val entity =
            cacheDao.getSearch(
                accountId = scope.accountId,
                contextKey = contextKey,
            ) ?: return null

        if (
            Freshness.synced(
                entity.fetchedAtEpochMillis,
                cachePolicy.restaurantSearch,
                currentTimeMillis(),
            ) !is Freshness.Fresh
        ) {
            return null
        }

        return cachedSearch(scope, contextKey)
    }
    private suspend fun cachedDetail(
        scope: AccountSessionScope,
        restaurantId: String,
    ): RestaurantDetailResult.Success? {
        if (!coordinator.isCurrent(scope)) return null

        val entity =
            cacheDao.getDetail(
                accountId = scope.accountId,
                restaurantId = restaurantId,
            ) ?: return null

        return try {
            val response =
                json.decodeFromString(
                    RestaurantDetailResponseDto.serializer(),
                    entity.responseJson,
                )

            val detail =
                response.toDetailOrNull(
                    fallbackTimeMillis = entity.fetchedAtEpochMillis,
                ) ?: return null

            RestaurantDetailResult.Success(detail)
        } catch (_: SerializationException) {
            null
        }
    }

    private suspend fun freshCachedDetail(
        scope: AccountSessionScope,
        restaurantId: String,
    ): RestaurantDetailResult.Success? {
        val entity =
            cacheDao.getDetail(
                accountId = scope.accountId,
                restaurantId = restaurantId,
            ) ?: return null

        if (
            Freshness.synced(
                entity.fetchedAtEpochMillis,
                cachePolicy.restaurantDetail,
                currentTimeMillis(),
            ) !is Freshness.Fresh
        ) {
            return null
        }

        return cachedDetail(scope, restaurantId)
    }

    private fun failure(error: RestaurantError) = RestaurantSearchResult.Failure(error)
    private companion object {
        const val LEGACY_CACHE_KEY = "restaurants.lastSearch"

        fun cacheKey(accountId: String) = stringPreferencesKey("$LEGACY_CACHE_KEY.$accountId")
    }
}

private fun RestaurantSearchRequestDto.cacheContextKeyOrNull(): String? {
    val campus = campusId ?: return null

    return listOf(
        "campus=$campus",
        "minutes=$availableMinutes",
        "budget=$maximumBudget",
        "diet=${dietaryPreferences.sorted().joinToString(",")}",
        "delivery=$includeDelivery",
        "category=${foodCategory ?: "-"}",
    ).joinToString("|")
}