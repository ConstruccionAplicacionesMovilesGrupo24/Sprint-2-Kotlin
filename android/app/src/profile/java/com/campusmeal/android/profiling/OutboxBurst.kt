package com.campusmeal.android.profiling

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.campusmeal.android.core.common.traceAsyncSection
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.database.DatabaseMigrations
import com.campusmeal.android.core.outbox.ComparisonLoadOutcome
import com.campusmeal.android.core.outbox.DeviceInfo
import com.campusmeal.android.core.outbox.OutboxAdmission
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.OutboxScheduler
import com.campusmeal.android.core.outbox.PerformanceEvent
import com.campusmeal.android.core.outbox.PerformanceEventType
import com.campusmeal.android.core.outbox.PerformanceEventsApi
import com.campusmeal.android.core.outbox.UsageEventsApi
import com.campusmeal.android.core.outbox.UsageFeature
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventsApi
import com.campusmeal.android.feature.reviews.data.ReviewApi
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope

/**
 * Synthetic event-admission burst for S4-11 (`android/docs/SPRINT4_MICROOPT_DANIEL.md`).
 *
 * Every run builds its own [OutboxRepository] over a disposable on-disk Room database (so the measured
 * admission includes the real SQLite commit), a disposable DataStore and a synthetic account. The
 * scheduler does nothing and every API throws, so nothing is sent: the trace contains event admission
 * only, never backend latency. The files are deleted when the run ends.
 *
 * Event ids and occurrence instants are derived from the event index, so the "before" and the "after"
 * builds store exactly the same rows; the SHA-256 of the stored payloads must therefore be equal.
 * Instants start at 2026-12-31T23:59:50.123Z, so a burst crosses a second, minute, hour, day, month and
 * year boundary and every payload carries non-zero milliseconds that must be truncated.
 */
internal class OutboxBurst(private val context: Context) {

    suspend fun run(mode: String): String = when (mode) {
        REPRESENTATIVE -> measured(mode, REPRESENTATIVE_EVENTS)
        STRESS -> measured(mode, STRESS_EVENTS)
        CHECK -> check()
        else -> "S4-11 burst mode=$mode UNKNOWN (use $REPRESENTATIVE, $STRESS or $CHECK)"
    }

    /** Warmup on its own database, then one traced burst of [events] admissions from a single caller. */
    private suspend fun measured(mode: String, events: Int): String {
        Harness.open(context, "warmup").use { warmup ->
            repeat(WARMUP_EVENTS) { warmup.admit("warmup", it) }
        }
        return Harness.open(context, mode).use { harness ->
            val start = SystemClock.elapsedRealtimeNanos()
            val admitted = traceAsyncSection(BURST_SECTION) {
                (0 until events).count { harness.admit(mode, it) == OutboxAdmission.Admitted }
            }
            val wallMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val stored = harness.storedPayloads()
            "S4-11 burst mode=$mode events=$events admitted=$admitted wallMs=${"%.3f".format(wallMs)} " +
                "earliest=${stored.minOfOrNull(::occurredAt)} latest=${stored.maxOfOrNull(::occurredAt)} " +
                "payloadSha256=${sha256(stored)}"
        }
    }

    /**
     * Correctness only, not timed: the stress burst admitted by one caller and by [CHECK_CALLERS]
     * concurrent callers on `Dispatchers.Default` must store byte-identical payloads.
     */
    private suspend fun check(): String {
        val sequential = Harness.open(context, "check-sequential").use { harness ->
            repeat(STRESS_EVENTS) { harness.admit(STRESS, it) }
            sha256(harness.storedPayloads())
        }
        val concurrent = Harness.open(context, "check-concurrent").use { harness ->
            coroutineScope {
                (0 until CHECK_CALLERS).map { caller ->
                    async(Dispatchers.Default) {
                        for (i in caller until STRESS_EVENTS step CHECK_CALLERS) harness.admit(STRESS, i)
                    }
                }.awaitAll()
            }
            sha256(harness.storedPayloads())
        }
        val verdict = if (sequential == concurrent) "MATCH" else "MISMATCH"
        return "S4-11 burst mode=$CHECK callers=$CHECK_CALLERS events=$STRESS_EVENTS $verdict " +
            "sequential=$sequential concurrent=$concurrent"
    }

