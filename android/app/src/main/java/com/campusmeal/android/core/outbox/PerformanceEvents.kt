package com.campusmeal.android.core.outbox

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** BQ1 technical telemetry (`docs/SPRINT4_BQ1.md` in CampusMealBack). Returns 202 with an empty body. */
interface PerformanceEventsApi {

    @POST("analytics/performance-events")
    suspend fun send(
        @Header("Authorization") authorization: String,
        @Body event: PerformanceEventDto,
    ): Response<Unit>
}

enum class PerformanceEventType { SESSION_START, FATAL_CRASH, COMPARISON_LOAD }

/** How one explicit comparison load ended. Only [FRESH_NETWORK] enters the BQ1 p95. */
enum class ComparisonLoadOutcome { FRESH_NETWORK, CACHED_RESTORE, OFFLINE_HISTORY, ERROR, TIMEOUT, CANCELLED }

/** What the device reports about itself: model, OS and app version. Never an identifier. */
data class DeviceInfo(val deviceModel: String, val osVersion: String, val appVersion: String)

/** One telemetry record before it is admitted. Holds no token, coordinate, message or stack trace. */
data class PerformanceEvent(
    val clientEventId: String,
    val type: PerformanceEventType,
    val device: DeviceInfo,
    val occurredAtEpochMillis: Long,
    val sessionId: String? = null,
    val loadId: String? = null,
    val screen: String? = null,
    val exceptionClass: String? = null,
    val durationMs: Long? = null,
    val outcome: ComparisonLoadOutcome? = null,
    val injected: Boolean? = null,
)

/**
 * The backend rejects unknown fields and fields of another type, so absent values are omitted
 * (`explicitNulls = false`). No property has a default: kotlinx.serialization would omit it.
 */
@Serializable
data class PerformanceEventDto(
    val clientEventId: String,
    val eventType: String,
    val sessionId: String?,
    val loadId: String?,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val platform: String,
    val screen: String?,
    val exceptionClass: String?,
    val durationMs: Long?,
    val outcome: String?,
    val injected: Boolean?,
    val occurredAt: String,
)

/** Versioned outbox envelope of [OutboxKind.PERFORMANCE] (`SPRINT4_SCOPE.md` §6). */
@Serializable
data class PerformanceOutboxPayload(val v: Int, val body: PerformanceEventDto) {
    companion object {
        const val VERSION = 1
    }
}
