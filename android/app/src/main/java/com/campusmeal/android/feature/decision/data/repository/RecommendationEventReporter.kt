package com.campusmeal.android.feature.decision.data.repository

import com.campusmeal.android.core.outbox.OutboxAdmission
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.OutboxStatus
import com.campusmeal.android.core.session.AccountSessionScope
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

/** Where a recorded event is on its way to the backend. */
enum class EventDelivery { PENDING, SENT, FAILED }

/**
 * Records recommendation impressions and selections in the durable outbox (S3-09). Delivery to
 * `POST analytics/events`, retries and backoff happen in the background, so an event is kept when
 * the device is offline or the process dies, and is only ever sent with [AccountSessionScope]'s
 * own credentials.
 */
class RecommendationEventReporter(private val outbox: OutboxRepository) {

    /**
     * Saves the event locally and schedules its delivery. Saving the same event again is a no-op
     * (a definitively failed one is queued again). Returns false when nothing was stored, because
     * [scope] is no longer the active session or the outbox is full.
     */
    suspend fun record(scope: AccountSessionScope, event: RecommendationEvent): Boolean =
        outbox.enqueueRecommendationEvent(
            scope = scope,
            recommendationId = event.recommendationId,
            selectedAlternative = event.selectedAlternative,
            clientEventId = event.clientEventId,
            occurredAtEpochMillis = event.occurredAtEpochMillis,
        ) == OutboxAdmission.Admitted

    /** Delivery state of a recorded event; emits again whenever it changes. */
    fun observe(clientEventId: String): Flow<EventDelivery> =
        outbox.observeStatus(clientEventId).map { status ->
            when (status) {
                OutboxStatus.SENT -> EventDelivery.SENT
                OutboxStatus.FAILED -> EventDelivery.FAILED
                else -> EventDelivery.PENDING
            }
        }
}
