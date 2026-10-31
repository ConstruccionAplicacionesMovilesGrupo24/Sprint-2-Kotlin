package com.campusmeal.android.feature.analytics.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

data class ZoneDemandFilters(
    val zone: String,
    val from: String,
    val to: String,
)

sealed interface ZoneDemandResult {

    data class Content(
        val report: ZoneDemandResponseDto,
        val selectedZone: String,
        val source: ReportSource,
        val freshness: Freshness.Synced,
    ) : ZoneDemandResult

    data object Forbidden : ZoneDemandResult

    data object Unauthorized : ZoneDemandResult

    data class Unavailable(
        val httpCode: Int?,
    ) : ZoneDemandResult
}

class ZoneDemandRepository(
    private val api: ZoneDemandApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val database: CampusMealDatabase,
    private val preferences: DataStore<Preferences>,
    private val cachePolicy: CachePolicy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AccountScopedStore {
    private val dao = database.zoneDemandReportDao()
    private val json = ApiClientFactory.json
    suspend fun report(
        scope: AccountSessionScope,
        zone: String,
        from: String,
        to: String,
        forceRefresh: Boolean = false,
            ): ZoneDemandResult {
            val cached =
                if (coordinator.isCurrent(scope)) {
                    dao.getLatest(
                        accountId = scope.accountId,
                        zone = zone,
                        fromDate = from,
                        toDate = to,
                    )?.toContent()
                } else {
                    null
                }

    if (!forceRefresh && cached?.freshness is Freshness.Fresh) {
        return cached
    }

    val authorization =
        authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return ZoneDemandResult.Unauthorized

    return when (
        val result = apiCall {
            api.report(
                authorization = authorization,
                from = from,
                to = to,
            )
        }
    ) {
        is ApiResult.Success -> {
            val now = currentTimeMillis()

            val entity =
                ZoneDemandReportEntity(
                    accountId = scope.accountId,
                    zone = zone,
                    fromDate = from,
                    toDate = to,
                    contractVersion = result.data.contractVersion,
                    responseJson =
                        json.encodeToString(
                            ZoneDemandResponseDto.serializer(),
                            result.data,
                        ),
                    fetchedAtEpochMillis = now,
                )

            coordinator.runIfCurrent(scope) {
                dao.upsert(entity)
            }

            ZoneDemandResult.Content(
                report = result.data,
                selectedZone = zone,
                source = ReportSource.NETWORK,
                freshness = Freshness.Fresh(now),
            )
        }

        ApiResult.Unauthorized ->
            ZoneDemandResult.Unauthorized

        is ApiResult.HttpError -> {
            if (result.code == 403) {
                coordinator.runIfCurrent(scope) {
                    dao.delete(scope.accountId)
                }

                ZoneDemandResult.Forbidden
            } else {
                cached ?: ZoneDemandResult.Unavailable(result.code)
            }
        }

        is ApiResult.NetworkUnavailable,
        is ApiResult.InvalidResponse ->
            cached ?: ZoneDemandResult.Unavailable(null)
    }
}


private fun ZoneDemandReportEntity.toContent(): ZoneDemandResult.Content? =
    try {
        ZoneDemandResult.Content(
            report =
                json.decodeFromString(
                    ZoneDemandResponseDto.serializer(),
                    responseJson,
                ),
            selectedZone = zone,
            source = ReportSource.CACHE,
            freshness =
                Freshness.synced(
                    fetchedAtEpochMillis,
                    cachePolicy.analyticsReport,
                    currentTimeMillis(),
                ),
        )
    } catch (_: SerializationException) {
        null
    }
    /**
     * Retrieves the saved filters for the given account.
     *
     * @param scope The account session scope.
     * @return The saved filters, or null if not found.
     */
    suspend fun savedFilters(
            scope: AccountSessionScope,
        ): ZoneDemandFilters? {
            if (!coordinator.isCurrent(scope)) return null

            val prefs = preferences.data.first()

            val zone =
                prefs[zoneKey(scope.accountId)]
                    ?: return null

            val from =
                prefs[fromKey(scope.accountId)]
                    ?: return null

            val to =
                prefs[toKey(scope.accountId)]
                    ?: return null

            return ZoneDemandFilters(
                zone = zone,
                from = from,
                to = to,
            )
            }
    suspend fun saveFilters(
            scope: AccountSessionScope,
            filters: ZoneDemandFilters,
        ) {
            coordinator.runIfCurrent(scope) {
                preferences.edit { prefs ->
                    prefs[zoneKey(scope.accountId)] = filters.zone
                    prefs[fromKey(scope.accountId)] = filters.from
                    prefs[toKey(scope.accountId)] = filters.to
                }
            }
        }
    override suspend fun deleteAccount(accountId: String) {
    dao.delete(accountId)

    preferences.edit {
        it.remove(zoneKey(accountId))
        it.remove(fromKey(accountId))
        it.remove(toKey(accountId))
    }
}

override suspend fun deleteAllExcept(accountId: String) {
    dao.deleteAllExcept(accountId)

    preferences.edit { prefs ->
        prefs.asMap().keys
            .filter {
                it.name.startsWith(FILTER_PREFIX) &&
                    !it.name.endsWith(".$accountId")
            }
            .forEach { prefs.remove(it) }
    }
}
private companion object {
    const val FILTER_PREFIX = "analytics.zoneDemand."

    fun zoneKey(accountId: String) =
        stringPreferencesKey("${FILTER_PREFIX}zone.$accountId")

    fun fromKey(accountId: String) =
        stringPreferencesKey("${FILTER_PREFIX}from.$accountId")

    fun toKey(accountId: String) =
        stringPreferencesKey("${FILTER_PREFIX}to.$accountId")
    }
}


