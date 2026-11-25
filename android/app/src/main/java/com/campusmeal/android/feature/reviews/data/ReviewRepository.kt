package com.campusmeal.android.feature.reviews.data

import android.util.Log
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.outbox.OutboxAdmission
import com.campusmeal.android.core.outbox.OutboxDeliveryListener
import com.campusmeal.android.core.outbox.OutboxEventEntity
import com.campusmeal.android.core.outbox.OutboxKind
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.OutboxStatus
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.analytics.data.ReportSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException

/** The five 1–5 scores and the observed wait. Checked with [isValid] before admission. */
data class ReviewScores(
    val overall: Int,
    val portion: Int,
    val taste: Int,
    val freshness: Int,
    val value: Int,
    val waitMinutes: Int,
) {
    fun isValid(): Boolean =
        listOf(overall, portion, taste, freshness, value).all { it in 1..5 } && waitMinutes in 0..MAX_WAIT_MINUTES

    companion object {
        const val MAX_WAIT_MINUTES = 180
    }
}

/** What the user's own review is doing; never mixed into the confirmed server summary. */
enum class SubmissionState {
    /** Stored on the device, not attempted yet (for example offline). */
    QUEUED,

    /** A send is in progress. */
    SENDING,

    /** Sent at least once without an acknowledgement (lost answer, process death): it is resent with the same id. */
    UNCONFIRMED,

    /** The backend acknowledged it (201, or 200 for the same review sent again). */
    SENT,

    /** The backend refused it definitively; [ReviewSubmission.rejectionReason] says why. */
    REJECTED,

    /** Its outbox row no longer exists and it was never acknowledged (retention limit). */
    NOT_DELIVERED,
}

data class ReviewSubmission(
    val clientReviewId: String,
    val reviewDate: String,
    val scores: ReviewScores,
    val createdAtEpochMillis: Long,
    val state: SubmissionState,
    val rejectionReason: String?,
)

sealed interface SubmitResult {
    data class Admitted(val clientReviewId: String) : SubmitResult

    /** A non-rejected review of this restaurant already exists for today's Bogotá date on this device. */
    data object AlreadyReviewedToday : SubmitResult

    data object InvalidScores : SubmitResult

    /** Nothing was stored: the session changed or the outbox is full. */
    data class NotStored(val reason: String) : SubmitResult
}

sealed interface ReviewSummaryResult {
    data class Content(
        val response: RestaurantReviewsResponseDto,
        val source: ReportSource,
        val freshness: Freshness.Synced,
    ) : ReviewSummaryResult

    data object Unauthorized : ReviewSummaryResult

    /** Nothing cached and the request failed; [httpCode] null for connectivity. */
    data class Unavailable(val httpCode: Int?) : ReviewSummaryResult
}

/**
 * S4-04: structured restaurant reviews on the device.
 *
 * - Drafts: one per account and restaurant in Room, written on every change, so they survive process death.
 * - Submission: [submit] stores the submission record, the `REVIEW` outbox row and the draft deletion in one
 *   transaction under the session lock; the per-account outbox drain sends it with a stable `clientReviewId`.
 * - Summary: cached 15 minutes per account and restaurant (LRU 50), invalidated when one of the account's
 *   reviews of that restaurant is confirmed ([onDelivered]).
 *
 * Every account-owned write checks the captured [AccountSessionScope]; reads of another account return nothing.
 */
