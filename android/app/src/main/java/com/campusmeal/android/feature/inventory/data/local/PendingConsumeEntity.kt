package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome
import kotlinx.coroutines.flow.Flow

/**
 * A consume (`DELETE inventory/{id}`) whose result is not confirmed yet. It is a marker, not a queue:
 * nothing is ever sent from it. It exists while the request runs (SUBMITTING) and, when the outcome
 * is unknown, until a later complete sync shows whether the item is gone. [itemName] lets the screen
 * name the item after it left the snapshot.
 */
@Entity(tableName = "inventory_pending_consumes", primaryKeys = ["accountId", "itemId"])
data class PendingConsumeEntity(
    val accountId: String,
    val itemId: String,
    val itemName: String,
    val outcome: SubmitOutcome,
    val startedAtEpochMillis: Long,
)

@Dao
interface PendingConsumeDao {

    @Query("SELECT * FROM inventory_pending_consumes WHERE accountId = :accountId ORDER BY startedAtEpochMillis ASC")
    fun observe(accountId: String): Flow<List<PendingConsumeEntity>>

    @Query("SELECT * FROM inventory_pending_consumes WHERE accountId = :accountId AND itemId = :itemId")
    suspend fun get(accountId: String, itemId: String): PendingConsumeEntity?

    @Upsert
    suspend fun upsert(marker: PendingConsumeEntity)

    @Query(
        "UPDATE inventory_pending_consumes SET outcome = 'OUTCOME_UNKNOWN' WHERE accountId = :accountId " +
            "AND outcome = 'SUBMITTING' AND itemId NOT IN (:inFlightItemIds)",
    )
    suspend fun markInterrupted(accountId: String, inFlightItemIds: List<String>)

    @Query("DELETE FROM inventory_pending_consumes WHERE accountId = :accountId AND itemId = :itemId")
    suspend fun delete(accountId: String, itemId: String)

    @Query("DELETE FROM inventory_pending_consumes WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("DELETE FROM inventory_pending_consumes WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
