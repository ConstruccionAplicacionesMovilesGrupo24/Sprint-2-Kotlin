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

    @Query("UPDATE outbox_events SET status = 'SENDING', attempts = attempts + 1 WHERE clientEventId = :id")
    suspend fun markSending(id: String)

    @Query("UPDATE outbox_events SET status = 'SENT', lastError = NULL WHERE clientEventId = :id")
    suspend fun markSent(id: String)

    @Query("UPDATE outbox_events SET status = 'PENDING', lastError = :error WHERE clientEventId = :id")
    suspend fun markPending(id: String, error: String)

    @Query("UPDATE outbox_events SET status = 'FAILED', lastError = :error WHERE clientEventId = :id")
    suspend fun markFailed(id: String, error: String)

    /** Explicit retry of a definitively failed event. Returns the number of rows changed. */
    @Query("UPDATE outbox_events SET status = 'PENDING', attempts = 0, lastError = NULL WHERE clientEventId = :id AND status = 'FAILED'")
    suspend fun requeue(id: String): Int

    @Query("SELECT status FROM outbox_events WHERE clientEventId = :id")
    fun observeStatus(id: String): Flow<String?>

    /** Delivered events are kept briefly so the UI can show "sent", then dropped. */
    @Query("DELETE FROM outbox_events WHERE status = 'SENT' AND createdAtEpochMillis < :before")
    suspend fun purgeSentBefore(before: Long)

    /** The backend rejects events older than 90 days, so older ones can never be delivered. */
    @Query("DELETE FROM outbox_events WHERE createdAtEpochMillis < :before")
    suspend fun purgeOlderThan(before: Long)
}
