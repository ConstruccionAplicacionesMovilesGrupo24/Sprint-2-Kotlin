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
interface FeatureAdoptionApi {

    @GET("analytics/feature-adoption")
    suspend fun report(
        @Header("Authorization") authorization: String,
        @HttpQuery("month") month: String,
    ): FeatureAdoptionDto
}

@Serializable
data class FeatureAdoptionDto(
    val month: String,
    /** `OK` or `NO_DATA` (no monthly active users). */
    val status: String,
    val monthlyActiveUsers: Int,
    val threshold: Double,
    val lowUseBelowUses: Int,
    val partialMonth: Boolean,
    val eventsReceived: Int,
    val includesSyntheticData: Boolean,
    val generatedAt: String,
    val results: List<FeatureAdoptionRowDto>,
)

@Serializable
data class FeatureAdoptionRowDto(
    val feature: String,
    val lowUseUsers: Int,
    val lowUseRate: Double,
    val flagged: Boolean,
)

/** One cached report per account and month (S3-11). [responseJson] is the body as received. */
@Entity(tableName = "feature_adoption_reports", primaryKeys = ["accountId", "month"])
data class FeatureAdoptionReportEntity(
    val accountId: String,
    val month: String,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Dao
interface FeatureAdoptionReportDao {

    @Upsert
    suspend fun upsert(report: FeatureAdoptionReportEntity)

    @Query("SELECT * FROM feature_adoption_reports WHERE accountId = :accountId AND month = :month")
    suspend fun get(accountId: String, month: String): FeatureAdoptionReportEntity?

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
