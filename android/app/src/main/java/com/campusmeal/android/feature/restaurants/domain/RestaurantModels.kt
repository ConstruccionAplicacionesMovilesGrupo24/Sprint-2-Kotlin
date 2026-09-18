package com.campusmeal.android.feature.restaurants.domain

import com.campusmeal.android.feature.context.domain.DietaryPreference

enum class OpeningStatus { OPEN, CLOSING_SOON, CLOSED }

/**
 * State of the external route provider, as reported by the CampusMeal backend. Android never talks
 * to the provider; it only relays what NestJS says about it.
 */
enum class RouteProviderStatus {
    /** Every restaurant has walking and total estimates. */
    AVAILABLE,

    /** Some restaurants have estimates; the rest show without them. */
    PARTIAL,

    /** No route estimates at all. Restaurant information is still valid. */
    UNAVAILABLE,
}

enum class ResultSource { NETWORK, CACHE }

/**
 * One BQ4 result. Walking and total times are null when the route provider could not estimate this
 * restaurant; everything else still comes from the backend and stays displayable.
 */
data class RestaurantSummary(
    val id: String,
    val name: String,
    val category: String,
    val openingStatus: OpeningStatus,
    val walkingMinutes: Int?,
    val estimatedTotalMinutes: Int?,
    /** Whole Colombian pesos. */
    val minimumMealPrice: Long,
    val dietaryTags: Set<DietaryPreference>,
    /** 0–5, or null when the restaurant has no ratings yet. */
    val averageRating: Double?,
    val recommendationReason: String?,
)

/** Search answer in backend order: Android does not re-sort or filter it. */
data class RestaurantResults(
    val restaurants: List<RestaurantSummary>,
    val routeProviderStatus: RouteProviderStatus,
    val lastUpdatedAtEpochMillis: Long,
    val source: ResultSource,
)

data class Meal(val id: String, val name: String, val price: Long, val dietaryTags: Set<DietaryPreference>)

data class RestaurantDetail(
    val summary: RestaurantSummary,
    val address: String?,
    val meals: List<Meal>,
    val routeProviderStatus: RouteProviderStatus,
    val lastUpdatedAtEpochMillis: Long,
)

sealed interface RestaurantSearchResult {

    /**
     * An empty [RestaurantResults.restaurants] is a valid "no results" answer. A [ResultSource.CACHE]
     * source means the backend was unreachable and this is the last successful search.
     */
    data class Success(val results: RestaurantResults) : RestaurantSearchResult

    /** There is no session, or the backend answered HTTP 401. Cached results are never returned. */
    data object Unauthorized : RestaurantSearchResult

    data class Failure(val error: RestaurantError) : RestaurantSearchResult
}

sealed interface RestaurantDetailResult {
    data class Success(val detail: RestaurantDetail) : RestaurantDetailResult
    data object Unauthorized : RestaurantDetailResult
    data class Failure(val error: RestaurantError) : RestaurantDetailResult
}

sealed interface RestaurantError {

    /** Connectivity failure or HTTP 5xx, and nothing cached. Null [httpCode] means no HTTP response. */
    data class BackendUnavailable(val httpCode: Int?) : RestaurantError

    /** Any other non-2xx status except 401, such as 404 for an unknown restaurant. */
    data class Http(val code: Int) : RestaurantError

    /** The response broke the contract. */
    data object InvalidResponse : RestaurantError
}
