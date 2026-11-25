package com.campusmeal.android.core.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    /** Returns -1 when the id already exists (the same event is never stored twice). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: OutboxEventEntity): Long

    @Query(
        "SELECT * FROM outbox_events WHERE accountId = :accountId AND status = 'PENDING' " +
            "ORDER BY createdAtEpochMillis ASC LIMIT :limit",
    )
    suspend fun pending(accountId: String, limit: Int): List<OutboxEventEntity>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE accountId = :accountId AND status IN ('PENDING', 'SENDING')")
    suspend fun pendingCount(accountId: String): Int

    /** A crash can leave rows in SENDING; the next drain sends them again with the same id. */
    @Query("UPDATE outbox_events SET status = 'PENDING' WHERE accountId = :accountId AND status = 'SENDING'")
    suspend fun resetSending(accountId: String)

    @Query("UPDATE outbox_events SET status = 'SENDING', attempts = attempts + 1, lastAttemptAtEpochMillis = :now WHERE clientEventId = :id")
    suspend fun markSending(id: String, now: Long)

    @Query("UPDATE outbox_events SET status = 'SENT', lastError = NULL, closedAtEpochMillis = :now WHERE clientEventId = :id")
    suspend fun markSent(id: String, now: Long)

    @Query("UPDATE outbox_events SET status = 'PENDING', lastError = :error WHERE clientEventId = :id")
    suspend fun markPending(id: String, error: String)

    @Query("UPDATE outbox_events SET status = 'FAILED', lastError = :error, closedAtEpochMillis = :now WHERE clientEventId = :id")
    suspend fun markFailed(id: String, error: String, now: Long)

    /** Explicit retry of a definitively failed event. Returns the number of rows changed. */
    @Query(
        "UPDATE outbox_events SET status = 'PENDING', attempts = 0, lastError = NULL, closedAtEpochMillis = NULL " +
            "WHERE clientEventId = :id AND status = 'FAILED'",
    )
    suspend fun requeue(id: String): Int

    /** [requeue] from the synchronization center: only a row of [accountId]. A second tap changes nothing. */
    @Query(
        "UPDATE outbox_events SET status = 'PENDING', attempts = 0, lastError = NULL, closedAtEpochMillis = NULL " +
            "WHERE clientEventId = :id AND accountId = :accountId AND status = 'FAILED'",
    )
    suspend fun requeueOwned(accountId: String, id: String): Int

    /** Open work (or work that needs attention) of one account, newest first, without payloads. */
    @Query(
        "SELECT clientEventId, kind, status, attempts, lastError, occurredAtEpochMillis, createdAtEpochMillis, " +
            "lastAttemptAtEpochMillis, closedAtEpochMillis FROM outbox_events " +
            "WHERE accountId = :accountId AND status IN ('PENDING', 'SENDING', 'FAILED', 'EXPIRED') " +
            "ORDER BY createdAtEpochMillis DESC LIMIT :limit",
    )
    fun observeOpen(accountId: String, limit: Int): Flow<List<OutboxItem>>

    /** Recently delivered events of one account, without payloads. */
    @Query(
        "SELECT clientEventId, kind, status, attempts, lastError, occurredAtEpochMillis, createdAtEpochMillis, " +
            "lastAttemptAtEpochMillis, closedAtEpochMillis FROM outbox_events " +
            "WHERE accountId = :accountId AND status = 'SENT' " +
            "ORDER BY COALESCE(closedAtEpochMillis, createdAtEpochMillis) DESC LIMIT :limit",
    )
    fun observeSent(accountId: String, limit: Int): Flow<List<OutboxItem>>

    @Query("SELECT status, COUNT(*) AS total FROM outbox_events WHERE accountId = :accountId GROUP BY status")
    fun observeCounts(accountId: String): Flow<List<OutboxStatusCount>>

    /** The user discards an event that will not be delivered. Pending work cannot be discarded. */
    @Query("DELETE FROM outbox_events WHERE clientEventId = :id AND accountId = :accountId AND status IN ('FAILED', 'EXPIRED')")
    suspend fun dismiss(accountId: String, id: String): Int

    @Query("SELECT status FROM outbox_events WHERE clientEventId = :id")
    fun observeStatus(id: String): Flow<String?>

    /** One account's rows of one kind, to show their delivery state (for example the review screen). */
    @Query("SELECT * FROM outbox_events WHERE accountId = :accountId AND kind = :kind")
    fun observeKind(accountId: String, kind: String): Flow<List<OutboxEventEntity>>

    /** Delivered events are kept briefly so the UI can show "sent", then dropped. */
    @Query("DELETE FROM outbox_events WHERE status = 'SENT' AND COALESCE(closedAtEpochMillis, createdAtEpochMillis) < :before")
    suspend fun purgeSentBefore(before: Long)

    /**
     * Pending events older than the backend accepts become EXPIRED instead of being deleted silently.
     * A row in SENDING is left alone: its outcome is not known yet.
     */
    @Query(
        "UPDATE outbox_events SET status = 'EXPIRED', lastError = :reason, closedAtEpochMillis = :now " +
            "WHERE status = 'PENDING' AND occurredAtEpochMillis < " +
            "CASE kind WHEN 'REVIEW' THEN :reviewBefore ELSE :before END",
    )
    suspend fun expirePending(before: Long, reviewBefore: Long, reason: String, now: Long): Int

    /** Failed and expired events stay visible for a while after closing, then are dropped. */
    @Query("DELETE FROM outbox_events WHERE status IN ('FAILED', 'EXPIRED') AND closedAtEpochMillis < :before")
    suspend fun purgeClosedBefore(before: Long)
}
