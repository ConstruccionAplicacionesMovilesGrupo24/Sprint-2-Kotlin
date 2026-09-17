package com.campusmeal.android.feature.decision.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MealDecisionApi {

    /**
     * Asks the backend to score and rank Cook, Walk and Order for the given context.
     * [authorization] is the full `Bearer` header value. The path is relative because the base URL
     * already ends in `/api/v1/`.
     */
    @POST("meal-decisions/compare")
    suspend fun compare(
        @Header("Authorization") authorization: String,
        @Body request: CompareMealOptionsRequestDto,
    ): CompareMealOptionsResponseDto
}

/** Same context shape as the BQ4 restaurant search. [requestedAt] is ISO-8601 UTC. */
@Serializable
data class CompareMealOptionsRequestDto(
    val location: LocationDto,
    val campusId: String?,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val dietaryPreferences: List<String>,
    val includeDelivery: Boolean,
    val requestedAt: String,
)

@Serializable
data class LocationDto(val latitude: Double, val longitude: Double)

/**
 * Provisional NestJS contract for `POST meal-decisions/compare`. The envelope fields are required.
 * Alternative fields are nullable on purpose: an incomplete or unsupported alternative is dropped by
 * the mapper instead of failing the whole response.
 */
@Serializable
data class CompareMealOptionsResponseDto(
    val recommendationId: String,
    val alternatives: List<MealAlternativeDto>,
    val mainExplanation: String,
    val supportingReasons: List<String> = emptyList(),
)

@Serializable
data class MealAlternativeDto(
    /** `COOK`, `WALK` or `ORDER`. Any other value is unsupported. */
    val type: String? = null,
    val rank: Int? = null,
    val score: Double? = null,
    val recommended: Boolean? = null,
    val estimatedMinutes: Int? = null,
    val estimatedCost: Long? = null,
    /** COOK only. */
    val expiringIngredients: List<ExpiringIngredientDto>? = null,
    /** WALK and ORDER only. */
    val restaurant: RestaurantDto? = null,
)

@Serializable
data class ExpiringIngredientDto(
    val itemId: String? = null,
    val name: String? = null,
    val remainingDays: Int? = null,
)

@Serializable
data class RestaurantDto(
    val id: String? = null,
    val name: String? = null,
    /** Required for WALK. */
    val walkingMinutes: Int? = null,
    /** Required for ORDER. */
    val deliveryMinutes: Int? = null,
    /** Required for ORDER: additional cost on top of the meal. */
    val deliveryFee: Long? = null,
)
