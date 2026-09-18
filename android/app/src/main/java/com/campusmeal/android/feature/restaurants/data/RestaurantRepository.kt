package com.campusmeal.android.feature.restaurants.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
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

    /** Sends the validated context to NestJS. Call it again to retry, e.g. after routes were unavailable. */
    suspend fun search(request: RestaurantSearchRequestDto): RestaurantSearchResult

    suspend fun getRestaurant(restaurantId: String): RestaurantDetailResult
}

/**
 * Search asks the backend first and caches every successful answer, including an empty one. The
 * cached answer is returned only when the backend is unreachable (connectivity failure or HTTP 5xx),
 * with its original last-updated time. HTTP 401 clears it, as it may belong to another session.
 *
 * The cache holds restaurant data only — never the request, so no coordinates are persisted. It lives
 * in the preferences DataStore rather than Room: one JSON value needs no table or migration.
 */
class NetworkRestaurantRepository(
    private val api: RestaurantApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val preferences: DataStore<Preferences>,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : RestaurantRepository {

    override suspend fun search(request: RestaurantSearchRequestDto): RestaurantSearchResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
            ?: return RestaurantSearchResult.Unauthorized

        return when (val response = apiCall { api.search(authorization, request) }) {
            is ApiResult.Success -> {
                val now = currentTimeMillis()
                val results = response.data.toResultsOrNull(now, ResultSource.NETWORK)
                    ?: return failure(RestaurantError.InvalidResponse)
                writeCache(CachedSearch(response.data, now))
                RestaurantSearchResult.Success(results)
            }
            ApiResult.Unauthorized -> {
                clearCache()
                RestaurantSearchResult.Unauthorized
            }
            is ApiResult.NetworkUnavailable -> cachedOr(httpCode = null)
            is ApiResult.HttpError ->
                if (response.code in 500..599) cachedOr(response.code) else failure(RestaurantError.Http(response.code))
            is ApiResult.InvalidResponse -> failure(RestaurantError.InvalidResponse)
        }
    }

    override suspend fun getRestaurant(restaurantId: String): RestaurantDetailResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
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

    private suspend fun cachedOr(httpCode: Int?): RestaurantSearchResult {
        val cached = readCache()
            ?: return failure(RestaurantError.BackendUnavailable(httpCode))
        val results = cached.response.toResultsOrNull(cached.cachedAtEpochMillis, ResultSource.CACHE)
            ?: return failure(RestaurantError.BackendUnavailable(httpCode))
        return RestaurantSearchResult.Success(results)
    }

    private suspend fun readCache(): CachedSearch? {
        val raw = preferences.data.first()[CACHE_KEY] ?: return null
        return try {
            ApiClientFactory.json.decodeFromString(CachedSearch.serializer(), raw)
        } catch (_: SerializationException) {
            null // Written by an older contract; treat as no cache.
        }
    }

    private suspend fun writeCache(entry: CachedSearch) {
        val raw = ApiClientFactory.json.encodeToString(CachedSearch.serializer(), entry)
        preferences.edit { it[CACHE_KEY] = raw }
    }

    private suspend fun clearCache() {
        preferences.edit { it.remove(CACHE_KEY) }
    }

    private fun failure(error: RestaurantError) = RestaurantSearchResult.Failure(error)

    @Serializable
    private data class CachedSearch(val response: RestaurantSearchResponseDto, val cachedAtEpochMillis: Long)

    private companion object {
        val CACHE_KEY = stringPreferencesKey("restaurants.lastSearch")
    }
}
