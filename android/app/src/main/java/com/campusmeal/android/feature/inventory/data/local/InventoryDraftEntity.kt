package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome
import kotlinx.coroutines.flow.Flow

/**
 * An add or edit form saved on the device, with the raw text the user typed (possibly incomplete or
 * invalid). [draftId] is `create` for the account's single add draft and `edit:{itemId}` for an edit,
 * so there is at most one of each. [lastSubmitOutcome] records the last submission attempt; it is
 * persisted as SUBMITTING before the request starts, so an interrupted write is never forgotten.
 */
@Entity(tableName = "inventory_drafts", primaryKeys = ["accountId", "draftId"])
data class InventoryDraftEntity(
    val accountId: String,
    val draftId: String,
    /** Null for the add draft. */
    val targetItemId: String?,
    val name: String,
    val quantityText: String,
    val unit: String,
    val expirationDateText: String,
    val updatedAtEpochMillis: Long,
    val lastSubmitOutcome: SubmitOutcome,
    /** When the last submission started; reconciliation needs a complete sync after it. */
    val lastSubmitAtEpochMillis: Long?,
    val lastHttpCode: Int?,
    val lastErrorCode: String?,
)

@Dao
interface InventoryDraftDao {

    @Query("SELECT * FROM inventory_drafts WHERE accountId = :accountId ORDER BY updatedAtEpochMillis DESC")
    fun observe(accountId: String): Flow<List<InventoryDraftEntity>>

    @Query("SELECT * FROM inventory_drafts WHERE accountId = :accountId AND draftId = :draftId")
    suspend fun get(accountId: String, draftId: String): InventoryDraftEntity?

    @Upsert
    suspend fun upsert(draft: InventoryDraftEntity)

    @Query(
        "UPDATE inventory_drafts SET lastSubmitOutcome = :outcome, lastSubmitAtEpochMillis = :atEpochMillis, " +
            "lastHttpCode = :httpCode, lastErrorCode = :errorCode WHERE accountId = :accountId AND draftId = :draftId",
    )
    suspend fun updateOutcome(
        accountId: String,
        draftId: String,
        outcome: SubmitOutcome,
        atEpochMillis: Long?,
        httpCode: Int?,
        errorCode: String?,
    )

    /** A SUBMITTING row nobody is sending any more was interrupted (process death, cancellation, session end). */
    @Query(
        "UPDATE inventory_drafts SET lastSubmitOutcome = 'OUTCOME_UNKNOWN' WHERE accountId = :accountId " +
            "AND lastSubmitOutcome = 'SUBMITTING' AND draftId NOT IN (:inFlightDraftIds)",
    )
    suspend fun markInterrupted(accountId: String, inFlightDraftIds: List<String>)

    /** Seven-day retention. Unresolved submissions stay until the user reviews them. */
    @Query(
        "DELETE FROM inventory_drafts WHERE accountId = :accountId AND updatedAtEpochMillis < :cutoffEpochMillis " +
            "AND lastSubmitOutcome IN ('NONE', 'NOT_SENT', 'REJECTED')",
    )
    suspend fun deleteOlderThan(accountId: String, cutoffEpochMillis: Long)

    @Query("DELETE FROM inventory_drafts WHERE accountId = :accountId AND draftId = :draftId")
    suspend fun delete(accountId: String, draftId: String)

    @Query("DELETE FROM inventory_drafts WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("DELETE FROM inventory_drafts WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
