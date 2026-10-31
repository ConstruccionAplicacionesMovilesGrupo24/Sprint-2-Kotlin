package com.campusmeal.android.core.outbox

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Core features measured by BQ6. One [UsageFeature] use is one explicit user interaction. */
enum class UsageFeature {
    INVENTORY,
    RECIPES,
    RESTAURANT_SEARCH,
    RESTAURANT_DETAIL,
    MEAL_COMPARISON,
}

/** Product-usage events (`docs/SPRINT3_BQ6.md` in CampusMealBack). Returns 202 with an empty body. */
interface UsageEventsApi {

    @POST("analytics/usage-events")
    suspend fun send(
        @Header("Authorization") authorization: String,
        @Body event: UsageEventDto,
    ): Response<Unit>
}

/**
 * The backend rejects unknown fields (400), and the owner comes from the token. No property has a
 * default value: kotlinx.serialization would omit it (encodeDefaults = false).
 */
@Serializable
data class UsageEventDto(
    val clientEventId: String,
    val eventType: String,
    val feature: String?,
    val platform: String,
    val occurredAt: String,
    val zoneId: String? = null,
    val foodCategory: String? = null,
    val budgetBand: String? = null,
)
