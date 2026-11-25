package com.campusmeal.android.core.outbox

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One analytics event waiting to be delivered (S3-09). It is written to Room BEFORE any network
 * attempt, so it survives process death and a missing connection. [clientEventId] is the id the
 * backend deduplicates on and never changes between retries.
 *
 * Holds no tokens or coordinates: [payloadJson] is exactly the request body, and the worker reads
 * the current credentials when it sends.
 */
@Entity(
    tableName = "outbox_events",
    indices = [Index(value = ["accountId", "status", "createdAtEpochMillis"])],
)
data class OutboxEventEntity(
    @PrimaryKey val clientEventId: String,
    /** Account that produced the event; only that account's session may send it. */
    val accountId: String,
    /** [OutboxKind]. */
    val kind: String,
    /** Set for BQ8 events, so the UI can observe the delivery of a selection. */
    val recommendationId: String?,
    val payloadJson: String,
    val occurredAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    /** [OutboxStatus]. */
    val status: String,
    val attempts: Int,
    val lastError: String?,
)

object OutboxKind {
    /** `POST analytics/events` (BQ8 impressions and selections). */
    const val RECOMMENDATION = "RECOMMENDATION"

    /** `POST analytics/usage-events` (BQ6 sessions and feature uses). */
    const val USAGE = "USAGE"

    /**
     * `POST restaurants/:id/reviews` (S4-04, BQ13 input). Payload is the versioned envelope
     * `{"v":1,"restaurantId":…,"body":{…}}`; the row's `clientEventId` is the review's `clientReviewId`.
     */
    const val REVIEW = "REVIEW"
}

/**
 * Told about the definitive outcome of an event, after its row was updated. Runs on the drain worker:
 * local work only.
 */
interface OutboxDeliveryListener {

    /** The backend acknowledged [event] (2xx; for reviews, 201 created or 200 idempotent replay). */
    suspend fun onDelivered(event: OutboxEventEntity) = Unit

    /** The backend rejected [event] definitively; [reason] is `HTTP <code>` plus the API error code when known. */
    suspend fun onRejected(event: OutboxEventEntity, reason: String) = Unit
}

object OutboxStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"

    /** Rejected definitively by the backend (4xx). Kept for diagnosis and an explicit retry. */
    const val FAILED = "FAILED"
}
