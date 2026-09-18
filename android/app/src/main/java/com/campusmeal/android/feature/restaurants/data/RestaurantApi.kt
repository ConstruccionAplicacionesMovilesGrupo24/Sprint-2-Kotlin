package com.campusmeal.android.feature.restaurants.data

import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * BQ4 restaurant endpoints on the CampusMeal NestJS API. The backend calls the external route
 * provider; Android never does, and holds no provider key. Paths are relative to `/api/v1/`.
 */
interface RestaurantApi {

    /** [request] is the validated context built by `ContextViewModel`, including `requestedAt`. */
    @POST("restaurants/search")
    suspend fun search(
        @Header("Authorization") authorization: String,
        @Body request: RestaurantSearchRequestDto,
    ): RestaurantSearchResponseDto

    @GET("restaurants/{restaurantId}")
    suspend fun getRestaurant(
        @Header("Authorization") authorization: String,
        @Path("restaurantId") restaurantId: String,
    ): RestaurantDetailResponseDto
}

/**
 * Provisional NestJS contract. These are CampusMeal models: nothing provider-specific (Google,
 * Mapbox…) reaches Android. Restaurant fields are nullable so one incomplete restaurant is dropped
 * instead of failing the whole search.
 */
@Serializable
data class RestaurantSearchResponseDto(
    val restaurants: List<RestaurantDto>,
    /** ISO-8601 UTC. */
    val lastUpdatedAt: String? = null,
    /** `AVAILABLE`, `PARTIAL` or `UNAVAILABLE`. */
    val routeProviderStatus: String? = null,
)

@Serializable
data class RestaurantDto(
    val id: String? = null,
    val name: String? = null,
    val category: String? = null,
    /** `OPEN`, `CLOSING_SOON` or `CLOSED`. */
    val openingStatus: String? = null,
    /** Null when the route provider gave no estimate for this restaurant. */
    val walkingMinutes: Int? = null,
    val estimatedTotalMinutes: Int? = null,
    val minimumMealPrice: Long? = null,
    val dietaryTags: List<String> = emptyList(),
    val averageRating: Double? = null,
    val recommendationReason: String? = null,
)

@Serializable
data class RestaurantDetailResponseDto(
    val restaurant: RestaurantDto,
    val address: String? = null,
    val meals: List<MealDto> = emptyList(),
    val lastUpdatedAt: String? = null,
    val routeProviderStatus: String? = null,
)

@Serializable
data class MealDto(
    val id: String? = null,
    val name: String? = null,
    val price: Long? = null,
    val dietaryTags: List<String> = emptyList(),
)
