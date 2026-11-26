package com.campusmeal.android.feature.bq10

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.data.remote.requestedNow
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.restaurants.data.MealDto
import com.campusmeal.android.feature.restaurants.data.RestaurantDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query as HttpQuery

internal val mealCategories = listOf("BOWLS", "HOME_STYLE", "SOUPS", "RICE_DISHES", "SUSHI", "SALADS")
internal val supplyZones = listOf("bogota-centro", "bogota-chapinero", "bogota-teusaquillo")

interface Bq10Api {
    @GET("analytics/supply-gaps")
    suspend fun supplyGaps(
        @Header("Authorization") authorization: String,
        @HttpQuery("zone") zone: String,
        @HttpQuery("from") from: String,
        @HttpQuery("to") to: String,
        @HttpQuery("dataset") dataset: String,
    ): SupplyGapResponseDto

    @POST("restaurants/alternatives")
    suspend fun alternatives(
        @Header("Authorization") authorization: String,
        @Body request: RestaurantSearchRequestDto,
    ): AlternativeMealsResponseDto
}

@Serializable
data class SupplyGapResponseDto(
    val contractVersion: Int,
    val policyVersion: Int,
    val generatedAt: String,
    val asOf: String,
    val zone: String,
    val from: String,
    val to: String,
    val synthetic: Boolean,
    val categorizedSearchIntents: Int?,
    val mappedRestaurants: Int,
    val demandSuppressed: Boolean,
    val rows: List<SupplyGapRowDto>,
    val limitations: List<String>,
)

@Serializable
data class SupplyGapRowDto(
    val category: String,
    val searchIntents: Int?,
    val distinctSearchers: Int?,
    val demandShare: Double?,
    val highDemand: Boolean?,
    val availableRestaurants: Int?,
    val lowSupply: Boolean?,
    val suppressed: Boolean,
    val status: String,
)

@Serializable
data class AlternativeMealsResponseDto(
    val contractVersion: Int,
    val generatedAt: String,
    val asOf: String,
    val requestedCategory: String,
    val status: String,
    val routeProviderStatus: String,
    val alternatives: List<AlternativeMealDto>,
)

@Serializable
data class AlternativeMealDto(
    val restaurant: RestaurantDto,
    val meal: MealDto,
    val substituteCategory: String,
    val explanation: String,
    val budgetCompatible: Boolean,
    val dietCompatible: Boolean,
    val timeCompatible: Boolean,
)

@Serializable
data class SupplyGapFilters(val zone: String, val from: String, val to: String, val synthetic: Boolean = false)

@Serializable
private data class AlternativeCacheContext(
    val campusId: String,
    val category: String,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val dietaryPreferences: List<String>,
    val includeDelivery: Boolean,
)

