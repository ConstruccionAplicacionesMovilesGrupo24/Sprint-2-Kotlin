package com.campusmeal.android.core.outbox

import android.util.Log
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountLifecycleListener
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.core.session.DeactivationReason
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventDto
import com.campusmeal.android.feature.decision.data.remote.RecommendationEventsApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import retrofit2.Response

enum class DrainResult { DONE, RETRY }

/** Whether the outbox durably stored an event. */
sealed interface OutboxAdmission {
    data object Admitted : OutboxAdmission

    /** Nothing was stored: the session changed, or the account already has too many pending events. */
    data class Rejected(val reason: String) : OutboxAdmission
}

/**
 * Durable, at-least-once delivery of analytics events (S3-09): BQ8 recommendation events and BQ6 usage
 * events.
 *
 * An event is written to Room first, inside the [AccountSessionCoordinator] lock and only while its
 * scope is still active. A unique WorkManager chain per account then sends it with its stable
 * `clientEventId`; the backend deduplicates, so a replay after a lost acknowledgement is safe.
 * Rows belong to the account that produced them and are only sent with that account's credentials
 * ([AuthorizationHeaderProvider] returns null once the scope ends). Logout cancels the work but keeps
 * the rows, which are sent the next time the same account is activated.
 */
class OutboxRepository(
    database: CampusMealDatabase,
    private val recommendationApi: RecommendationEventsApi,
    private val usageApi: UsageEventsApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountLifecycleListener {

    private val dao = database.outboxDao()
    private val json = ApiClientFactory.json

    /** Account that already recorded SESSION_STARTED in this process. Only touched inside the coordinator lock. */
    @Volatile
    private var sessionStartedFor: String? = null

    /**
     * Stores a BQ8 event (an impression when [selectedAlternative] is null). Storing the same
     * [clientEventId] again is a no-op, except that a definitively failed event is queued again.
     */
    suspend fun enqueueRecommendationEvent(
        scope: AccountSessionScope,
        recommendationId: String,
        selectedAlternative: String?,
        clientEventId: String,
        occurredAtEpochMillis: Long,
    ): OutboxAdmission {
        val dto = RecommendationEventDto(
            clientEventId = clientEventId,
            recommendationId = recommendationId,
            eventType = if (selectedAlternative == null) IMPRESSION else SELECTED,
            selectedAlternative = selectedAlternative,
            platform = PLATFORM,
            occurredAt = isoUtc(occurredAtEpochMillis),
        )
        return admit(scope, OutboxKind.RECOMMENDATION, recommendationId, clientEventId, occurredAtEpochMillis, json.encodeToString(dto))
    }

    /**
     * Records one explicit use of [feature] (BQ6). Call once per user interaction, never on recomposition.
     * [clientEventId] lets a caller reuse its own visit id, so a repeated admission stores one event.
     */
    suspend fun enqueueUsage(
        scope: AccountSessionScope,
        feature: UsageFeature,
        clientEventId: String = UUID.randomUUID().toString(),
        occurredAtEpochMillis: Long = clock(),
    ): OutboxAdmission =
        admit(scope, OutboxKind.USAGE, null, clientEventId, occurredAtEpochMillis, usagePayload(clientEventId, FEATURE_USED, feature.name, occurredAtEpochMillis))

    /** `PENDING`, `SENDING`, `SENT`, `FAILED`, or null when the event does not exist. */
    fun observeStatus(clientEventId: String): Flow<String?> = dao.observeStatus(clientEventId)

    /**
     * Runs inside the coordinator lock (local work only): records one SESSION_STARTED per account per
     * process and schedules delivery of whatever that account left pending (for example after the
     * process was killed).
     */
    override suspend fun onAccountActivated(scope: AccountSessionScope) {
        if (sessionStartedFor != scope.accountId) {
            sessionStartedFor = scope.accountId
            val id = UUID.randomUUID().toString()
            val now = clock()
            dao.insert(row(scope.accountId, OutboxKind.USAGE, null, id, now, usagePayload(id, SESSION_STARTED, null, now)))
        }
        if (dao.pendingCount(scope.accountId) > 0) scheduler.schedule(scope.accountId)
    }

    override suspend fun onAccountDeactivated(accountId: String, reason: DeactivationReason) {
        // Rows stay with their account; AccountWorkCanceller already cancels this account's work.
        if (sessionStartedFor == accountId) sessionStartedFor = null
    }

    /**
     * Sends [accountId]'s pending events, oldest first, while that account is the active session.
     * Stops at the first transient failure ([DrainResult.RETRY]); definitive rejections are marked
     * FAILED and skipped.
     */
    suspend fun drain(accountId: String): DrainResult {
        // A worker may start in a fresh process: currentState() restores the session first.
        val scope = coordinator.currentState().scope?.takeIf { it.accountId == accountId } ?: return DrainResult.DONE
        val now = clock()
        dao.purgeOlderThan(now - TimeUnit.DAYS.toMillis(RETENTION_DAYS))
        dao.purgeSentBefore(now - TimeUnit.DAYS.toMillis(1))
        dao.resetSending(accountId)
        while (true) {
            val batch = dao.pending(accountId, BATCH_SIZE)
            if (batch.isEmpty()) return DrainResult.DONE
            for (event in batch) {
                // Null as soon as the scope ends: an event is never sent with another account's token.
                val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return DrainResult.DONE
                dao.markSending(event.clientEventId)
                when (val outcome = send(event, authorization)) {
                    Outcome.Accepted -> dao.markSent(event.clientEventId)
                    is Outcome.Transient -> {
                        dao.markPending(event.clientEventId, outcome.reason)
                        return DrainResult.RETRY
                    }
                    Outcome.SessionRejected -> {
                        // The HTTP layer already tried a refresh: wait for a valid session instead of looping.
                        dao.markPending(event.clientEventId, "HTTP 401")
                        return DrainResult.DONE
                    }
                    is Outcome.Permanent -> dao.markFailed(event.clientEventId, outcome.reason)
                }
            }
        }
    }

    private suspend fun admit(
        scope: AccountSessionScope,
        kind: String,
        recommendationId: String?,
        clientEventId: String,
        occurredAtEpochMillis: Long,
        payloadJson: String,
    ): OutboxAdmission {
        val stored = coordinator.runIfCurrent(scope) {
            if (dao.pendingCount(scope.accountId) >= MAX_PENDING_PER_ACCOUNT) return@runIfCurrent false
            if (dao.insert(row(scope.accountId, kind, recommendationId, clientEventId, occurredAtEpochMillis, payloadJson)) == -1L) {
                dao.requeue(clientEventId)
            }
            true
        }
        return when (stored) {
            null -> OutboxAdmission.Rejected("session changed")
            false -> OutboxAdmission.Rejected("outbox full")
            true -> {
                scheduler.schedule(scope.accountId)
                OutboxAdmission.Admitted
            }
        }
    }

    private fun row(
        accountId: String,
        kind: String,
        recommendationId: String?,
        clientEventId: String,
        occurredAtEpochMillis: Long,
        payloadJson: String,
    ) = OutboxEventEntity(
        clientEventId = clientEventId,
        accountId = accountId,
        kind = kind,
        recommendationId = recommendationId,
        payloadJson = payloadJson,
        occurredAtEpochMillis = occurredAtEpochMillis,
        createdAtEpochMillis = clock(),
        status = OutboxStatus.PENDING,
        attempts = 0,
        lastError = null,
    )

    private fun usagePayload(clientEventId: String, eventType: String, feature: String?, occurredAtEpochMillis: Long): String =
        json.encodeToString(
            UsageEventDto(
                clientEventId = clientEventId,
                eventType = eventType,
                feature = feature,
                platform = PLATFORM,
                occurredAt = isoUtc(occurredAtEpochMillis),
            ),
        )

    private suspend fun send(event: OutboxEventEntity, authorization: String): Outcome {
        // Decode outside apiCall: a corrupt row must fail definitively, not be retried forever.
        val call: suspend () -> Response<Unit> = try {
            when (event.kind) {
                OutboxKind.RECOMMENDATION -> {
                    val dto = json.decodeFromString<RecommendationEventDto>(event.payloadJson)
                    suspend { recommendationApi.send(authorization, dto) }
                }
                OutboxKind.USAGE -> {
                    val dto = json.decodeFromString<UsageEventDto>(event.payloadJson)
                    suspend { usageApi.send(authorization, dto) }
                }
                else -> return Outcome.Permanent("unknown kind")
            }
        } catch (_: SerializationException) {
            return Outcome.Permanent("unreadable payload")
        }

        val status = when (val result = apiCall(call)) {
            is ApiResult.Success -> result.data.code()
            ApiResult.Unauthorized -> 401
            is ApiResult.HttpError -> result.code
            is ApiResult.NetworkUnavailable, is ApiResult.InvalidResponse -> null
        }
        // Ids only: no token, coordinates or payload reach the log.
        if (BuildConfig.DEBUG) Log.d(TAG, "${event.kind} clientEventId=${event.clientEventId} status=${status ?: "network error"}")
        return when {
            status != null && status in 200..299 -> Outcome.Accepted
            status == null || status in 500..599 || status == 429 || status == 408 ->
                Outcome.Transient(status?.let { "HTTP $it" } ?: "network error")
            status == 401 -> Outcome.SessionRejected
            else -> Outcome.Permanent("HTTP $status")
        }
    }

    private sealed interface Outcome {
        data object Accepted : Outcome
        data class Transient(val reason: String) : Outcome
        data object SessionRejected : Outcome
        data class Permanent(val reason: String) : Outcome
    }

    private fun isoUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    private companion object {
        const val TAG = "CampusMealOutbox"
        const val PLATFORM = "ANDROID"
        const val IMPRESSION = "RECOMMENDATION_IMPRESSION"
        const val SELECTED = "RECOMMENDATION_SELECTED"
        const val SESSION_STARTED = "SESSION_STARTED"
        const val FEATURE_USED = "FEATURE_USED"
        const val BATCH_SIZE = 20
        const val MAX_PENDING_PER_ACCOUNT = 1_000
        const val RETENTION_DAYS = 80L
    }
}
