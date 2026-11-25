package com.campusmeal.android.core.outbox

import android.util.Log
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.common.TraceSections
import com.campusmeal.android.core.common.traceAsyncSection
import com.campusmeal.android.core.common.traceSection
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
import com.campusmeal.android.feature.reviews.data.CreateReviewRequestDto
import com.campusmeal.android.feature.reviews.data.ReviewApi
import com.campusmeal.android.feature.reviews.data.ReviewOutboxPayload
import androidx.room.withTransaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * events; since S4-04 also structured restaurant reviews ([OutboxKind.REVIEW]), and since S4-10 BQ1
 * telemetry ([OutboxKind.PERFORMANCE]), all through the same drain.
 *
 * An event is written to Room first, inside the [AccountSessionCoordinator] lock and only while its
 * scope is still active. A unique WorkManager chain per account then sends it with its stable
 * `clientEventId`; the backend deduplicates, so a replay after a lost acknowledgement is safe.
 * Rows belong to the account that produced them and are only sent with that account's credentials
 * ([AuthorizationHeaderProvider] returns null once the scope ends). Logout cancels the work but keeps
 * the rows, which are sent the next time the same account is activated.
 */
class OutboxRepository(
    private val database: CampusMealDatabase,
    private val recommendationApi: RecommendationEventsApi,
    private val usageApi: UsageEventsApi,
    private val reviewApi: ReviewApi,
    private val performanceApi: PerformanceEventsApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val scheduler: OutboxScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Resolved lazily: listeners (such as the review store) may depend on this repository. */
    private val deliveryListeners: () -> List<OutboxDeliveryListener> = { emptyList() },
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
    ): OutboxAdmission = traceAsyncSection(TraceSections.OUTBOX_ENQUEUE) {
        val dto = RecommendationEventDto(
            clientEventId = clientEventId,
            recommendationId = recommendationId,
            eventType = if (selectedAlternative == null) IMPRESSION else SELECTED,
            selectedAlternative = selectedAlternative,
            platform = PLATFORM,
            occurredAt = isoUtc(occurredAtEpochMillis),
        )
        admit(scope, OutboxKind.RECOMMENDATION, recommendationId, clientEventId, occurredAtEpochMillis, json.encodeToString(dto))
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
        zoneId: String? = null,
        foodCategory: String? = null,
        budgetBand: String? = null,
    ): OutboxAdmission = traceAsyncSection(TraceSections.OUTBOX_ENQUEUE) {
        admit(scope, OutboxKind.USAGE, null, clientEventId, occurredAtEpochMillis, usagePayload(clientEventId, FEATURE_USED, feature.name, occurredAtEpochMillis, zoneId, foodCategory, budgetBand))
    }

    /**
     * Admits one restaurant review (S4-04). [inSameTransaction] runs in the same Room transaction as the
     * outbox insert (the caller stores its submission record and deletes the draft there), so either both
     * exist or neither does. `clientEventId` is the review's `clientReviewId`: a resend after a lost
     * acknowledgement is answered 200 by the backend with the same receipt.
     */
    suspend fun enqueueReview(
        scope: AccountSessionScope,
        restaurantId: String,
        body: CreateReviewRequestDto,
        occurredAtEpochMillis: Long,
        inSameTransaction: suspend () -> Unit,
    ): OutboxAdmission {
        val payload = json.encodeToString(ReviewOutboxPayload(ReviewOutboxPayload.VERSION, restaurantId, body))
        return admit(scope, OutboxKind.REVIEW, null, body.clientReviewId, occurredAtEpochMillis, payload, inSameTransaction)
    }

    /**
     * Admits one BQ1 telemetry record (S4-10). Its ids are generated by the caller, so admitting the same
     * record twice (for example a crash marker imported again) stores one row.
     */
    suspend fun enqueuePerformance(scope: AccountSessionScope, event: PerformanceEvent): OutboxAdmission =
        traceAsyncSection(TraceSections.OUTBOX_ENQUEUE) {
            val body = PerformanceEventDto(
                clientEventId = event.clientEventId,
                eventType = event.type.name,
                sessionId = event.sessionId,
                loadId = event.loadId,
                deviceModel = event.device.deviceModel,
                osVersion = event.device.osVersion,
                appVersion = event.device.appVersion,
                platform = PLATFORM,
                screen = event.screen,
                exceptionClass = event.exceptionClass,
                durationMs = event.durationMs,
                outcome = event.outcome?.name,
                injected = event.injected,
                occurredAt = isoUtc(event.occurredAtEpochMillis),
            )
            val payload = json.encodeToString(PerformanceOutboxPayload(PerformanceOutboxPayload.VERSION, body))
            admit(scope, OutboxKind.PERFORMANCE, null, event.clientEventId, event.occurredAtEpochMillis, payload)
        }

    // Synchronization center (S4-10): account-scoped, bounded, never the payloads.

    fun observeOpen(accountId: String): Flow<List<OutboxItem>> = dao.observeOpen(accountId, MAX_OPEN_ITEMS)

    fun observeSent(accountId: String): Flow<List<OutboxItem>> = dao.observeSent(accountId, MAX_SENT_ITEMS)

    fun observeCounts(accountId: String): Flow<Map<String, Int>> =
        dao.observeCounts(accountId).map { counts -> counts.associate { it.status to it.total } }

    /** Queues a failed event again with its original id and starts the account's single chain. */
    suspend fun retry(scope: AccountSessionScope, clientEventId: String): Boolean {
        val changed = coordinator.runIfCurrent(scope) { dao.requeueOwned(scope.accountId, clientEventId) } ?: return false
        if (changed > 0) scheduler.schedule(scope.accountId)
        return changed > 0
    }

    /** Asks for a delivery of whatever is pending. It only appends to the account's chain, so tapping twice is harmless. */
    suspend fun requestDelivery(scope: AccountSessionScope) {
        val pending = coordinator.runIfCurrent(scope) { dao.pendingCount(scope.accountId) } ?: return
        if (pending > 0) scheduler.schedule(scope.accountId)
    }

    suspend fun dismiss(scope: AccountSessionScope, clientEventId: String) {
        coordinator.runIfCurrent(scope) { dao.dismiss(scope.accountId, clientEventId) }
    }

    /**
     * Retention policy (`SPRINT4_SCOPE.md` §7). Also runs when the synchronization center opens, so what
     * expired while offline is visible without a successful drain.
     */
    suspend fun applyRetention() {
        val now = clock()
        dao.expirePending(
            before = now - TimeUnit.DAYS.toMillis(RETENTION_DAYS),
            reviewBefore = now - TimeUnit.DAYS.toMillis(REVIEW_RETENTION_DAYS),
            reason = EXPIRED_REASON,
            now = now,
        )
        dao.purgeClosedBefore(now - TimeUnit.DAYS.toMillis(CLOSED_RETENTION_DAYS))
        dao.purgeSentBefore(now - TimeUnit.DAYS.toMillis(1))
    }

    /** `PENDING`, `SENDING`, `SENT`, `FAILED`, `EXPIRED`, or null when the event does not exist. */
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
        // First, so a row left in SENDING by a crash can also expire.
        dao.resetSending(accountId)
        applyRetention()
        while (true) {
            val batch = dao.pending(accountId, BATCH_SIZE)
            if (batch.isEmpty()) return DrainResult.DONE
            for (event in batch) {
                // Null as soon as the scope ends: an event is never sent with another account's token.
                val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return DrainResult.DONE
                dao.markSending(event.clientEventId, clock())
                when (val outcome = send(event, authorization)) {
                    Outcome.Accepted -> {
                        dao.markSent(event.clientEventId, clock())
                        deliveryListeners().forEach { it.onDelivered(event) }
                    }
                    is Outcome.Transient -> {
                        dao.markPending(event.clientEventId, outcome.reason)
                        return DrainResult.RETRY
                    }
                    Outcome.SessionRejected -> {
                        // The HTTP layer already tried a refresh: wait for a valid session instead of looping.
                        dao.markPending(event.clientEventId, "HTTP 401")
                        return DrainResult.DONE
                    }
                    is Outcome.Permanent -> {
                        dao.markFailed(event.clientEventId, outcome.reason, clock())
                        deliveryListeners().forEach { it.onRejected(event, outcome.reason) }
                    }
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
        inSameTransaction: (suspend () -> Unit)? = null,
    ): OutboxAdmission {
        val stored = coordinator.runIfCurrent(scope) {
            database.withTransaction {
                if (dao.pendingCount(scope.accountId) >= MAX_PENDING_PER_ACCOUNT) return@withTransaction false
                if (dao.insert(row(scope.accountId, kind, recommendationId, clientEventId, occurredAtEpochMillis, payloadJson)) == -1L) {
                    dao.requeue(clientEventId)
                }
                inSameTransaction?.invoke()
                true
            }
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

    private fun usagePayload(
        clientEventId: String,
        eventType: String,
        feature: String?,
        occurredAtEpochMillis: Long,
        zoneId: String? = null,
        foodCategory: String? = null,
        budgetBand: String? = null,
    ): String =
        json.encodeToString(
            UsageEventDto(
                clientEventId = clientEventId,
                eventType = eventType,
                feature = feature,
                platform = PLATFORM,
                occurredAt = isoUtc(occurredAtEpochMillis),
                zoneId = zoneId,
                foodCategory = foodCategory,
                budgetBand = budgetBand,
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
                OutboxKind.REVIEW -> {
                    val envelope = json.decodeFromString<ReviewOutboxPayload>(event.payloadJson)
                    // A newer app wrote a format this one cannot send: fail visibly instead of guessing.
                    if (envelope.v != ReviewOutboxPayload.VERSION) return Outcome.Permanent(UNREADABLE_PAYLOAD)
                    suspend { reviewApi.submit(authorization, envelope.restaurantId, envelope.body) }
                }
                OutboxKind.PERFORMANCE -> {
                    val envelope = json.decodeFromString<PerformanceOutboxPayload>(event.payloadJson)
                    if (envelope.v != PerformanceOutboxPayload.VERSION) return Outcome.Permanent(UNREADABLE_PAYLOAD)
                    suspend { performanceApi.send(authorization, envelope.body) }
                }
                else -> return Outcome.Permanent("unknown kind")
            }
        } catch (_: SerializationException) {
            return Outcome.Permanent(UNREADABLE_PAYLOAD)
        }

        val result = apiCall(call)
        val status = when (result) {
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
            else -> {
                // Only the API's error code is kept (an enum such as REVIEW_ALREADY_SUBMITTED_TODAY), never the message.
                val errorCode = (result as? ApiResult.Success)?.data?.errorBody()
                    ?.let { body -> runCatching { body.string() }.getOrNull() }
                    ?.let { ERROR_CODE.find(it)?.groupValues?.get(1) }
                Outcome.Permanent(listOfNotNull("HTTP $status", errorCode).joinToString(" "))
            }
        }
    }

    private sealed interface Outcome {
        data object Accepted : Outcome
        data class Transient(val reason: String) : Outcome
        data object SessionRejected : Outcome
        data class Permanent(val reason: String) : Outcome
    }

    private fun isoUtc(epochMillis: Long): String =
        traceSection(TraceSections.OUTBOX_ISO_UTC) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMillis))
        }

    companion object {
        const val UNREADABLE_PAYLOAD = "unreadable payload"
        const val EXPIRED_REASON = "expired before delivery"

        private const val TAG = "CampusMealOutbox"
        private const val PLATFORM = "ANDROID"
        private const val IMPRESSION = "RECOMMENDATION_IMPRESSION"
        private const val SELECTED = "RECOMMENDATION_SELECTED"
        private const val SESSION_STARTED = "SESSION_STARTED"
        private const val FEATURE_USED = "FEATURE_USED"
        private const val BATCH_SIZE = 20
        private const val MAX_PENDING_PER_ACCOUNT = 1_000
        /** The backend accepts events up to 90 days old. */
        private const val RETENTION_DAYS = 80L

        /** The review endpoint accepts reviews up to 30 days old. */
        private const val REVIEW_RETENTION_DAYS = 30L

        /** How long FAILED and EXPIRED rows stay visible after closing. */
        private const val CLOSED_RETENTION_DAYS = 30L
        private const val MAX_OPEN_ITEMS = 100
        private const val MAX_SENT_ITEMS = 20
        private val ERROR_CODE = Regex(""""code"\s*:\s*"([A-Z_]{1,64})"""")
    }
}