@Entity(tableName = "bq10_snapshots", primaryKeys = ["accountId", "kind", "queryKey", "contractVersion"])
data class Bq10Snapshot(
    val accountId: String,
    val kind: String,
    val queryKey: String,
    val contractVersion: Int,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Dao
interface Bq10SnapshotDao {
    @Query("SELECT * FROM bq10_snapshots WHERE accountId = :accountId AND kind = :kind AND queryKey = :key AND contractVersion = :version LIMIT 1")
    suspend fun find(accountId: String, kind: String, key: String, version: Int): Bq10Snapshot?

    @Upsert
    suspend fun upsert(snapshot: Bq10Snapshot)

    @Query("DELETE FROM bq10_snapshots WHERE accountId = :accountId AND kind = :kind AND rowid NOT IN (SELECT rowid FROM bq10_snapshots WHERE accountId = :accountId AND kind = :kind ORDER BY fetchedAtEpochMillis DESC, rowid DESC LIMIT 20)")
    suspend fun trim(accountId: String, kind: String)

    @Query("DELETE FROM bq10_snapshots WHERE accountId = :accountId AND kind = :kind")
    suspend fun deleteKind(accountId: String, kind: String)

    @Query("DELETE FROM bq10_snapshots WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM bq10_snapshots WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}

data class Bq10Content<T>(val data: T, val fetchedAt: Long, val cached: Boolean, val stale: Boolean)

data class Bq10Result<T>(val content: Bq10Content<T>? = null, val error: String? = null, val accessDenied: Boolean = false)

class Bq10Repository(
    private val api: Bq10Api,
    private val authorization: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val database: CampusMealDatabase,
    private val preferences: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) : AccountScopedStore {
    private val dao = database.bq10SnapshotDao()
    private val json = ApiClientFactory.json

    suspend fun report(scope: AccountSessionScope, filters: SupplyGapFilters, force: Boolean, online: Boolean): Bq10Result<SupplyGapResponseDto> =
        load(scope, REPORT, json.encodeToString(SupplyGapFilters.serializer(), filters), 15 * 60_000L,
            SupplyGapResponseDto.serializer(), force, online,
            { it.contractVersion == VERSION && it.zone == filters.zone && it.from == filters.from && it.to == filters.to && it.synthetic == filters.synthetic }) { token ->
            api.supplyGaps(token, filters.zone, filters.from, filters.to, if (filters.synthetic) "SYNTHETIC" else "REAL")
        }

    suspend fun alternatives(scope: AccountSessionScope, request: RestaurantSearchRequestDto, force: Boolean, online: Boolean): Bq10Result<AlternativeMealsResponseDto> =
        load(scope, ALTERNATIVES, safeContextKey(request), 5 * 60_000L, AlternativeMealsResponseDto.serializer(), force, online,
            { it.contractVersion == VERSION && it.requestedCategory == request.foodCategory }) { token ->
            api.alternatives(token, request.requestedNow())
        }

    // A campus ID may also accompany a device fix. Cache only the canonical campus centre,
    // never the actual device coordinates or a route calculated for a different position.
    private fun safeContextKey(request: RestaurantSearchRequestDto): String? {
        val campus = CampusCatalog.campuses.firstOrNull { it.id == request.campusId } ?: return null
        if (request.location.latitude != campus.latitude || request.location.longitude != campus.longitude) return null
        val category = request.foodCategory ?: return null
        return json.encodeToString(AlternativeCacheContext.serializer(), AlternativeCacheContext(
            campus.id, category, request.availableMinutes, request.maximumBudget,
            request.dietaryPreferences.distinct().sorted(), request.includeDelivery,
        ))
    }

    private suspend fun <T> load(
        scope: AccountSessionScope, kind: String, key: String?, ttl: Long, serializer: KSerializer<T>,
        force: Boolean, online: Boolean, valid: (T) -> Boolean, call: suspend (String) -> T,
    ): Bq10Result<T> {
        if (!coordinator.isCurrent(scope)) return Bq10Result(accessDenied = true)
        val snapshot = key?.let { dao.find(scope.accountId, kind, it, VERSION) }
        val cached = snapshot?.let { row ->
            try {
                val data = json.decodeFromString(serializer, row.responseJson)
                if (!valid(data)) null else Bq10Content(data, row.fetchedAtEpochMillis, true,
                    now() - row.fetchedAtEpochMillis !in 0L until ttl)
            } catch (_: SerializationException) { null }
        }
        fun fallback(message: String) = Bq10Result(cached?.copy(stale = true), message)
        if (!coordinator.isCurrent(scope)) return Bq10Result(accessDenied = true)
        if (!online) return fallback(if (cached == null) "Offline. No matching saved result." else "Offline. Showing historical data.")
        if (!force && cached != null && !cached.stale) return Bq10Result(cached)
        val token = authorization.getAuthorizationHeader(scope) ?: return Bq10Result(error = "Sign in again.", accessDenied = true)
        val result = apiCall { call(token) }
        if (!coordinator.isCurrent(scope)) return Bq10Result(accessDenied = true)
        return when (result) {
            is ApiResult.Success -> {
                if (!valid(result.data)) return fallback("Unexpected API contract or query response.")
                val fetchedAt = now()
                if (key != null) coordinator.runIfCurrent(scope) {
                    database.withTransaction {
                        dao.upsert(Bq10Snapshot(scope.accountId, kind, key, VERSION, json.encodeToString(serializer, result.data), fetchedAt))
                        dao.trim(scope.accountId, kind)
                    }
                }
                if (coordinator.isCurrent(scope)) Bq10Result(Bq10Content(result.data, fetchedAt, false, false)) else Bq10Result(accessDenied = true)
            }
            ApiResult.Unauthorized -> Bq10Result(error = "Sign in again.", accessDenied = true)
            is ApiResult.HttpError -> if (result.code == 403) {
                coordinator.runIfCurrent(scope) { dao.deleteKind(scope.accountId, kind) }
                Bq10Result(error = "Access denied (403).", accessDenied = true)
            } else fallback("Request failed (HTTP ${result.code}).")
            is ApiResult.NetworkUnavailable -> fallback("Cannot reach the server. Try again when connected.")
            is ApiResult.InvalidResponse -> fallback("Server response could not be read.")
        }
    }

    suspend fun savedReport(scope: AccountSessionScope): SupplyGapFilters? {
        if (!coordinator.isCurrent(scope)) return null
        val value = preferences.data.first()[filterKey(REPORT, scope.accountId)] ?: return null
        return try { json.decodeFromString(SupplyGapFilters.serializer(), value) } catch (_: SerializationException) { null }
    }

    suspend fun saveReport(scope: AccountSessionScope, filters: SupplyGapFilters) = save(scope, REPORT, json.encodeToString(SupplyGapFilters.serializer(), filters))
    suspend fun savedCategory(scope: AccountSessionScope): String? = if (coordinator.isCurrent(scope)) preferences.data.first()[filterKey(ALTERNATIVES, scope.accountId)] else null
    suspend fun saveAlternative(scope: AccountSessionScope, request: RestaurantSearchRequestDto) {
        val category = request.foodCategory ?: return
        // Persist only category and non-precise filter values. The active context remains authoritative.
        coordinator.runIfCurrent(scope) {
            preferences.edit { prefs ->
                prefs[filterKey(ALTERNATIVES, scope.accountId)] = category
                prefs[filterKey("alternativeContext", scope.accountId)] = json.encodeToString(
                    AlternativeCacheContext.serializer(), AlternativeCacheContext(
                        request.campusId.orEmpty(), category, request.availableMinutes, request.maximumBudget,
                        request.dietaryPreferences.distinct().sorted(), request.includeDelivery,
                    ),
                )
            }
        }
    }
    private suspend fun save(scope: AccountSessionScope, kind: String, value: String) {
        coordinator.runIfCurrent(scope) { preferences.edit { it[filterKey(kind, scope.accountId)] = value } }
    }
    override suspend fun deleteAccount(accountId: String) {
        dao.delete(accountId)
        preferences.edit { prefs -> prefs.asMap().keys.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".$accountId") }.forEach { prefs.remove(it) } }
    }
    override suspend fun deleteAllExcept(accountId: String) {
        dao.deleteAllExcept(accountId)
        preferences.edit { prefs -> prefs.asMap().keys.filter { it.name.startsWith(PREFIX) && !it.name.endsWith(".$accountId") }.forEach { prefs.remove(it) } }
    }
    private companion object {
        const val VERSION = 1
        const val REPORT = "report"
        const val ALTERNATIVES = "alternatives"
        const val PREFIX = "bq10.filters."
        fun filterKey(kind: String, account: String) = stringPreferencesKey("$PREFIX$kind.$account")
    }
}
