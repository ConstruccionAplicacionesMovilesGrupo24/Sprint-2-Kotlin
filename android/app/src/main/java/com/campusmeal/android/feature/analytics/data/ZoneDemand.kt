package com.campusmeal.android.feature.analytics.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query as HttpQuery

/** BQ9 demand report. ANALYST only; USER gets 403. */
interface ZoneDemandApi {

    @GET("analytics/zone-demand")
        suspend fun report(
        @Header("Authorization") authorization: String,
        @HttpQuery("from") from: String,
        @HttpQuery("to") to: String,
    ): ZoneDemandResponseDto
}

@Serializable
data class ZoneDemandResponseDto(
    val contractVersion: Int,
    val from: String,
    val to: String,
    val timezone: String,
    val generatedAt: String,
    val minDistinctUsersPerCell: Int,
    val containsSyntheticData: Boolean,
    val interpretation: String,
    val suppressedCells: Int,
    val zones: List<ZoneDemandZoneDto>,
)

@Serializable
data class ZoneDemandZoneDto(
    val zone: String,
    val totals: ZoneDemandTotalsDto,
    val categories: List<ZoneDemandCategoryDto>,
    val searchedBudgetRanges: List<ZoneDemandSearchedBudgetRangeDto>,
    val interactionPriceRanges: List<ZoneDemandInteractionPriceRangeDto>,
    val hours: List<ZoneDemandHourDto>,
)

@Serializable
data class ZoneDemandTotalsDto(
    val searches: Int?,
    val detailOpenings: Int?,
    val selections: Int?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
)

@Serializable
data class ZoneDemandCategoryDto(
    val category: String,
    val selections: Int?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
)

@Serializable
data class ZoneDemandSearchedBudgetRangeDto(
    val range: String,
    val searches: Int?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
)
@Serializable
data class ZoneDemandInteractionPriceRangeDto(
    val range: String,
    val detailOpenings: Int?,
    val selections: Int?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
)
@Serializable
data class ZoneDemandHourDto(
    val hour: Int,
    val selections: Int?,
    val distinctUsers: Int?,
    val suppressed: Boolean,
    val searches: Int?,
    val detailOpenings: Int?,
)
    
/** Cached BQ9 report for one account, zone and date range. */
@Entity(tableName = "zone_demand_reports", primaryKeys = ["accountId", "zone", "fromDate", "toDate", "contractVersion"])
data class ZoneDemandReportEntity(
    val accountId: String,
    val zone: String,
    val fromDate: String,
    val toDate: String,
    val contractVersion: Int,
    val responseJson: String,
    val fetchedAtEpochMillis: Long
)

@Dao
interface ZoneDemandReportDao {

    @Upsert
    suspend fun upsert(report: ZoneDemandReportEntity)

    @Query(
        "SELECT * FROM zone_demand_reports " +
            "WHERE accountId = :accountId AND zone = :zone AND fromDate = :fromDate AND toDate = :toDate " +
            "ORDER BY contractVersion DESC LIMIT 1",
    )
    suspend fun getLatest(
        accountId: String,
        zone: String,
        fromDate: String,
        toDate: String,
    ): ZoneDemandReportEntity?

    @Query("DELETE FROM zone_demand_reports WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM zone_demand_reports WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
