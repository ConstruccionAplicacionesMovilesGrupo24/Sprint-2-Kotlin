package com.campusmeal.android.feature.reviews.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Structured restaurant reviews (S4-03 backend, `docs/SPRINT4_BQ13_REVIEWS.md` in CampusMealBack). */
interface ReviewApi {

    /** Public summary: no author; every value withheld below 5 distinct reviewers. Synthetic rows excluded. */
    @GET("restaurants/{restaurantId}/reviews")
    suspend fun summary(
        @Header("Authorization") authorization: String,
        @Path("restaurantId") restaurantId: String,
        @Query("limit") limit: Int,
    ): RestaurantReviewsResponseDto

    /**
     * Sent only by the outbox drain. 201 created, 200 idempotent replay of the same `clientReviewId`,
     * 409 `REVIEW_ALREADY_SUBMITTED_TODAY` / `CLIENT_REVIEW_ID_CONFLICT`, 404 unknown restaurant, 400 validation.
     */
    @POST("restaurants/{restaurantId}/reviews")
    suspend fun submit(
        @Header("Authorization") authorization: String,
        @Path("restaurantId") restaurantId: String,
        @Body body: CreateReviewRequestDto,
    ): Response<Unit>
}

/**
 * Exactly the request body. The backend rejects unknown fields (400) and takes the author from the token.
 * No property has a default value: kotlinx.serialization would omit it (encodeDefaults = false).
 */
@Serializable
data class CreateReviewRequestDto(
    val clientReviewId: String,
    val overall: Int,
    val portion: Int,
    val taste: Int,
    val freshness: Int,
    val value: Int,
    val waitMinutes: Int,
    /** ISO-8601 UTC ending in `Z`; at most 30 days old and 5 minutes in the future. */
    val occurredAt: String,
)

/** Versioned outbox envelope for [com.campusmeal.android.core.outbox.OutboxKind.REVIEW] (`SPRINT4_SCOPE.md` §6). */
@Serializable
data class ReviewOutboxPayload(
    val v: Int,
    val restaurantId: String,
    val body: CreateReviewRequestDto,
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class RestaurantReviewsResponseDto(
    val contractVersion: Int,
    val restaurantId: String,
    val generatedAt: String,
    val includesSyntheticData: Boolean = false,
    val summary: ReviewSummaryDto,
    val recent: List<PublicReviewDto> = emptyList(),
    val catalogRating: CatalogRatingDto? = null,
)

/** Counts and means are null while [suppressed]; `reviewCount = 0` is a real zero, not a suppression. */
@Serializable
data class ReviewSummaryDto(
    val source: String,
    val reviewCount: Int?,
    val distinctReviewers: Int?,
    val suppressed: Boolean,
    val meanOverall: Double?,
    val meanPortion: Double?,
    val meanTaste: Double?,
    val meanFreshness: Double?,
    val meanValue: Double?,
    val meanWaitMinutes: Double?,
)

@Serializable
data class PublicReviewDto(
    val reviewDate: String,
    val overall: Int,
    val portion: Int,
    val taste: Int,
    val freshness: Int,
    val value: Int,
    val waitMinutes: Int,
)

/** The seeded catalog rating; never computed from reviews and never mixed with their means. */
@Serializable
data class CatalogRatingDto(
    val value: Double,
    val provenance: String,
)