class ReviewRepository(
    private val api: ReviewApi,
    private val outbox: OutboxRepository,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    database: CampusMealDatabase,
    private val cachePolicy: CachePolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccountScopedStore, OutboxDeliveryListener {

    private val dao = database.reviewDao()
    private val outboxDao = database.outboxDao()
    private val json = ApiClientFactory.json

    // Drafts

    suspend fun draft(scope: AccountSessionScope, restaurantId: String): ReviewDraftEntity? =
        if (coordinator.isCurrent(scope)) dao.draft(scope.accountId, restaurantId) else null

    suspend fun saveDraft(scope: AccountSessionScope, draft: ReviewDraftEntity) {
        if (draft.accountId != scope.accountId) return
        coordinator.runIfCurrent(scope) { dao.upsertDraft(draft.copy(updatedAtEpochMillis = clock())) }
    }

    suspend fun discardDraft(scope: AccountSessionScope, restaurantId: String) {
        coordinator.runIfCurrent(scope) { dao.deleteDraft(scope.accountId, restaurantId) }
    }

    // Submission

    /**
     * Admits the review for delivery. The caller serializes calls (one submission at a time per screen);
     * the same-day check mirrors the backend's one-review-per-day rule to avoid a predictable 409.
     */
    suspend fun submit(
        scope: AccountSessionScope,
        restaurantId: String,
        restaurantName: String,
        scores: ReviewScores,
    ): SubmitResult {
        if (!scores.isValid()) return SubmitResult.InvalidScores
        if (!coordinator.isCurrent(scope)) return SubmitResult.NotStored("session changed")
        val now = clock()
        val reviewDate = BogotaCalendar.dateOf(now)
        if (dao.activeSubmissionsOn(scope.accountId, restaurantId, reviewDate) > 0) return SubmitResult.AlreadyReviewedToday

        val clientReviewId = UUID.randomUUID().toString()
        val body = CreateReviewRequestDto(
            clientReviewId = clientReviewId,
            overall = scores.overall,
            portion = scores.portion,
            taste = scores.taste,
            freshness = scores.freshness,
            value = scores.value,
            waitMinutes = scores.waitMinutes,
            occurredAt = isoUtc(now),
        )
        val admission = outbox.enqueueReview(scope, restaurantId, body, now) {
            dao.insertSubmission(
                ReviewSubmissionEntity(
                    clientReviewId = clientReviewId,
                    accountId = scope.accountId,
                    restaurantId = restaurantId,
                    restaurantName = restaurantName,
                    overall = scores.overall,
                    portion = scores.portion,
                    taste = scores.taste,
                    freshness = scores.freshness,
                    value = scores.value,
                    waitMinutes = scores.waitMinutes,
                    occurredAtEpochMillis = now,
                    reviewDate = reviewDate,
                    createdAtEpochMillis = now,
                    deliveredAtEpochMillis = null,
                    rejectionReason = null,
                ),
            )
            dao.deleteDraft(scope.accountId, restaurantId)
        }
        // Ids only in the log: no scores, tokens or account data.
        if (BuildConfig.DEBUG) Log.d(TAG, "REVIEW clientReviewId=$clientReviewId admission=$admission")
        return when (admission) {
            OutboxAdmission.Admitted -> SubmitResult.Admitted(clientReviewId)
            is OutboxAdmission.Rejected -> SubmitResult.NotStored(admission.reason)
        }
    }

    /** The account's own reviews of [restaurantId], newest first, with their delivery state. */
    fun observeSubmissions(scope: AccountSessionScope, restaurantId: String): Flow<List<ReviewSubmission>> {
        if (!coordinator.isCurrent(scope)) return flowOf(emptyList())
        return combine(
            dao.observeSubmissions(scope.accountId, restaurantId),
            outboxDao.observeKind(scope.accountId, OutboxKind.REVIEW),
        ) { submissions, rows ->
            val byId = rows.associateBy { it.clientEventId }
            submissions.map { it.toModel(byId[it.clientReviewId]) }
        }.distinctUntilChanged()
    }

    // Summary

    /** Cache first while fresh and not invalidated; otherwise the network, falling back to the saved copy. */
    suspend fun summary(scope: AccountSessionScope, restaurantId: String, forceRefresh: Boolean): ReviewSummaryResult {
        val cachedEntity = if (coordinator.isCurrent(scope)) dao.summary(scope.accountId, restaurantId) else null
        val cached = cachedEntity?.toContent()
        if (cached != null) coordinator.runIfCurrent(scope) { dao.touchSummary(scope.accountId, restaurantId, clock()) }
        if (!forceRefresh && cached?.freshness is Freshness.Fresh) return cached

        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope) ?: return ReviewSummaryResult.Unauthorized
        return when (val result = apiCall { api.summary(authorization, restaurantId, RECENT_LIMIT) }) {
            is ApiResult.Success -> {
                val response = result.data
                if (response.restaurantId != restaurantId) return cached ?: ReviewSummaryResult.Unavailable(null)
                val now = clock()
                coordinator.runIfCurrent(scope) {
                    dao.upsertSummary(
                        ReviewSummaryCacheEntity(
                            accountId = scope.accountId,
                            restaurantId = restaurantId,
                            contractVersion = response.contractVersion,
                            responseJson = json.encodeToString(RestaurantReviewsResponseDto.serializer(), response),
                            fetchedAtEpochMillis = now,
                            accessedAtEpochMillis = now,
                            invalidatedAtEpochMillis = null,
                        ),
                    )
                    dao.pruneSummaries(scope.accountId, MAX_CACHED_SUMMARIES)
                }
                ReviewSummaryResult.Content(response, ReportSource.NETWORK, Freshness.Fresh(now))
            }
            ApiResult.Unauthorized -> ReviewSummaryResult.Unauthorized
            is ApiResult.HttpError -> cached ?: ReviewSummaryResult.Unavailable(result.code)
            is ApiResult.NetworkUnavailable, is ApiResult.InvalidResponse -> cached ?: ReviewSummaryResult.Unavailable(null)
        }
    }

    /** Emits when the cached summary changes, notably when a confirmed review invalidates it. */
    fun observeSummaryInvalidation(scope: AccountSessionScope, restaurantId: String): Flow<Long?> =
        if (coordinator.isCurrent(scope)) dao.observeSummaryInvalidation(scope.accountId, restaurantId).distinctUntilChanged() else flowOf(null)

    // Outbox outcome (drain worker, local work only)

    override suspend fun onDelivered(event: OutboxEventEntity) {
        if (event.kind != OutboxKind.REVIEW) return
        val now = clock()
        dao.markDelivered(event.clientEventId, now)
        val restaurantId = dao.submission(event.clientEventId)?.restaurantId ?: restaurantIdOf(event) ?: return
        // The confirmed review now counts on the server: the account's cached summary is behind it.
        dao.invalidateSummary(event.accountId, restaurantId, now)
    }

    override suspend fun onRejected(event: OutboxEventEntity, reason: String) {
        if (event.kind == OutboxKind.REVIEW) dao.markRejected(event.clientEventId, reason)
    }

    // Account lifecycle

    override suspend fun deleteAccount(accountId: String) {
        dao.deleteDrafts(accountId)
        dao.deleteSummaries(accountId)
        dao.deleteSettledSubmissions(accountId)
    }

    override suspend fun deleteAllExcept(accountId: String) {
        dao.deleteDraftsExcept(accountId)
        dao.deleteSummariesExcept(accountId)
        dao.deleteSettledSubmissionsExcept(accountId)
    }

    private fun ReviewSubmissionEntity.toModel(row: OutboxEventEntity?): ReviewSubmission {
        val state = when {
            deliveredAtEpochMillis != null -> SubmissionState.SENT
            rejectionReason != null -> SubmissionState.REJECTED
            row == null -> SubmissionState.NOT_DELIVERED
            row.status == OutboxStatus.SENT -> SubmissionState.SENT
            row.status == OutboxStatus.FAILED -> SubmissionState.REJECTED
            row.status == OutboxStatus.SENDING -> SubmissionState.SENDING
            row.attempts > 0 -> SubmissionState.UNCONFIRMED
            else -> SubmissionState.QUEUED
        }
        return ReviewSubmission(
            clientReviewId = clientReviewId,
            reviewDate = reviewDate,
            scores = ReviewScores(overall, portion, taste, freshness, value, waitMinutes),
            createdAtEpochMillis = createdAtEpochMillis,
            state = state,
            rejectionReason = rejectionReason ?: row?.lastError?.takeIf { state == SubmissionState.REJECTED },
        )
    }

    private fun ReviewSummaryCacheEntity.toContent(): ReviewSummaryResult.Content? =
        try {
            ReviewSummaryResult.Content(
                response = json.decodeFromString(RestaurantReviewsResponseDto.serializer(), responseJson),
                source = ReportSource.CACHE,
                freshness = Freshness.synced(fetchedAtEpochMillis, invalidatedAtEpochMillis, cachePolicy.reviewSummary, clock()),
            )
        } catch (_: SerializationException) {
            null
        }

    private fun restaurantIdOf(event: OutboxEventEntity): String? =
        try {
            json.decodeFromString(ReviewOutboxPayload.serializer(), event.payloadJson).restaurantId
        } catch (_: SerializationException) {
            null
        }

    private fun isoUtc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    companion object {
        private const val TAG = "CampusMealReviews"
        const val RECENT_LIMIT = 5
        const val MAX_CACHED_SUMMARIES = 50
    }
}
