package com.campusmeal.android.profiling

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.traceAsyncSection
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchLocationDto
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.restaurants.data.*
import com.campusmeal.android.feature.restaurants.domain.*
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Profile-only: disposable Room and DataStore, synthetic credentials, no real HTTP. */
class RestaurantCacheCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "S4-08 running"; setPadding(32, 80, 32, 32) }
        setContentView(status)
        val mode = intent.getStringExtra("cm.cache.mode") ?: "check"
        lifecycleScope.launch {
            val line = try {
                withContext(Dispatchers.Default) { runHarness(mode) }
            } catch (e: Exception) {
                Log.e(TAG, "S4-08 FAILED", e)
                "FAILED ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "DONE $line")
            status.text = line
        }
    }

    private suspend fun runHarness(mode: String): String {
        val name = "s4-08-${UUID.randomUUID()}"
        val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefsFile = preferencesDataStoreFile(name)
        val db = Room.databaseBuilder(applicationContext, CampusMealDatabase::class.java, "$name.db").build()
        try {
            db.openHelper.writableDatabase
            val prefs = PreferenceDataStoreFactory.create(scope = dsScope) { prefsFile }
            val coordinator = AccountSessionCoordinator(prefs, { true }, { emptyList() })
            val gen = checkNotNull(coordinator.replaceCredentials(coordinator.currentState().generation) {})
            val scope = checkNotNull(coordinator.publishIdentity(gen, "synthetic-A") {})
            val auth = object : AuthorizationHeaderProvider {
                override suspend fun getAuthorizationHeader(scope: AccountSessionScope) = if (coordinator.isCurrent(scope)) "Bearer synthetic" else null
                override suspend fun getAuthorizationHeader(generation: Long) = if (coordinator.currentScopeOrNull()?.generation == generation) "Bearer synthetic" else null
            }
            var now = 1_790_000_000_000L
            val api = FakeApi()
            val repo = NetworkRestaurantRepository(api, auth, prefs, coordinator, { now }, db, CachePolicy())
            val dao = db.restaurantCacheDao()
            val req = RestaurantSearchRequestDto(RestaurantSearchLocationDto(0.0, 0.0), "synthetic-campus", 90, 40000L, emptyList(), false, "2026-09-21T00:00:00Z")
            val key = "campus=synthetic-campus|minutes=90|budget=40000|diet=|delivery=false|category=-"
            val json = ApiClientFactory.json
            val fetched = now
            suspend fun seed(count: Int) {
                api.searchDto = RestaurantSearchResponseDto(List(count) { restaurant(it) })
                api.detailDto = RestaurantDetailResponseDto(restaurant(0), "Synthetic address", List(count) { MealDto("m$it", "Synthetic meal $it", 15000L) })
                dao.upsertSearch(RestaurantSearchCacheEntity(scope.accountId, key, json.encodeToString(RestaurantSearchResponseDto.serializer(), api.searchDto), fetched))
                dao.upsertDetail(RestaurantDetailCacheEntity(scope.accountId, "r0", json.encodeToString(RestaurantDetailResponseDto.serializer(), api.detailDto), fetched))
            }
            suspend fun search(s: AccountSessionScope = scope, force: Boolean = false) = repo.search(s, req, force)
            suspend fun detail(force: Boolean = false) = repo.getRestaurant(scope, "r0", force)
            fun expect(test: String, ok: Boolean) { check(ok) { test }; Log.i(TAG, "PASS $test") }
            fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            if (mode == "check") {
                seed(5)
                api.offline = true
                now = fetched + 300000L
                val hits = api.calls
                val s1 = search() as? RestaurantSearchResult.Success
                expect("search TTL exactly 5min fresh", s1?.results?.source == ResultSource.FRESH_CACHE && api.calls == hits && s1.results.lastUpdatedAtEpochMillis == fetched)
                now++
                val s2 = search() as? RestaurantSearchResult.Success
                expect("search TTL +1ms stale offline", s2?.results?.source == ResultSource.CACHE && api.calls == hits + 1 && s2.results.lastUpdatedAtEpochMillis == fetched)
                now = fetched + 1800000L
                val dHits = api.calls
                val d1 = detail() as? RestaurantDetailResult.Success
                expect("detail TTL exactly 30min fresh", d1?.detail?.lastUpdatedAtEpochMillis == fetched && api.calls == dHits)
                now++
                val d2 = detail() as? RestaurantDetailResult.Success
                expect("detail TTL +1ms stale offline", d2?.detail?.lastUpdatedAtEpochMillis == fetched && api.calls == dHits + 1)
                now = fetched
                seed(0)
                val empty = search() as? RestaurantSearchResult.Success
                expect("empty search distinct from missing", empty?.results?.restaurants?.isEmpty() == true && empty.results.source == ResultSource.FRESH_CACHE)
                expect("missing context offline", repo.search(scope, req.copy(maximumBudget = 40001L)) is RestaurantSearchResult.Failure)
                dao.upsertSearch(RestaurantSearchCacheEntity(scope.accountId, key, "{broken", fetched))
                dao.upsertDetail(RestaurantDetailCacheEntity(scope.accountId, "r0", "{broken", fetched))
                expect("corrupt search offline handled", search() is RestaurantSearchResult.Failure)
                expect("corrupt detail offline handled", detail() is RestaurantDetailResult.Failure)
                api.offline = false
                val recovered = search() as? RestaurantSearchResult.Success
                expect("corrupt search online recovery", recovered?.results?.source == ResultSource.NETWORK)
                expect("corrupt detail online recovery", detail() is RestaurantDetailResult.Success)
                seed(5)
                now = fetched + 1000L
                val fHits = api.calls
                val refreshed = search(force = true) as? RestaurantSearchResult.Success
                expect("explicit search force refresh", refreshed?.results?.source == ResultSource.NETWORK && api.calls == fHits + 1 && refreshed.results.lastUpdatedAtEpochMillis == now)
                val refreshedDetail = detail(force = true) as? RestaurantDetailResult.Success
                expect("explicit detail force refresh", refreshedDetail?.detail?.lastUpdatedAtEpochMillis == now && api.calls == fHits + 2)
                val g2 = checkNotNull(coordinator.replaceCredentials(coordinator.currentState().generation) {})
                val b = checkNotNull(coordinator.publishIdentity(g2, "synthetic-B") {})
                api.offline = true
                expect("account B cannot read account A search", search(b) is RestaurantSearchResult.Failure)
                expect("account B cannot read account A detail", repo.getRestaurant(b, "r0") is RestaurantDetailResult.Failure)
                expect("old session cannot read search", search(scope) == RestaurantSearchResult.Unauthorized)
                expect("old session cannot read detail", detail() == RestaurantDetailResult.Unauthorized)
                return "mode=check PASS"
            }
            require(mode in listOf("small", "large"))
            val count = if (mode == "small") 5 else 200
            seed(count)
            api.offline = true
            val searchPayload = checkNotNull(dao.getSearch(scope.accountId, key)).responseJson
            val detailPayload = checkNotNull(dao.getDetail(scope.accountId, "r0")).responseJson
            val expectedS = checkNotNull(api.searchDto.toResultsOrNull(fetched, ResultSource.FRESH_CACHE))
            val expectedD = checkNotNull(api.detailDto.toDetailOrNull(fetched))
            val expectedSearch = RestaurantSearchResult.Success(expectedS)
            val expectedDetail = RestaurantDetailResult.Success(expectedD)
            repeat(15) { check(search() == expectedSearch); check(detail() == expectedDetail) }
            val n = 100
            var lastS: RestaurantSearchResult? = null
            var lastD: RestaurantDetailResult? = null
            val calls = api.calls
            var start = SystemClock.elapsedRealtimeNanos()
            traceAsyncSection("cm.s4-08.search.$mode") { repeat(n) { lastS = search() } }
            val searchMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            start = SystemClock.elapsedRealtimeNanos()
            traceAsyncSection("cm.s4-08.detail.$mode") { repeat(n) { lastD = detail() } }
            val detailMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            check(lastS == expectedSearch && lastD == expectedDetail && calls == 0 && api.calls == 0)
            return "mode=$mode count=$count n=$n searchBytes=${searchPayload.toByteArray().size} detailBytes=${detailPayload.toByteArray().size} searchSha=${sha(searchPayload)} detailSha=${sha(detailPayload)} searchMs=$searchMs detailMs=$detailMs apiCalls=${api.calls} PASS"
        } finally {
            db.close()
            deleteDatabase("$name.db")
            dsScope.cancel()
            prefsFile.delete()
        }
    }

    private class FakeApi : RestaurantApi {
        var offline = false
        var calls = 0
        var searchDto = RestaurantSearchResponseDto(emptyList())
        var detailDto = RestaurantDetailResponseDto(restaurant(0))
        override suspend fun search(authorization: String, request: RestaurantSearchRequestDto): RestaurantSearchResponseDto {
            calls++
            if (offline) throw IOException("synthetic offline")
            return searchDto
        }
        override suspend fun getRestaurant(authorization: String, restaurantId: String): RestaurantDetailResponseDto {
            calls++
            if (offline) throw IOException("synthetic offline")
            return detailDto
        }
    }
    companion object {
        private const val TAG = "CampusMealCacheCheck"
        private fun restaurant(i: Int) = RestaurantDto(id = "r$i", name = "Synthetic restaurant $i", category = "BOWLS", openingStatus = "OPEN", walkingMinutes = 5, estimatedTotalMinutes = 25, minimumMealPrice = 15000L)
    }
}
