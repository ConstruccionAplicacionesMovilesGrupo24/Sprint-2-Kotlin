package com.campusmeal.android.feature.decision.data.repository

import android.util.Log
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventDto
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventsApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * One BQ8 event. [clientEventId] is generated once, when the event happens, and reused by every
 * retry, so the backend stores it exactly once (`ON CONFLICT DO NOTHING` on that id).
 */
data class RecommendationEvent(
    val recommendationId: String,
    /** Null for an impression; `COOK`, `WALK` or `ORDER` for a selection. */
    val selectedAlternative: String?,
    val clientEventId: String = UUID.randomUUID().toString(),
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
)

/** Sends recommendation impressions and selections to `POST analytics/events`. */
class RecommendationEventReporter(
    private val api: RecommendationEventsApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val retryDelaysMillis: List<Long> = listOf(1_000, 3_000),
) {

    /**
     * Returns true once the backend accepted the event (202). Connectivity failures and HTTP 5xx
     * are retried with the same [RecommendationEvent.clientEventId]; other failures are not.
     *
     * Every attempt uses the credentials of [scope] only: once that session ends, retries stop instead
     * of sending the event with another account's token. Pending events are still memory-only until
     * the S3-09 outbox.
     */
    suspend fun report(scope: AccountSessionScope, event: RecommendationEvent): Boolean {
        val dto = RecommendationEventDto(
            clientEventId = event.clientEventId,
            recommendationId = event.recommendationId,
            eventType = if (event.selectedAlternative == null) IMPRESSION else SELECTED,
            selectedAlternative = event.selectedAlternative,
            platform = PLATFORM,
            occurredAt = isoUtc(event.occurredAtEpochMillis),
        )
        for (attempt in 0..retryDelaysMillis.size) {
            if (attempt > 0) delay(retryDelaysMillis[attempt - 1])
            val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return false
            val status = when (val result = apiCall { api.send(authorization, dto) }) {
                is ApiResult.Success -> result.data.code()
                ApiResult.Unauthorized -> 401
                is ApiResult.HttpError -> result.code
                is ApiResult.NetworkUnavailable, is ApiResult.InvalidResponse -> null
            }
            // Ids only: no token, coordinates or explanation text reach the log.
            if (BuildConfig.DEBUG) Log.d(TAG, "${dto.eventType} clientEventId=${dto.clientEventId} attempt=${attempt + 1} status=${status ?: "network error"}")
            when {
                status == 202 -> return true
                status != null && status !in 500..599 -> return false
            }
        }
        return false
    }

    private fun isoUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    private companion object {
        const val TAG = "CampusMealBQ8"
        const val PLATFORM = "ANDROID"
        const val IMPRESSION = "RECOMMENDATION_IMPRESSION"
        const val SELECTED = "RECOMMENDATION_SELECTED"
    }
}
