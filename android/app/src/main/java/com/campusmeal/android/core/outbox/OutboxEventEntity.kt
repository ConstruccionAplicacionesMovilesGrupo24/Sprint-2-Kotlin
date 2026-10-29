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
}

object OutboxStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val SENT = "SENT"

    /** Rejected definitively by the backend (4xx). Kept for diagnosis and an explicit retry. */
    const val FAILED = "FAILED"
}
