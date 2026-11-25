package com.campusmeal.android.feature.analytics.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query as HttpQuery

/** BQ1 report (`docs/SPRINT4_BQ1.md` in CampusMealBack). ANALYST only; USER gets 403. */
interface PerformanceHealthApi {

    @GET("analytics/performance")
    suspend fun report(
        @Header("Authorization") authorization: String,
        @HttpQuery("from") from: String,
        @HttpQuery("to") to: String,
        @HttpQuery("includeSynthetic") includeSynthetic: Boolean,
    ): PerformanceReportDto
}

@Serializable
data class PerformanceReportDto(
    val contractVersion: Int,
    val from: String,
    val to: String,
    val generatedAt: String,
    val includeSynthetic: Boolean,
    val includesSyntheticData: Boolean,
    val thresholds: PerformanceThresholdsDto,
    val samplePolicy: PerformanceSamplePolicyDto,
    val combinations: List<PerformanceCombinationDto>,
    val coverage: PerformanceCoverageDto,
    val limitations: List<String>,
)

@Serializable
data class PerformanceThresholdsDto(val crashRate: Double, val p95Ms: Long, val comparison: String)

@Serializable
data class PerformanceSamplePolicyDto(val minSessions: Int, val minFreshLoads: Int)

@Serializable
data class PerformanceCombinationDto(
    val platform: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val flagged: Boolean,
    val sessions: SessionHealthDto,
    val loading: ComparisonLoadingDto,
    val synthetic: Boolean,
)

@Serializable
data class SessionHealthDto(
    val started: Int,
    val crashed: Int,
    val distinctUsers: Int,
    /** Null without sessions. */
    val crashRate: Double?,
    /** `NO_DATA`, `INSUFFICIENT` or `SUFFICIENT`. */
    val confidence: String,
    val flagged: Boolean,
)

@Serializable
data class ComparisonLoadingDto(
    val freshNetwork: Int,
    /** Null without fresh-network loads. */
    val p95Ms: Long?,
    val confidence: String,
    val flagged: Boolean,
    val cachedRestore: Int,
    val offlineHistory: Int,
    val errors: Int,
    val timeouts: Int,
    val cancelled: Int,
    val failureRate: Double?,
)

@Serializable
data class PerformancePlatformCoverageDto(val platform: String, val events: Int)

@Serializable
data class PerformanceCoverageDto(
    val events: Int,
    val platforms: List<PerformancePlatformCoverageDto>,
    val crashesWithoutSession: Int,
    val lateEvents: Int,
    val excludedInjectedSessions: Int,
    val firstOccurredAt: String?,
    val lastOccurredAt: String?,
    val lastReceivedAt: String?,
    val lateSyncPossibleUntil: String,
)

/** Cached BQ1 report for one account, date range, synthetic choice and contract version. */
@Entity(
    tableName = "performance_reports",
    primaryKeys = ["accountId", "fromDate", "toDate", "includeSynthetic", "contractVersion"],
)
data class PerformanceReportEntity(
    val accountId: String,
    val fromDate: String,
    val toDate: String,
    val includeSynthetic: Boolean,
    val contractVersion: Int,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Dao
interface PerformanceReportDao {

    @Upsert
    suspend fun upsert(report: PerformanceReportEntity)

    @Query(
        "SELECT * FROM performance_reports WHERE accountId = :accountId AND fromDate = :fromDate " +
            "AND toDate = :toDate AND includeSynthetic = :includeSynthetic ORDER BY contractVersion DESC LIMIT 1",
    )
    suspend fun latest(accountId: String, fromDate: String, toDate: String, includeSynthetic: Boolean): PerformanceReportEntity?

    /** Keeps the [keep] most recently fetched reports of the account. */
    @Query(
        "DELETE FROM performance_reports WHERE accountId = :accountId AND rowid NOT IN (" +
            "SELECT rowid FROM performance_reports WHERE accountId = :accountId " +
            "ORDER BY fetchedAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun prune(accountId: String, keep: Int)

    @Query("DELETE FROM performance_reports WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM performance_reports WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
