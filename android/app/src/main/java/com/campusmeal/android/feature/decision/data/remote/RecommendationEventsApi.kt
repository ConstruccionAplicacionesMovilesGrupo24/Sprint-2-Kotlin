package com.campusmeal.android.feature.decision.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** BQ8 recommendation events (`docs/API_CONTRACT.md` §10 in CampusMealBack). */
interface RecommendationEventsApi {

    /** Returns 202 with an empty body, so the response is not decoded. */
    @POST("analytics/events")
    suspend fun send(
        @Header("Authorization") authorization: String,
        @Body event: RecommendationEventDto,
    ): Response<Unit>
}

/**
 * Only these six fields are accepted by the backend (unknown fields answer 400). The explanation
 * category is never sent: the backend reads it from the stored recommendation.
 *
 * No property has a default value: kotlinx.serialization omits properties equal to their default
 * (encodeDefaults = false), and a missing `platform` is rejected with 400.
 */
@Serializable
data class RecommendationEventDto(
    val clientEventId: String,
    val recommendationId: String,
    val eventType: String,
    /** Null (omitted) for an impression. */
    val selectedAlternative: String?,
    val platform: String,
    /** ISO-8601 UTC instant ending in `Z`. */
    val occurredAt: String,
)
