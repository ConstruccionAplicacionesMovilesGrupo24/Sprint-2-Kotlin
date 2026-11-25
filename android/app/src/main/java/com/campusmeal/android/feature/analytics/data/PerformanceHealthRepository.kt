package com.campusmeal.android.feature.analytics.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.serialization.SerializationException

/** Inclusive Bogotá date range plus whether labeled demo rows and injected crashes are counted. */
data class PerformanceFilters(val from: String, val to: String, val includeSynthetic: Boolean)

sealed interface PerformanceHealthResult {

    data class Content(
        val report: PerformanceReportDto,
        val source: ReportSource,
        val freshness: Freshness.Synced,
    ) : PerformanceHealthResult

    /** 403: not an analyst. The account's saved reports were deleted. */
    data object Forbidden : PerformanceHealthResult

    data object Unauthorized : PerformanceHealthResult

    /** 400: the backend refused the range. */
    data object Rejected : PerformanceHealthResult

    /** Nothing saved for these filters and the request failed; [httpCode] null for connectivity. */
    data class Unavailable(val httpCode: Int?) : PerformanceHealthResult
}

/**
 * BQ1 report (S4-10): Room cache for the exact query (account, range, synthetic choice and contract
 * version; 15 minutes, 20 reports per account) and the last filters per account in DataStore. Another
 * range or account never reuses these figures, and a 403 removes them.
 */
class PerformanceHealthRepository(
    private val api: PerformanceHealthApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val database: CampusMealDatabase,
    private val preferences: DataStore<Preferences>,
    private val cachePolicy: CachePolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountScopedStore {

    private val dao = database.performanceReportDao()
    private val json = ApiClientFactory.json

    suspend fun report(scope: AccountSessionScope, filters: PerformanceFilters, forceRefresh: Boolean): PerformanceHealthResult {
        val cached = if (coordinator.isCurrent(scope)) {
            dao.latest(scope.accountId, filters.from, filters.to, filters.includeSynthetic)?.toContent()
        } else {
            null
        }
        if (!forceRefresh && cached?.freshness is Freshness.Fresh) return cached

        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return PerformanceHealthResult.Unauthorized
        return when (val result = apiCall { api.report(authorization, filters.from, filters.to, filters.includeSynthetic) }) {
            is ApiResult.Success -> {
                val report = result.data
                // An answer for other filters is never stored under these ones.
                if (report.from != filters.from || report.to != filters.to || report.includeSynthetic != filters.includeSynthetic) {
                    return cached ?: PerformanceHealthResult.Unavailable(null)
                }
                val now = clock()
                coordinator.runIfCurrent(scope) {
                    database.withTransaction {
                        dao.upsert(
                            PerformanceReportEntity(
                                accountId = scope.accountId,
                                fromDate = filters.from,
                                toDate = filters.to,
                                includeSynthetic = filters.includeSynthetic,
                                contractVersion = report.contractVersion,
                                responseJson = json.encodeToString(PerformanceReportDto.serializer(), report),
                                fetchedAtEpochMillis = now,
                            ),
                        )
                        dao.prune(scope.accountId, MAX_CACHED_REPORTS)
                    }
                }
                PerformanceHealthResult.Content(report, ReportSource.NETWORK, Freshness.Fresh(now))
            }
            ApiResult.Unauthorized -> PerformanceHealthResult.Unauthorized
            is ApiResult.HttpError -> when (result.code) {
                // Access was removed: saved reports of this account must not stay readable.
                403 -> {
                    coordinator.runIfCurrent(scope) { dao.delete(scope.accountId) }
                    PerformanceHealthResult.Forbidden
                }
                400 -> PerformanceHealthResult.Rejected
                else -> cached ?: PerformanceHealthResult.Unavailable(result.code)
            }
            is ApiResult.NetworkUnavailable, is ApiResult.InvalidResponse -> cached ?: PerformanceHealthResult.Unavailable(null)
        }
    }

    suspend fun savedFilters(scope: AccountSessionScope): PerformanceFilters? {
        if (!coordinator.isCurrent(scope)) return null
        val prefs = preferences.data.first()
        val from = prefs[fromKey(scope.accountId)] ?: return null
        val to = prefs[toKey(scope.accountId)] ?: return null
        return PerformanceFilters(from, to, prefs[syntheticKey(scope.accountId)] ?: false)
    }

    suspend fun saveFilters(scope: AccountSessionScope, filters: PerformanceFilters) {
        coordinator.runIfCurrent(scope) {
            preferences.edit {
                it[fromKey(scope.accountId)] = filters.from
                it[toKey(scope.accountId)] = filters.to
                it[syntheticKey(scope.accountId)] = filters.includeSynthetic
            }
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        dao.delete(accountId)
        preferences.edit {
            it.remove(fromKey(accountId))
            it.remove(toKey(accountId))
            it.remove(syntheticKey(accountId))
        }
    }

    override suspend fun deleteAllExcept(accountId: String) {
        dao.deleteAllExcept(accountId)
        preferences.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(PREFIX) && !it.name.endsWith(".$accountId") }
                .forEach { prefs.remove(it) }
        }
    }

    private fun PerformanceReportEntity.toContent(): PerformanceHealthResult.Content? =
        try {
            PerformanceHealthResult.Content(
                report = json.decodeFromString(PerformanceReportDto.serializer(), responseJson),
                source = ReportSource.CACHE,
                freshness = Freshness.synced(fetchedAtEpochMillis, cachePolicy.performanceReport, clock()),
            )
        } catch (_: SerializationException) {
            null
        }

    private companion object {
        const val MAX_CACHED_REPORTS = 20
        const val PREFIX = "performanceHealth.lastRange."

        fun fromKey(accountId: String) = stringPreferencesKey("${PREFIX}from.$accountId")
        fun toKey(accountId: String) = stringPreferencesKey("${PREFIX}to.$accountId")
        fun syntheticKey(accountId: String) = booleanPreferencesKey("${PREFIX}includeSynthetic.$accountId")
    }
}
