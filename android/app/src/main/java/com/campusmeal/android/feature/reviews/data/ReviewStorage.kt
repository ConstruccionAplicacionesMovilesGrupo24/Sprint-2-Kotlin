package com.campusmeal.android.feature.reviews.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The unsent review form of one account for one restaurant (at most one). Every score is optional while editing. */
@Entity(tableName = "review_drafts", primaryKeys = ["accountId", "restaurantId"])
data class ReviewDraftEntity(
    val accountId: String,
    val restaurantId: String,
    val restaurantName: String,
    val overall: Int?,
    val portion: Int?,
    val taste: Int?,
    val freshness: Int?,
    val value: Int?,
    /** Raw text of the waiting-minutes field, so an incomplete entry survives process death as typed. */
    val waitMinutesText: String,
    val updatedAtEpochMillis: Long,
)

/**
 * A review the user explicitly submitted. Written in the same transaction as its outbox row, so it exists
 * exactly when delivery was admitted. It outlives the outbox row (sent rows are purged after a day) to keep
 * showing "sent"; [deliveredAtEpochMillis] and [rejectionReason] are set by the drain's outcome.
 */
@Entity(
    tableName = "review_submissions",
    indices = [Index(value = ["accountId", "restaurantId"])],
)
data class ReviewSubmissionEntity(
    @PrimaryKey val clientReviewId: String,
    val accountId: String,
    val restaurantId: String,
    val restaurantName: String,
    val overall: Int,
    val portion: Int,
    val taste: Int,
    val freshness: Int,
    val value: Int,
    val waitMinutes: Int,
    val occurredAtEpochMillis: Long,
    /** America/Bogota date of [occurredAtEpochMillis]: the backend allows one review per restaurant and day. */
    val reviewDate: String,
    val createdAtEpochMillis: Long,
    val deliveredAtEpochMillis: Long?,
    val rejectionReason: String?,
)

/** Last public summary per account and restaurant. LRU-bounded by [accessedAtEpochMillis]. */
@Entity(tableName = "review_summary_cache", primaryKeys = ["accountId", "restaurantId", "contractVersion"])
data class ReviewSummaryCacheEntity(
    val accountId: String,
    val restaurantId: String,
    val contractVersion: Int,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
    val accessedAtEpochMillis: Long,
    /** Set when one of this account's reviews of the restaurant was confirmed: the summary is behind the server. */
    val invalidatedAtEpochMillis: Long?,
)

@Dao
interface ReviewDao {

    // Drafts

    @Query("SELECT * FROM review_drafts WHERE accountId = :accountId AND restaurantId = :restaurantId")
    suspend fun draft(accountId: String, restaurantId: String): ReviewDraftEntity?

    @Upsert
    suspend fun upsertDraft(draft: ReviewDraftEntity)

    @Query("DELETE FROM review_drafts WHERE accountId = :accountId AND restaurantId = :restaurantId")
    suspend fun deleteDraft(accountId: String, restaurantId: String)

    // Submissions

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmission(submission: ReviewSubmissionEntity)

    @Query("SELECT * FROM review_submissions WHERE clientReviewId = :clientReviewId")
    suspend fun submission(clientReviewId: String): ReviewSubmissionEntity?

    @Query(
        "SELECT * FROM review_submissions WHERE accountId = :accountId AND restaurantId = :restaurantId " +
            "ORDER BY createdAtEpochMillis DESC",
    )
    fun observeSubmissions(accountId: String, restaurantId: String): Flow<List<ReviewSubmissionEntity>>

    /** A submission for that Bogotá day that was not rejected: a second one would be refused with 409. */
    @Query(
        "SELECT COUNT(*) FROM review_submissions WHERE accountId = :accountId AND restaurantId = :restaurantId " +
            "AND reviewDate = :reviewDate AND rejectionReason IS NULL",
    )
    suspend fun activeSubmissionsOn(accountId: String, restaurantId: String, reviewDate: String): Int

    @Query("UPDATE review_submissions SET deliveredAtEpochMillis = :at, rejectionReason = NULL WHERE clientReviewId = :id")
    suspend fun markDelivered(id: String, at: Long)

    @Query("UPDATE review_submissions SET rejectionReason = :reason WHERE clientReviewId = :id AND deliveredAtEpochMillis IS NULL")
    suspend fun markRejected(id: String, reason: String)

    // Summary cache

    @Query(
        "SELECT * FROM review_summary_cache WHERE accountId = :accountId AND restaurantId = :restaurantId " +
            "ORDER BY contractVersion DESC LIMIT 1",
    )
    suspend fun summary(accountId: String, restaurantId: String): ReviewSummaryCacheEntity?

    @Upsert
    suspend fun upsertSummary(summary: ReviewSummaryCacheEntity)

    @Query("UPDATE review_summary_cache SET accessedAtEpochMillis = :at WHERE accountId = :accountId AND restaurantId = :restaurantId")
    suspend fun touchSummary(accountId: String, restaurantId: String, at: Long)

    @Query(
        "UPDATE review_summary_cache SET invalidatedAtEpochMillis = :at " +
            "WHERE accountId = :accountId AND restaurantId = :restaurantId",
    )
    suspend fun invalidateSummary(accountId: String, restaurantId: String, at: Long)

    /** Emits whenever the cached summary changes, including an invalidation after a confirmed review. */
    @Query(
        "SELECT invalidatedAtEpochMillis FROM review_summary_cache " +
            "WHERE accountId = :accountId AND restaurantId = :restaurantId ORDER BY contractVersion DESC LIMIT 1",
    )
    fun observeSummaryInvalidation(accountId: String, restaurantId: String): Flow<Long?>

    /** Keeps the [keep] most recently used summaries of the account. */
    @Query(
        "DELETE FROM review_summary_cache WHERE accountId = :accountId AND rowid NOT IN (" +
            "SELECT rowid FROM review_summary_cache WHERE accountId = :accountId " +
            "ORDER BY accessedAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun pruneSummaries(accountId: String, keep: Int)

    // Account lifecycle. Undelivered submissions are admitted work and keep their owner, like the outbox rows.

    @Query("DELETE FROM review_drafts WHERE accountId = :accountId")
    suspend fun deleteDrafts(accountId: String)

    @Query("DELETE FROM review_drafts WHERE accountId != :accountId")
    suspend fun deleteDraftsExcept(accountId: String)

    @Query("DELETE FROM review_summary_cache WHERE accountId = :accountId")
    suspend fun deleteSummaries(accountId: String)

    @Query("DELETE FROM review_summary_cache WHERE accountId != :accountId")
    suspend fun deleteSummariesExcept(accountId: String)

    @Query(
        "DELETE FROM review_submissions WHERE accountId = :accountId " +
            "AND (deliveredAtEpochMillis IS NOT NULL OR rejectionReason IS NOT NULL)",
    )
    suspend fun deleteSettledSubmissions(accountId: String)

    @Query(
        "DELETE FROM review_submissions WHERE accountId != :accountId " +
            "AND (deliveredAtEpochMillis IS NOT NULL OR rejectionReason IS NOT NULL)",
    )
    suspend fun deleteSettledSubmissionsExcept(accountId: String)
}
