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

/** The synchronization center's two groups, taken from one snapshot of `outbox_events`. */
data class OutboxActivity(val open: List<OutboxItem>, val sent: List<OutboxItem>) {
    companion object {
        /**
         * Splits the rows of [OutboxDao.observeActivity] by status and restores each group's order (`UNION ALL`
         * does not guarantee it). Each row has one status, so no event can be in both groups; ids are
         * de-duplicated anyway because they are the list keys.
         */
        fun of(rows: List<OutboxItem>): OutboxActivity {
            val unique = rows.distinctBy { it.clientEventId }
            val (sent, open) = unique.partition { it.status == OutboxStatus.SENT }
            return OutboxActivity(
                open = open.sortedByDescending { it.createdAtEpochMillis },
                sent = sent.sortedByDescending { it.closedAtEpochMillis ?: it.createdAtEpochMillis },
            )
        }
    }
}
