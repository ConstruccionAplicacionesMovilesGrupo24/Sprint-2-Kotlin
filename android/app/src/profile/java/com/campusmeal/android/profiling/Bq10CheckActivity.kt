package com.campusmeal.android.profiling

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.database.DatabaseMigrations
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.bq10.*
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchLocationDto
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.CampusCatalog
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Response

/** Profile only: synthetic fixtures, isolated databases, no real HTTP. */
class Bq10CheckActivity : ComponentActivity() {
    private var passed = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "S4-07 checks running"; setPadding(32,80,32,32) }
        setContentView(status)
        lifecycleScope.launch {
            val line = try {
                withContext(Dispatchers.Default) { runChecks(); migrationCheck() }
                "PASS assertions=$passed"
            } catch (e: Exception) {
                Log.e(TAG, "FAILED", e)
                "FAILED assertions=$passed ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "DONE $line")
            status.text = line
        }
    }
    private fun expect(label: String, ok: Boolean) {
        check(ok) { label }; passed++; Log.i(TAG, "PASS $label")
    }
    private suspend fun runChecks() = coroutineScope {
        val name = "s4-07-check-${UUID.randomUUID()}"
        val dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = preferencesDataStoreFile(name)
        val db = Room.databaseBuilder(applicationContext, CampusMealDatabase::class.java, "$name.db").build()
        try {
            db.openHelper.writableDatabase
            val prefs = PreferenceDataStoreFactory.create(scope = dsScope) { file }
            val coordinator = AccountSessionCoordinator(prefs, { true }, { emptyList() })
            suspend fun account(id: String): AccountSessionScope {
                val g = checkNotNull(coordinator.replaceCredentials(coordinator.currentState().generation) {})
                return checkNotNull(coordinator.publishIdentity(g, id) {})
            }
            val a = account("synthetic-A")
            val auth = object : AuthorizationHeaderProvider {
                override suspend fun getAuthorizationHeader(scope: AccountSessionScope) = if (coordinator.isCurrent(scope)) "Bearer synthetic" else null
                override suspend fun getAuthorizationHeader(generation: Long) = if (coordinator.currentScopeOrNull()?.generation == generation) "Bearer synthetic" else null
            }
            var now = 1_790_000_000_000L
            val api = FakeApi()
            val repo = Bq10Repository(api, auth, coordinator, db, prefs) { now }
            val campus = CampusCatalog.campuses.first()
            val req = RestaurantSearchRequestDto(RestaurantSearchLocationDto(campus.latitude, campus.longitude), campus.id, 90, 40000L, emptyList(), false, AT, "SUSHI")
            val filters = SupplyGapFilters("bogota-centro", "2026-11-17", "2026-11-17", true)
            suspend fun alt(r: RestaurantSearchRequestDto = req, force: Boolean = false, online: Boolean = true, s: AccountSessionScope = a) = repo.alternatives(s,r,force,online)
            suspend fun report(f: SupplyGapFilters = filters, force: Boolean = false, online: Boolean = true, s: AccountSessionScope = a) = repo.report(s,f,force,online)
            fun count(id: String, kind: String): Int = db.openHelper.readableDatabase.query("SELECT count(*) FROM bq10_snapshots WHERE accountId=? AND kind=?", arrayOf(id,kind)).use { it.moveToFirst(); it.getInt(0) }
            val start = now
            expect("alternative initial network result", alt().content?.cached == false)
            expect("report initial network result", report().content?.cached == false)
            val calls = api.calls
            now = start + 299999
            expect("alternative TTL 299999ms fresh", alt().content?.let { it.cached && !it.stale && it.fetchedAt == start } == true && api.calls == calls)
            now++
            api.failure = 503
            expect("alternative TTL 300000ms expired and 503 historical fallback", alt().content?.let { it.cached && it.stale && it.fetchedAt == start } == true && api.calls == calls + 1)
            api.failure = 0
            expect("alternative reconnect refresh", alt().content?.let { !it.cached && !it.stale && it.fetchedAt == now } == true)
            now = start + 899999
            val beforeReport = api.calls
            expect("report TTL 899999ms fresh", report().content?.let { it.cached && !it.stale && it.fetchedAt == start } == true && api.calls == beforeReport)
            now++
            api.failure = 503
            expect("report TTL 900000ms expired and 503 historical fallback", report().content?.let { it.cached && it.stale && it.fetchedAt == start } == true && api.calls == beforeReport + 1)
            api.failure = 0
            expect("report reconnect refresh", report().content?.let { !it.cached && it.fetchedAt == now } == true)
            expect("budget change offline miss", alt(req.copy(maximumBudget=23000L),online=false).content == null)
            expect("time change offline miss", alt(req.copy(availableMinutes=45),online=false).content == null)
            expect("dataset change offline miss", report(filters.copy(synthetic=false),online=false).content == null)
            expect("date change offline miss", report(filters.copy(to="2026-11-18"),online=false).content == null)
            val precise = req.copy(location=RestaurantSearchLocationDto(campus.latitude+0.000123,campus.longitude+0.000456))
            val altCount = count(a.accountId,"alternatives")
            expect("noncanonical coordinates cannot reuse campus snapshot", alt(precise,online=false).content == null)
            expect("noncanonical request online works without storing snapshot", alt(precise).content?.cached == false && count(a.accountId,"alternatives") == altCount)
            expect("noncanonical request still offline miss", alt(precise,online=false).content == null)
            expect("no campus offline miss", alt(req.copy(campusId=null),online=false).content == null)
            repo.saveAlternative(a,precise)
            expect("nonprecise category filter saved", repo.savedCategory(a) == "SUSHI")
            val stored = prefs.data.first().asMap()
            expect("saved BQ10 filter values contain no device coordinates", stored.values.none { it.toString().contains(precise.location.latitude.toString()) || it.toString().contains(precise.location.longitude.toString()) })
            repo.saveReport(a,filters)
            expect("report filters persisted", repo.savedReport(a) == filters)
            for (i in 0..20) {
                now++
                check(alt(req.copy(maximumBudget=50000L+i),force=true).content != null)
                val day = (i+1).toString().padStart(2,'0')
                check(report(filters.copy(from="2026-11-$day",to="2026-11-$day"),force=true).content != null)
            }
            expect("alternative retention exactly 20", count(a.accountId,"alternatives") == 20)
            expect("report retention exactly 20", count(a.accountId,"report") == 20)
            expect("oldest alternative evicted", alt(req.copy(maximumBudget=50000L),online=false).content == null)
            expect("newest alternative retained", alt(req.copy(maximumBudget=50020L),online=false).content?.cached == true)
            expect("oldest report evicted", report(filters.copy(from="2026-11-01",to="2026-11-01"),online=false).content == null)
            expect("newest report retained", report(filters.copy(from="2026-11-21",to="2026-11-21"),online=false).content?.cached == true)
            check(report(force=true).content != null)
            api.failure = 403
            val denied = report(force=true)
            expect("403 denies report and clears all account report rows", denied.accessDenied && denied.content == null && count(a.accountId,"report") == 0)
            expect("403 does not clear alternative kind", count(a.accountId,"alternatives") == 20)
            expect("offline after 403 cannot recover deleted report", report(online=false).content == null)
            api.failure = 0
            check(report(force=true).content != null)
            api.failure = -1
            expect("IOException on forced refresh keeps historical report", report(force=true).content?.stale == true)
            api.failure = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            api.gate = entered to release
            val pending = async { report(filters.copy(zone="bogota-chapinero"),force=true) }
            withTimeout(5000) { entered.await() }
            val b = account("synthetic-B")
            release.complete(Unit)
            expect("in-flight response from previous session denied", withTimeout(5000) { pending.await() }.let { it.accessDenied && it.content == null })
            api.gate = null
            expect("previous session cannot fetch", report().accessDenied)
            expect("new account cannot reuse old report", report(online=false,s=b).content == null)
            expect("new account cannot reuse old alternative", alt(req.copy(maximumBudget=50020L),online=false,s=b).content == null)
            expect("new account does not inherit report filters", repo.savedReport(b) == null)
            check(report(s=b,force=true).content != null)
            repo.deleteAccount(a.accountId)
            expect("account cleanup removes A but retains B", count(a.accountId,"alternatives") == 0 && count(a.accountId,"report") == 0 && count(b.accountId,"report") == 1)
            // Deterministic cancellation of a request blocked inside the fake API; no ViewModel/UI claim.
            val entered2 = CompletableDeferred<Unit>()
            val release2 = CompletableDeferred<Unit>()
            api.gate = entered2 to release2
            val canceled = async { report(filters.copy(zone="bogota-teusaquillo"),force=true,s=b) }
            withTimeout(5000) { entered2.await() }
            canceled.cancelAndJoin()
            api.gate = null
            expect("cancelled in-flight request does not persist result", report(filters.copy(zone="bogota-teusaquillo"),online=false,s=b).content == null)
        } finally {
            db.close(); deleteDatabase("$name.db"); dsScope.cancel(); file.delete()
        }
    }
    private suspend fun migrationCheck() {
        val name = "s4-07-migration-${UUID.randomUUID()}.db"
        val path = getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            val schema = JSONObject(assets.open("s4-07-room-v11.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            check(schema.getInt("version") == 11)
            SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i)
                    val table = e.getString("tableName")
                    old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices = e.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO inventory_items VALUES ('synthetic-A','item-1','Rice',2,'kg','2026-12-01',5,0)")
                old.execSQL("INSERT INTO restaurant_search_cache VALUES ('synthetic-A','key-1','{}',12345)")
                old.execSQL("INSERT INTO review_drafts (accountId,restaurantId,restaurantName,waitMinutesText,updatedAtEpochMillis) VALUES ('synthetic-A','r1','Synthetic','5',12345)")
                old.execSQL("INSERT INTO outbox_events (clientEventId,accountId,kind,payloadJson,occurredAtEpochMillis,createdAtEpochMillis,status,attempts) VALUES ('event-1','synthetic-A','USAGE','{}',12345,12345,'PENDING',0)")
                old.version = 11
            }
            val db = Room.databaseBuilder(applicationContext,CampusMealDatabase::class.java,name).addMigrations(*DatabaseMigrations.ALL).build()
            try {
                val sql = db.openHelper.writableDatabase
                expect("exported v11 schema migrates and Room validates v12", sql.version == 12)
                fun value(q: String): String = sql.query(q).use { check(it.moveToFirst()); it.getString(0) }
                expect("migration preserves inventory row", value("SELECT name FROM inventory_items WHERE itemId='item-1'") == "Rice")
                expect("migration preserves restaurant cache timestamp", value("SELECT fetchedAtEpochMillis FROM restaurant_search_cache WHERE contextKey='key-1'") == "12345")
                expect("migration preserves review draft", value("SELECT waitMinutesText FROM review_drafts WHERE restaurantId='r1'") == "5")
                expect("migration preserves pending outbox", value("SELECT status FROM outbox_events WHERE clientEventId='event-1'") == "PENDING")
                db.bq10SnapshotDao().upsert(Bq10Snapshot("synthetic-A","report","key",1,"{}",12345))
                expect("new BQ10 table writable", db.bq10SnapshotDao().find("synthetic-A","report","key",1)?.fetchedAtEpochMillis == 12345L)
            } finally { db.close() }
        } finally { deleteDatabase(name) }
    }
    private class FakeApi : Bq10Api {
        var failure = 0
        var calls = 0
        var gate: Pair<CompletableDeferred<Unit>,CompletableDeferred<Unit>>? = null
        private suspend fun before() {
            calls++
            gate?.let { (entered,release) -> entered.complete(Unit); release.await() }
            if (failure == -1) throw IOException("synthetic disconnect")
            if (failure != 0) throw HttpException(Response.error<Any>(failure,"synthetic error".toResponseBody()))
        }
        override suspend fun supplyGaps(authorization:String,zone:String,from:String,to:String,dataset:String): SupplyGapResponseDto {
            before(); return SupplyGapResponseDto(1,1,AT,AT,zone,from,to,dataset=="SYNTHETIC",0,0,false,emptyList(),emptyList())
        }
        override suspend fun alternatives(authorization:String,request:RestaurantSearchRequestDto): AlternativeMealsResponseDto {
            before(); return AlternativeMealsResponseDto(1,AT,AT,checkNotNull(request.foodCategory),"NO_ALTERNATIVES","AVAILABLE",emptyList())
        }
    }
    companion object { private const val TAG = "CampusMealBq10Check"; private const val AT = "2026-11-17T12:00:00Z" }
}
