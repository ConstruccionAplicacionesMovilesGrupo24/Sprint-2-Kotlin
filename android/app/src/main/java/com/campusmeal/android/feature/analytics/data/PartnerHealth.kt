package com.campusmeal.android.feature.analytics.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query as HttpQuery

/** BQ13 partner-health report (S4-03 backend). ANALYST only; USER gets 403. */
interface PartnerHealthApi {

    @GET("analytics/partner-health")
    suspend fun report(
        @Header("Authorization") authorization: String,
        @HttpQuery("from") from: String,
        @HttpQuery("to") to: String,
        @HttpQuery("includeSynthetic") includeSynthetic: Boolean,
    ): PartnerHealthResponseDto
}

object MenuStatus {
    /** A confirmed menu revision within the 7 Bogotá days ending at the cutoff. */
    const val FRESH = "FRESH"

    /** Revisions exist, none in that window. */
    const val STALE = "STALE"

    /** No revision recorded up to the cutoff: update history unknown (not "never updated"). */
    const val UNKNOWN = "UNKNOWN"
}

@Serializable
data class PartnerHealthResponseDto(
    val contractVersion: Int,
    val from: String,
    val to: String,
    /** Exclusive instant: data at or after it is not counted. */
    val cutoff: String,
    val freshnessWindow: FreshnessWindowDto,
    val generatedAt: String,
    val includeSynthetic: Boolean,
    val includesSyntheticData: Boolean,
    /** First recorded menu revision; before it, update history is unknown. Null when none exists. */
    val revisionHistoryStartsAt: String?,
    val minDistinctUsers: Int,
    val restaurants: List<PartnerHealthRestaurantDto>,
    val cohorts: List<PartnerHealthCohortDto>,
    val associations: List<PartnerHealthAssociationDto>,
    val limitations: List<String>,
)

@Serializable
data class FreshnessWindowDto(val days: Int, val from: String, val to: String)

@Serializable
data class PartnerHealthRestaurantDto(
    val restaurantId: String,
    val name: String,
    val category: String,
    val menuStatus: String,
    val lastRevisionAt: String?,
    val lastRevisionDate: String?,
    val daysSinceRevision: Int?,
    val revisionsInRange: Int,
    val recommendations: RecommendationBehaviorDto,
    val reviews: ReviewAggregateDto,
    val synthetic: Boolean,
)

/** Values are null while [suppressed]; [selectionRate] is also null when there were 0 exposures. */
@Serializable
data class RecommendationBehaviorDto(
    val exposures: Int?,
    val selections: Int?,
    val selectionRate: Double?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
)

@Serializable
data class ReviewAggregateDto(
    val reviewCount: Int?,
    val distinctReviewers: Int?,
    val meanOverall: Double?,
    val suppressed: Boolean,
)

/** Pooled over non-suppressed restaurants only; the suppressed ones are counted but their values excluded. */
@Serializable
data class PartnerHealthCohortDto(
    val menuStatus: String,
    val restaurants: Int,
    val restaurantsWithSuppressedBehavior: Int,
    val exposures: Int,
    val selections: Int,
    val selectionRate: Double?,
    val restaurantsWithSuppressedReviews: Int,
    val reviewCount: Int,
    val meanOverall: Double?,
)

@Serializable
data class PartnerHealthAssociationDto(
    val x: String,
    val y: String,
    val method: String,
    /** `COMPUTED` or `INSUFFICIENT_DATA`. */
    val status: String,
    val coefficient: Double?,
    val sampleSize: Int,
    val reason: String?,
)

/** Cached BQ13 report for one account, date range, synthetic choice and contract version. */
@Entity(
    tableName = "partner_health_reports",
    primaryKeys = ["accountId", "fromDate", "toDate", "includeSynthetic", "contractVersion"],
)
data class PartnerHealthReportEntity(
    val accountId: String,
    val fromDate: String,
    val toDate: String,
    val includeSynthetic: Boolean,
    val contractVersion: Int,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Dao
interface PartnerHealthReportDao {

    @Upsert
    suspend fun upsert(report: PartnerHealthReportEntity)

    @Query(
        "SELECT * FROM partner_health_reports WHERE accountId = :accountId AND fromDate = :fromDate " +
            "AND toDate = :toDate AND includeSynthetic = :includeSynthetic ORDER BY contractVersion DESC LIMIT 1",
    )
    suspend fun latest(accountId: String, fromDate: String, toDate: String, includeSynthetic: Boolean): PartnerHealthReportEntity?

    /** Keeps the [keep] most recently fetched reports of the account. */
    @Query(
        "DELETE FROM partner_health_reports WHERE accountId = :accountId AND rowid NOT IN (" +
            "SELECT rowid FROM partner_health_reports WHERE accountId = :accountId " +
            "ORDER BY fetchedAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun prune(accountId: String, keep: Int)

    @Query("DELETE FROM partner_health_reports WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM partner_health_reports WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
