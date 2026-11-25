package com.campusmeal.android.core.outbox

/** Row of the synchronization center: a projection of `outbox_events` without its payload. */
data class OutboxItem(
    val clientEventId: String,
    val kind: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val occurredAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val lastAttemptAtEpochMillis: Long?,
    val closedAtEpochMillis: Long?,
) {
    /**
     * Whether sending the same event again can end differently. A review is resolved on its own screen,
     * a conflict (409) and an unreadable payload always give the same answer, and an expired event is
     * older than the backend accepts.
     */
    val canRetry: Boolean
        get() = status == OutboxStatus.FAILED &&
            kind != OutboxKind.REVIEW &&
            lastError != OutboxRepository.UNREADABLE_PAYLOAD &&
            lastError?.startsWith("HTTP 409") != true
}

data class OutboxStatusCount(val status: String, val total: Int)
