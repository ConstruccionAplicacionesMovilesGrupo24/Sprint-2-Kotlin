package com.campusmeal.android.feature.decision.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope

/**
 * One BQ5 comparison exactly as the backend returned it (S3-11). [responseJson] is the raw response
 * body: Android never recalculates or re-ranks it. Only the coarse context is kept (no coordinates).
 */
@Entity(
    tableName = "comparison_history",
    indices = [Index(value = ["accountId", "comparedAtEpochMillis"])],
)
data class ComparisonHistoryEntity(
    @PrimaryKey val recommendationId: String,
    val accountId: String,
    val comparedAtEpochMillis: Long,
    val campusId: String?,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val responseJson: String,
)

@Dao
interface ComparisonHistoryDao {

    @Upsert
    suspend fun upsert(entry: ComparisonHistoryEntity)

    @Query("SELECT * FROM comparison_history WHERE accountId = :accountId ORDER BY comparedAtEpochMillis DESC LIMIT 1")
    suspend fun latest(accountId: String): ComparisonHistoryEntity?

    /** Keeps the [keep] most recent comparisons of the account. */
    @Query(
        "DELETE FROM comparison_history WHERE accountId = :accountId AND recommendationId NOT IN (" +
            "SELECT recommendationId FROM comparison_history WHERE accountId = :accountId " +
            "ORDER BY comparedAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun prune(accountId: String, keep: Int)

    @Query("DELETE FROM comparison_history WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM comparison_history WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}

/**
 * Bounded, per-account history of comparisons. Personal data: deleted on logout and when another
 * account is activated. Writes run only while their scope is active.
 */
class ComparisonHistoryStore(
    private val database: CampusMealDatabase,
    private val coordinator: AccountSessionCoordinator,
) : AccountScopedStore {

    private val dao = database.comparisonHistoryDao()

    suspend fun save(scope: AccountSessionScope, entry: ComparisonHistoryEntity) {
        coordinator.runIfCurrent(scope) {
            database.withTransaction {
                dao.upsert(entry)
                dao.prune(scope.accountId, MAX_ENTRIES_PER_ACCOUNT)
            }
        }
    }

    suspend fun latest(scope: AccountSessionScope): ComparisonHistoryEntity? =
        if (coordinator.isCurrent(scope)) dao.latest(scope.accountId) else null

    override suspend fun deleteAccount(accountId: String) = dao.delete(accountId)

    override suspend fun deleteAllExcept(accountId: String) = dao.deleteAllExcept(accountId)

    companion object {
        const val MAX_ENTRIES_PER_ACCOUNT = 10
    }
}