    private class Harness private constructor(
        private val context: Context,
        private val name: String,
        private val database: CampusMealDatabase,
        private val dataStoreScope: CoroutineScope,
        private val repository: OutboxRepository,
        private val scope: AccountSessionScope,
    ) : AutoCloseable {

        /** Admits event [index] of [workload]: the same id, kind, payload and instant in every build. */
        suspend fun admit(workload: String, index: Int): OutboxAdmission {
            val id = UUID.nameUUIDFromBytes("s4-11:$workload:$index".toByteArray()).toString()
            val group = UUID.nameUUIDFromBytes("s4-11:$workload:group:${index / KINDS}".toByteArray()).toString()
            val at = FIRST_INSTANT + index * STEP_MS
            return when (index % KINDS) {
                0 -> repository.enqueueUsage(scope, UsageFeature.MEAL_COMPARISON, clientEventId = id, occurredAtEpochMillis = at)
                1 -> repository.enqueueRecommendationEvent(scope, group, selectedAlternative = null, clientEventId = id, occurredAtEpochMillis = at)
                2 -> repository.enqueueRecommendationEvent(scope, group, selectedAlternative = "B", clientEventId = id, occurredAtEpochMillis = at)
                else -> repository.enqueuePerformance(
                    scope,
                    PerformanceEvent(
                        clientEventId = id,
                        type = PerformanceEventType.COMPARISON_LOAD,
                        device = DEVICE,
                        occurredAtEpochMillis = at,
                        sessionId = group,
                        loadId = id,
                        screen = "MEAL_COMPARISON",
                        durationMs = 700L + (index % 9) * 110L,
                        outcome = ComparisonLoadOutcome.FRESH_NETWORK,
                        injected = false,
                    ),
                )
            }
        }

        /** `clientEventId` and payload of every stored row, ordered by id (independent of admission order). */
        fun storedPayloads(): List<Pair<String, String>> =
            database.openHelper.readableDatabase
                .query("SELECT clientEventId, payloadJson FROM outbox_events ORDER BY clientEventId")
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }

        override fun close() {
            database.close()
            context.deleteDatabase(databaseName(name))
            dataStoreScope.cancel()
            context.preferencesDataStoreFile(name).delete()
        }

        companion object {
            private val instances = AtomicInteger()

            suspend fun open(context: Context, label: String): Harness {
                // DataStore allows one instance per file and process: every harness gets a new name.
                val name = "s4-11-burst-$label-${instances.incrementAndGet()}"
                context.deleteDatabase(databaseName(name))
                context.preferencesDataStoreFile(name).delete()
                val database = Room.databaseBuilder(context, CampusMealDatabase::class.java, databaseName(name))
                    .addMigrations(*DatabaseMigrations.ALL)
                    .build()
                // Room creates the file on first use; open it now so creation is never inside the burst.
                database.openHelper.writableDatabase
                val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val preferences = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    context.preferencesDataStoreFile(name)
                }
                val coordinator = AccountSessionCoordinator(preferences, hasCredentials = { false }, listeners = { emptyList() })
                val generation = checkNotNull(coordinator.replaceCredentials(coordinator.currentState().generation) {})
                val scope = checkNotNull(coordinator.publishIdentity(generation, ACCOUNT_ID) {})
                val repository = OutboxRepository(
                    database = database,
                    recommendationApi = noNetwork<RecommendationEventsApi>(),
                    usageApi = noNetwork<UsageEventsApi>(),
                    reviewApi = noNetwork<ReviewApi>(),
                    performanceApi = noNetwork<PerformanceEventsApi>(),
                    authorizationHeaderProvider = NoCredentials,
                    coordinator = coordinator,
                    scheduler = OutboxScheduler { },
                )
                return Harness(context, name, database, dataStoreScope, repository, scope)
            }

            private fun databaseName(name: String) = "$name.db"
        }
    }

    private object NoCredentials : AuthorizationHeaderProvider {
        override suspend fun getAuthorizationHeader(scope: AccountSessionScope): String? = null
        override suspend fun getAuthorizationHeader(generation: Long): String? = null
    }

    companion object {
        const val REPRESENTATIVE = "representative"
        const val STRESS = "stress"
        const val CHECK = "check"

        /** Encloses the measured admissions; only sections inside it are analyzed. */
        const val BURST_SECTION = "cm.s4-11.burst"

        /** About one active session: comparisons with impression, selection, usage and load telemetry. */
        private const val REPRESENTATIVE_EVENTS = 20
        private const val STRESS_EVENTS = 500
        private const val WARMUP_EVENTS = 50
        private const val CHECK_CALLERS = 8
        private const val KINDS = 4

        /** 2026-12-31T23:59:50.123Z. */
        private const val FIRST_INSTANT = 1_798_761_590_123L
        private const val STEP_MS = 1_037L

        private const val ACCOUNT_ID = "s4-11-synthetic-account"
        private val DEVICE = DeviceInfo(deviceModel = "S4-11 synthetic", osVersion = "synthetic", appVersion = "synthetic")

        private val OCCURRED_AT = Regex("\"occurredAt\":\"([^\"]+)\"")

        private fun occurredAt(row: Pair<String, String>): String =
            OCCURRED_AT.find(row.second)?.groupValues?.get(1) ?: "none"

        private fun sha256(rows: List<Pair<String, String>>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            rows.forEach { (id, payload) -> digest.update("$id\u0000$payload\n".toByteArray()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private inline fun <reified T : Any> noNetwork(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                throw UnsupportedOperationException("S4-11 burst must not reach the network (${method.name})")
            } as T
    }
}
