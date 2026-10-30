package com.campusmeal.android.feature.analytics.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query as HttpQuery

/** BQ6 report (`docs/SPRINT3_BQ6.md` in CampusMealBack). ANALYST only; USER gets 403. */
interface ZoneDemandApi {

    @GET("analytics/zone-demand")
        suspend fun report(
        @Header("Authorization") authorization: String,
        @HttpQuery("from") from: String,
        @HttpQuery("to") to: String,
    ): ZoneDemandDto
}

@Serializable
data class ZoneDemandDto(
    val contractVersion: Int,
    val from: String,
    val to: String,
    val timezone: String,
    val generatedAt: String,
    val staminDistinctUsersPerCell: Int,
    val containsSyntheticData: Boolean,
    val interpretation: String,
    val supressedCells: Int,
    val results: List<ZoneDemandRowDto>,
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
    val searches: Int,
    val detailOpenings: Int,
    val selections: Int,
    val distinctUsers: Int,
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
    
/** One cached report per account and month (S3-11). [responseJson] is the body as received. */
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

    @Query("SELECT * FROM zone_demand_reports WHERE accountId = :accountId AND zone = :zone AND fromDate = :fromDate AND toDate = :toDate AND contractVersion = :contractVersion")
    suspend fun get(accountId: String, zone: String, fromDate: String, toDate: String, contractVersion: Int): ZoneDemandReportEntity?

    /** Keeps the [keep] most recently fetched reports of the account. */
    @Query(
        "DELETE FROM feature_adoption_reports WHERE accountId = :accountId AND month NOT IN (" +
            "SELECT month FROM feature_adoption_reports WHERE accountId = :accountId " +
            "ORDER BY fetchedAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun prune(accountId: String, keep: Int)

    @Query("DELETE FROM feature_adoption_reports WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM feature_adoption_reports WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}

enum class ReportSource { NETWORK, CACHE }

sealed interface FeatureAdoptionResult {

    /** [freshness] tells whether a cached copy is still within its 15-minute TTL. */
    data class Content(
        val report: FeatureAdoptionDto,
        val source: ReportSource,
        val freshness: Freshness.Synced,
    ) : FeatureAdoptionResult

    /** The backend says this account is not an ANALYST. Its cached reports are removed. */
    data object Forbidden : FeatureAdoptionResult

    data object Unauthorized : FeatureAdoptionResult

    /** Offline (or backend error) and no copy of this month is stored for this account. */
    data class Unavailable(val httpCode: Int?) : FeatureAdoptionResult
}

/**
 * BQ6 report with a per-account, per-month cache (S3-11).
 *
 * - A copy younger than [CachePolicy.analyticsReport] is shown without a request.
 * - Otherwise the backend is asked; if it cannot be reached, the stored copy of **the same account and
 *   month** is shown with its date. Another month or account never reuses those figures.
 * - 403 drops the account's cached reports: a local role never authorizes reading them.
 * - Personal data of the account: deleted on logout and when another account is activated.
 */
class FeatureAdoptionRepository(
    private val api: FeatureAdoptionApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val database: CampusMealDatabase,
    private val preferences: DataStore<Preferences>,
    private val cachePolicy: CachePolicy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AccountScopedStore {

    private val dao = database.featureAdoptionReportDao()
    private val json = ApiClientFactory.json

    suspend fun report(scope: AccountSessionScope, month: String, forceRefresh: Boolean = false): FeatureAdoptionResult {
        val cached = if (coordinator.isCurrent(scope)) dao.get(scope.accountId, month)?.toContent() else null
        if (!forceRefresh && cached?.freshness is Freshness.Fresh) return cached

        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return FeatureAdoptionResult.Unauthorized
        return when (val result = apiCall { api.report(authorization, month) }) {
            is ApiResult.Success -> {
                val now = currentTimeMillis()
                val entity = FeatureAdoptionReportEntity(
                    accountId = scope.accountId,
                    month = month,
                    responseJson = json.encodeToString(FeatureAdoptionDto.serializer(), result.data),
                    fetchedAtEpochMillis = now,
                )
                coordinator.runIfCurrent(scope) {
                    database.withTransaction {
                        dao.upsert(entity)
                        dao.prune(scope.accountId, MAX_REPORTS_PER_ACCOUNT)
                    }
                }
                FeatureAdoptionResult.Content(result.data, ReportSource.NETWORK, Freshness.Fresh(now))
            }
            ApiResult.Unauthorized -> FeatureAdoptionResult.Unauthorized
            is ApiResult.HttpError -> when (result.code) {
                403 -> {
                    coordinator.runIfCurrent(scope) { dao.delete(scope.accountId) }
                    FeatureAdoptionResult.Forbidden
                }
                else -> cached ?: FeatureAdoptionResult.Unavailable(result.code)
            }
            is ApiResult.NetworkUnavailable, is ApiResult.InvalidResponse -> cached ?: FeatureAdoptionResult.Unavailable(null)
        }
    }

    /** The month this account last looked at, or null. */
    suspend fun savedMonth(scope: AccountSessionScope): String? =
        if (coordinator.isCurrent(scope)) preferences.data.first()[monthKey(scope.accountId)] else null

    suspend fun saveMonth(scope: AccountSessionScope, month: String) {
        coordinator.runIfCurrent(scope) { preferences.edit { it[monthKey(scope.accountId)] = month } }
    }

    override suspend fun deleteAccount(accountId: String) {
        dao.delete(accountId)
        preferences.edit { it.remove(monthKey(accountId)) }
    }

    override suspend fun deleteAllExcept(accountId: String) {
        dao.deleteAllExcept(accountId)
        val keep = monthKey(accountId)
        preferences.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(MONTH_KEY_PREFIX) && it != keep }.forEach { prefs.remove(it) }
        }
    }

    private fun FeatureAdoptionReportEntity.toContent(): FeatureAdoptionResult.Content? =
        try {
            FeatureAdoptionResult.Content(
                report = json.decodeFromString(FeatureAdoptionDto.serializer(), responseJson),
                source = ReportSource.CACHE,
                freshness = Freshness.synced(fetchedAtEpochMillis, cachePolicy.analyticsReport, currentTimeMillis()),
            )
        } catch (_: SerializationException) {
            null
        }

    private companion object {
        const val MAX_REPORTS_PER_ACCOUNT = 12
        const val MONTH_KEY_PREFIX = "analytics.featureAdoption.month."

        fun monthKey(accountId: String) = stringPreferencesKey("$MONTH_KEY_PREFIX$accountId")
    }
}
