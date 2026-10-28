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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** The BQ4 / Issue #6 boundary the results ViewModel depends on. */
interface RestaurantRepository {

    /**
     * Sends the validated context to NestJS for the account of [scope]. Call it again to retry, e.g.
     * after routes were unavailable.
     */
    suspend fun search(scope: AccountSessionScope, request: RestaurantSearchRequestDto): RestaurantSearchResult

    suspend fun getRestaurant(scope: AccountSessionScope, restaurantId: String): RestaurantDetailResult
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
) : RestaurantRepository, AccountScopedStore {

    override suspend fun search(scope: AccountSessionScope, request: RestaurantSearchRequestDto): RestaurantSearchResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return RestaurantSearchResult.Unauthorized

        return when (val response = apiCall { api.search(authorization, request) }) {
            is ApiResult.Success -> {
                val now = currentTimeMillis()
                val results = response.data.toResultsOrNull(now, ResultSource.NETWORK)
                    ?: return failure(RestaurantError.InvalidResponse)
                writeCache(scope, CachedSearch(response.data, now))
                RestaurantSearchResult.Success(results)
            }
            ApiResult.Unauthorized -> RestaurantSearchResult.Unauthorized
            is ApiResult.NetworkUnavailable -> cachedOr(scope, httpCode = null)
            is ApiResult.HttpError ->
                if (response.code in 500..599) cachedOr(scope, response.code) else failure(RestaurantError.Http(response.code))
            is ApiResult.InvalidResponse -> failure(RestaurantError.InvalidResponse)
        }
    }

    override suspend fun getRestaurant(scope: AccountSessionScope, restaurantId: String): RestaurantDetailResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return RestaurantDetailResult.Unauthorized

        return when (val response = apiCall { api.getRestaurant(authorization, restaurantId) }) {
            is ApiResult.Success -> response.data.toDetailOrNull(currentTimeMillis())
                ?.let(RestaurantDetailResult::Success)
                ?: RestaurantDetailResult.Failure(RestaurantError.InvalidResponse)
            ApiResult.Unauthorized -> RestaurantDetailResult.Unauthorized
            is ApiResult.NetworkUnavailable ->
                RestaurantDetailResult.Failure(RestaurantError.BackendUnavailable(null))
            is ApiResult.HttpError -> RestaurantDetailResult.Failure(
                if (response.code in 500..599) {
                    RestaurantError.BackendUnavailable(response.code)
                } else {
                    RestaurantError.Http(response.code)
                },
            )
            is ApiResult.InvalidResponse -> RestaurantDetailResult.Failure(RestaurantError.InvalidResponse)
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        preferences.edit { it.remove(cacheKey(accountId)) }
    }

    /** Also removes the ownerless Sprint 2 key `restaurants.lastSearch`, which no account can claim. */
    override suspend fun deleteAllExcept(accountId: String) {
        val keep = cacheKey(accountId)
        preferences.edit { prefs ->
            prefs.asMap().keys
                .filter { key -> (key.name == LEGACY_CACHE_KEY || key.name.startsWith("$LEGACY_CACHE_KEY.")) && key != keep }
                .forEach { prefs.remove(it) }
        }
    }

    private suspend fun cachedOr(scope: AccountSessionScope, httpCode: Int?): RestaurantSearchResult {
        val cached = readCache(scope.accountId)
            ?: return failure(RestaurantError.BackendUnavailable(httpCode))
        val results = cached.response.toResultsOrNull(cached.cachedAtEpochMillis, ResultSource.CACHE)
            ?: return failure(RestaurantError.BackendUnavailable(httpCode))
        return RestaurantSearchResult.Success(results)
    }

    private suspend fun readCache(accountId: String): CachedSearch? {
        val raw = preferences.data.first()[cacheKey(accountId)] ?: return null
        return try {
            ApiClientFactory.json.decodeFromString(CachedSearch.serializer(), raw)
        } catch (_: SerializationException) {
            null // Written by an older contract; treat as no cache.
        }
    }

    /** A late answer of an ended session is not cached. */
    private suspend fun writeCache(scope: AccountSessionScope, entry: CachedSearch) {
        val raw = ApiClientFactory.json.encodeToString(CachedSearch.serializer(), entry)
        coordinator.runIfCurrent(scope) {
            preferences.edit { it[cacheKey(scope.accountId)] = raw }
        }
    }

    private fun failure(error: RestaurantError) = RestaurantSearchResult.Failure(error)

    @Serializable
    private data class CachedSearch(val response: RestaurantSearchResponseDto, val cachedAtEpochMillis: Long)

    private companion object {
        const val LEGACY_CACHE_KEY = "restaurants.lastSearch"

        fun cacheKey(accountId: String) = stringPreferencesKey("$LEGACY_CACHE_KEY.$accountId")
    }
}
