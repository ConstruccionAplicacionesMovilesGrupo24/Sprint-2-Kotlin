package com.campusmeal.android.feature.inventory.data.repository

import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.database.CacheMetadataEntity
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.MutationResponse
import com.campusmeal.android.core.network.TransmissionTracker
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.network.mutationCall
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.inventory.data.local.InventoryDraftEntity
import com.campusmeal.android.feature.inventory.data.local.PendingConsumeEntity
import com.campusmeal.android.feature.inventory.data.local.RoomInventoryStore
import com.campusmeal.android.feature.inventory.data.mapper.toCompleteInventoryOrNull
import com.campusmeal.android.feature.inventory.data.mapper.toExpiringItemsOrNull
import com.campusmeal.android.feature.inventory.data.remote.CreateInventoryItemRequestDto
import com.campusmeal.android.feature.inventory.data.remote.InventoryApi
import com.campusmeal.android.feature.inventory.data.remote.InventoryItemsResponseDto
import com.campusmeal.android.feature.inventory.data.remote.InventoryMutationApi
import com.campusmeal.android.feature.inventory.data.remote.UpdateInventoryItemRequestDto
import com.campusmeal.android.feature.inventory.domain.model.CompleteInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.DraftFields
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.domain.model.InventoryRefreshResult
import com.campusmeal.android.feature.inventory.domain.model.MutationOutcome
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * Local-first inventory (S3-03).
 *
 * **Locking.** One [Mutex] per account serializes every refresh (complete and BQ2) and every write, for
 * Home and Inventory alike. A write and its follow-up refresh run in the same critical section through
 * the `…Locked` helpers, which never take the lock again. The inventory lock may span HTTP; the session
 * coordinator lock never does: it is only taken inside `runIfCurrent` for short local work. Lock order is
 * always inventory lock → coordinator lock, and lifecycle cleanup (which runs under the coordinator lock)
 * never takes an inventory lock, so they cannot deadlock.
 *
 * **Writes.** Each Save or Consume is sent at most once per user action, through the mutation client
 * (no transparent connection retries). Its state is persisted before the request starts, so a timeout,
 * a cancellation or process death leaves an outcome-unknown marker instead of being forgotten or replayed.
 */
class OfflineFirstInventoryRepository(
    private val api: InventoryApi,
    private val mutationApi: InventoryMutationApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val store: RoomInventoryStore,
    private val coordinator: AccountSessionCoordinator,
    private val cachePolicy: CachePolicy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : InventoryRepository {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** When the last successful fetch of each metadata key started; lets queued refreshes coalesce. */
    private val lastFetchStartedAt = ConcurrentHashMap<String, Long>()

    /** `accountId|draftId` and `accountId|itemId` of writes whose request this process is running. */
    private val draftsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val consumesInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun lockFor(accountId: String): Mutex = locks.computeIfAbsent(accountId) { Mutex() }

    // Reads

    override fun observeCompleteInventory(scope: AccountSessionScope): Flow<CompleteInventorySnapshot?> =
        store.observeComplete(scope.accountId).onlyWhileCurrent(scope)

    override fun observeExpiringInventory(scope: AccountSessionScope): Flow<ExpiringInventorySnapshot?> =
        store.observeExpiring(scope.accountId).onlyWhileCurrent(scope)

    override fun observeDrafts(scope: AccountSessionScope): Flow<List<InventoryDraft>> =
        store.observeDrafts(scope.accountId).onlyWhileCurrent(scope)

    override fun observePendingConsumes(scope: AccountSessionScope): Flow<List<PendingConsume>> =
        store.observePendingConsumes(scope.accountId).onlyWhileCurrent(scope)

    override suspend fun refreshCompleteInventory(scope: AccountSessionScope, force: Boolean): InventoryRefreshResult =
        coalescedRefresh(scope, RoomInventoryStore.completeKey(scope.accountId), force) { refreshCompleteLocked(scope) }

    override suspend fun refreshExpiringInventory(scope: AccountSessionScope, force: Boolean): InventoryRefreshResult =
        coalescedRefresh(scope, RoomInventoryStore.expiringKey(scope.accountId), force) { refreshExpiringLocked(scope) }

    /**
     * Waits for the account's lock, then skips the request when a refresh that started after this one
     * was requested already succeeded, or (without [force]) while the stored snapshot is fresh.
     */
    private suspend fun coalescedRefresh(
        scope: AccountSessionScope,
        metadataKey: String,
        force: Boolean,
        refreshLocked: suspend () -> InventoryRefreshResult,
    ): InventoryRefreshResult {
        val requestedAt = currentTimeMillis()
        return lockFor(scope.accountId).withLock {
            if (!coordinator.isCurrent(scope)) return@withLock InventoryRefreshResult.SessionEnded
            val metadata = when (metadataKey) {
                RoomInventoryStore.completeKey(scope.accountId) -> store.completeMetadata(scope.accountId)
                else -> store.expiringMetadata(scope.accountId)
            }
            val answeredByNewerFetch = metadata != null && metadata.invalidatedAtEpochMillis == null &&
                (lastFetchStartedAt[metadataKey] ?: Long.MIN_VALUE) >= requestedAt
            if (answeredByNewerFetch || (!force && isFresh(metadata))) {
                InventoryRefreshResult.AlreadyCurrent
            } else {
                refreshLocked()
            }
        }
    }

    /** Fresh: within the TTL, not invalidated by a write, and synchronized on today's Bogotá date. */
    private fun isFresh(metadata: CacheMetadataEntity?): Boolean {
        metadata ?: return false
        val now = currentTimeMillis()
        val synced = Freshness.synced(metadata.lastSyncedAtEpochMillis, metadata.invalidatedAtEpochMillis, cachePolicy.inventory, now)
        return synced is Freshness.Fresh && BogotaCalendar.dateOf(metadata.lastSyncedAtEpochMillis) == BogotaCalendar.today(now)
    }

    private suspend fun refreshCompleteLocked(scope: AccountSessionScope): InventoryRefreshResult =
        fetchLocked(
            scope = scope,
            metadataKey = RoomInventoryStore.completeKey(scope.accountId),
            fetch = { authorization -> api.getInventory(authorization) },
            map = { it.toCompleteInventoryOrNull() },
            replace = { items, syncedAt -> store.replaceComplete(scope.accountId, items, syncedAt) },
        )

    private suspend fun refreshExpiringLocked(scope: AccountSessionScope): InventoryRefreshResult =
        fetchLocked(
            scope = scope,
            metadataKey = RoomInventoryStore.expiringKey(scope.accountId),
            fetch = { authorization -> api.getExpiringInventory(authorization, EXPIRING_WITHIN_DAYS) },
            map = { it.toExpiringItemsOrNull() },
            replace = { items, syncedAt -> store.replaceExpiring(scope.accountId, items, syncedAt) },
        )

    /**
     * One GET whose valid answer replaces a snapshot atomically, only while [scope] is still active. A
     * failure or an invalid answer stores nothing, so the previous snapshot keeps its age. HTTP 401 never
     * deletes anything: the session lifecycle owns cleanup, and token refresh already happened in
     * SessionRefreshInterceptor before a 401 reaches this class.
     */
    private suspend fun fetchLocked(
        scope: AccountSessionScope,
        metadataKey: String,
        fetch: suspend (authorization: String) -> InventoryItemsResponseDto,
        map: (InventoryItemsResponseDto) -> List<InventoryItem>?,
        replace: suspend (List<InventoryItem>, Long) -> Unit,
    ): InventoryRefreshResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return noAuthorization(scope)
        val startedAt = currentTimeMillis()
        return when (val response = apiCall { fetch(authorization) }) {
            is ApiResult.Success -> {
                val items = map(response.data)
                    ?: return InventoryRefreshResult.Failed(InventoryError.InvalidResponse)
                val syncedAt = currentTimeMillis()
                coordinator.runIfCurrent(scope) { replace(items, syncedAt) }
                    ?: return InventoryRefreshResult.SessionEnded
                lastFetchStartedAt[metadataKey] = startedAt
                InventoryRefreshResult.Refreshed
            }
            ApiResult.Unauthorized -> InventoryRefreshResult.Unauthorized
            is ApiResult.NetworkUnavailable -> InventoryRefreshResult.Failed(InventoryError.BackendUnavailable(null))
            is ApiResult.HttpError -> InventoryRefreshResult.Failed(
                if (response.code in 500..599) InventoryError.BackendUnavailable(response.code) else InventoryError.Http(response.code),
            )
            is ApiResult.InvalidResponse -> InventoryRefreshResult.Failed(InventoryError.InvalidResponse)
        }
    }

    // Drafts

    override suspend fun saveDraft(scope: AccountSessionScope, draftId: String, targetItemId: String?, fields: DraftFields) {
        coordinator.runIfCurrent(scope) {
            val existing = store.getDraft(scope.accountId, draftId)
            if (existing?.lastSubmitOutcome == SubmitOutcome.SUBMITTING) return@runIfCurrent
            store.upsertDraft(
                InventoryDraftEntity(
                    accountId = scope.accountId,
                    draftId = draftId,
                    targetItemId = targetItemId,
                    name = fields.name,
                    quantityText = fields.quantityText,
                    unit = fields.unit,
                    expirationDateText = fields.expirationDateText,
                    updatedAtEpochMillis = currentTimeMillis(),
                    // Editing never erases an unresolved outcome: reconciliation is still required.
                    lastSubmitOutcome = if (existing?.lastSubmitOutcome == SubmitOutcome.OUTCOME_UNKNOWN) {
                        SubmitOutcome.OUTCOME_UNKNOWN
                    } else {
                        SubmitOutcome.NONE
                    },
                    lastSubmitAtEpochMillis = existing?.lastSubmitAtEpochMillis,
                    lastHttpCode = null,
                    lastErrorCode = null,
                ),
            )
        }
    }

    override suspend fun discardDraft(scope: AccountSessionScope, draftId: String) {
        if (inFlightKey(scope, draftId) in draftsInFlight) return
        coordinator.runIfCurrent(scope) { store.deleteDraft(scope.accountId, draftId) }
    }

    override suspend fun recoverSubmissions(scope: AccountSessionScope) {
        coordinator.runIfCurrent(scope) {
            store.recoverSubmissions(
                accountId = scope.accountId,
                inFlightDraftIds = draftsInFlight.idsFor(scope),
                inFlightConsumeIds = consumesInFlight.idsFor(scope),
                draftCutoffEpochMillis = currentTimeMillis() - DRAFT_RETENTION.inWholeMilliseconds,
            )
        }
    }

    // Writes

    override suspend fun submitDraft(scope: AccountSessionScope, draftId: String, online: Boolean): MutationOutcome {
        val key = inFlightKey(scope, draftId)
        if (!draftsInFlight.add(key)) return MutationOutcome.AlreadyRunning
        return try {
            lockFor(scope.accountId).withLock { submitDraftLocked(scope, draftId, online) }
        } finally {
            draftsInFlight.remove(key)
        }
    }

    private suspend fun submitDraftLocked(scope: AccountSessionScope, draftId: String, online: Boolean): MutationOutcome {
        val accountId = scope.accountId
        val draft = store.getDraft(accountId, draftId) ?: return MutationOutcome.InvalidInput
        if (draft.lastSubmitOutcome.isUnresolved() && !reconciledSince(accountId, draft.lastSubmitAtEpochMillis)) {
            return MutationOutcome.NeedsReconciliation
        }
        val write = DraftFields(draft.name, draft.quantityText, draft.unit, draft.expirationDateText).toWriteOrNull()
            ?: return MutationOutcome.InvalidInput
        if (!online) {
            recordDraft(scope, draftId, SubmitOutcome.NOT_SENT, currentTimeMillis())
            return MutationOutcome.NotSent(offline = true)
        }
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return MutationOutcome.SessionEnded

        val startedAt = currentTimeMillis()
        coordinator.runIfCurrent(scope) { store.updateDraftOutcome(accountId, draftId, SubmitOutcome.SUBMITTING, startedAt) }
            ?: return MutationOutcome.SessionEnded

        val tracker = TransmissionTracker()
        val response = sendOnce(
            tracker = tracker,
            onInterrupted = { sent ->
                recordDraft(scope, draftId, if (sent) SubmitOutcome.OUTCOME_UNKNOWN else SubmitOutcome.NOT_SENT, startedAt)
            },
        ) {
            val target = draft.targetItemId
            if (target == null) {
                mutationApi.createItem(
                    authorization,
                    CreateInventoryItemRequestDto(write.name, write.quantity, write.unit, write.expirationDate),
                    tracker,
                )
            } else {
                mutationApi.updateItem(
                    authorization,
                    target,
                    UpdateInventoryItemRequestDto(write.name, write.quantity, write.unit, write.expirationDate),
                    tracker,
                )
            }
        }

        return when (response) {
            is MutationResponse.Applied -> confirmLocked(scope, draftId = draftId, consumedItemId = null)
            MutationResponse.Unauthorized -> {
                recordDraft(scope, draftId, SubmitOutcome.REJECTED, startedAt, httpCode = 401)
                MutationOutcome.Unauthorized
            }
            is MutationResponse.Rejected -> {
                recordDraft(scope, draftId, SubmitOutcome.REJECTED, startedAt, response.httpCode, response.errorCode)
                // An edited item that no longer exists disappears from the list after this sync.
                if (response.isItemNotFound()) refreshCompleteLocked(scope)
                MutationOutcome.Rejected(response.httpCode, response.errorCode)
            }
            is MutationResponse.NotSent -> {
                recordDraft(scope, draftId, SubmitOutcome.NOT_SENT, startedAt)
                MutationOutcome.NotSent(offline = false)
            }
            is MutationResponse.OutcomeUnknown -> {
                recordDraft(scope, draftId, SubmitOutcome.OUTCOME_UNKNOWN, startedAt)
                MutationOutcome.OutcomeUnknown
            }
        }
    }

    override suspend fun consumeItem(scope: AccountSessionScope, itemId: String, itemName: String, online: Boolean): MutationOutcome {
        val key = inFlightKey(scope, itemId)
        if (!consumesInFlight.add(key)) return MutationOutcome.AlreadyRunning
        return try {
            lockFor(scope.accountId).withLock { consumeLocked(scope, itemId, itemName, online) }
        } finally {
            consumesInFlight.remove(key)
        }
    }

    private suspend fun consumeLocked(scope: AccountSessionScope, itemId: String, itemName: String, online: Boolean): MutationOutcome {
        val accountId = scope.accountId
        val previous = store.getPendingConsume(accountId, itemId)
        if (previous != null && !reconciledSince(accountId, previous.startedAtEpochMillis)) {
            return MutationOutcome.NeedsReconciliation
        }
        // Nothing is recorded for an offline refusal; an earlier marker, if any, stays as it was.
        if (!online) return MutationOutcome.NotSent(offline = true)
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return MutationOutcome.SessionEnded

        val startedAt = currentTimeMillis()
        coordinator.runIfCurrent(scope) {
            store.upsertPendingConsume(PendingConsumeEntity(accountId, itemId, itemName, SubmitOutcome.SUBMITTING, startedAt))
        } ?: return MutationOutcome.SessionEnded

        val tracker = TransmissionTracker()
        val response = sendOnce(
            tracker = tracker,
            onInterrupted = { sent -> if (sent) recordConsumeUnknown(scope, itemId, itemName, startedAt) else clearConsume(scope, itemId) },
        ) {
            mutationApi.deactivateItem(authorization, itemId, tracker)
        }

        return when (response) {
            is MutationResponse.Applied -> confirmLocked(scope, draftId = null, consumedItemId = itemId)
            is MutationResponse.Rejected ->
                if (response.isItemNotFound()) {
                    // Already inactive: after an unknown attempt this is that attempt's success (plan §3).
                    confirmLocked(scope, draftId = null, consumedItemId = itemId)
                    MutationOutcome.AlreadyConsumed
                } else {
                    clearConsume(scope, itemId)
                    MutationOutcome.Rejected(response.httpCode, response.errorCode)
                }
            MutationResponse.Unauthorized -> {
                clearConsume(scope, itemId)
                MutationOutcome.Unauthorized
            }
            is MutationResponse.NotSent -> {
                clearConsume(scope, itemId)
                MutationOutcome.NotSent(offline = false)
            }
            is MutationResponse.OutcomeUnknown -> {
                recordConsumeUnknown(scope, itemId, itemName, startedAt)
                MutationOutcome.OutcomeUnknown
            }
        }
    }

    override suspend fun dismissPendingConsume(scope: AccountSessionScope, itemId: String) {
        if (inFlightKey(scope, itemId) in consumesInFlight) return
        val marker = store.getPendingConsume(scope.accountId, itemId) ?: return
        // Only after a sync that can show the user whether the item is gone.
        if (!reconciledSince(scope.accountId, marker.startedAtEpochMillis)) return
        clearConsume(scope, itemId)
    }

    /**
     * Confirmed success: removes the draft or marker and invalidates both snapshots in one transaction,
     * then refreshes the complete inventory inside the same critical section. A failed refresh keeps the
     * confirmation; the snapshot simply stays marked stale.
     */
    private suspend fun confirmLocked(scope: AccountSessionScope, draftId: String?, consumedItemId: String?): MutationOutcome {
        val confirmedAt = currentTimeMillis()
        coordinator.runIfCurrent(scope) { store.confirmApplied(scope.accountId, draftId, consumedItemId, confirmedAt) }
            ?: return MutationOutcome.SessionEnded
        val refreshed = refreshCompleteLocked(scope) == InventoryRefreshResult.Refreshed
        return MutationOutcome.Confirmed(listRefreshed = refreshed)
    }

    /**
     * Sends one attempt. If the coroutine is cancelled, [onInterrupted] records whether the request may
     * have reached the server, without being cancelled itself, before the cancellation propagates.
     */
    private suspend fun <T> sendOnce(
        tracker: TransmissionTracker,
        onInterrupted: suspend (sent: Boolean) -> Unit,
        call: suspend () -> Response<T>,
    ): MutationResponse<T> =
        try {
            mutationCall(tracker, call)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onInterrupted(tracker.started) }
            throw e
        }

    /** True once a complete sync finished after [submittedAt], so the user can see its effect. */
    private suspend fun reconciledSince(accountId: String, submittedAt: Long?): Boolean {
        val metadata = store.completeMetadata(accountId) ?: return false
        return submittedAt == null || metadata.lastSyncedAtEpochMillis > submittedAt
    }

    private suspend fun recordDraft(
        scope: AccountSessionScope,
        draftId: String,
        outcome: SubmitOutcome,
        atEpochMillis: Long,
        httpCode: Int? = null,
        errorCode: String? = null,
    ) {
        coordinator.runIfCurrent(scope) {
            store.updateDraftOutcome(scope.accountId, draftId, outcome, atEpochMillis, httpCode, errorCode)
        }
    }

    private suspend fun recordConsumeUnknown(scope: AccountSessionScope, itemId: String, itemName: String, startedAt: Long) {
        coordinator.runIfCurrent(scope) {
            store.upsertPendingConsume(PendingConsumeEntity(scope.accountId, itemId, itemName, SubmitOutcome.OUTCOME_UNKNOWN, startedAt))
        }
    }

    private suspend fun clearConsume(scope: AccountSessionScope, itemId: String) {
        coordinator.runIfCurrent(scope) { store.deletePendingConsume(scope.accountId, itemId) }
    }

    private fun noAuthorization(scope: AccountSessionScope): InventoryRefreshResult =
        if (coordinator.isCurrent(scope)) InventoryRefreshResult.Unauthorized else InventoryRefreshResult.SessionEnded

    /** A stream of a scope that is no longer active emits nothing more, so no screen sees another session's rows. */
    private fun <T> Flow<T>.onlyWhileCurrent(scope: AccountSessionScope): Flow<T> =
        transform { value -> if (coordinator.isCurrent(scope)) emit(value) }

    private fun inFlightKey(scope: AccountSessionScope, id: String) = "${scope.accountId}|$id"

    private fun Set<String>.idsFor(scope: AccountSessionScope): List<String> {
        val prefix = "${scope.accountId}|"
        return filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    private fun SubmitOutcome.isUnresolved() = this == SubmitOutcome.OUTCOME_UNKNOWN || this == SubmitOutcome.SUBMITTING

    private fun MutationResponse.Rejected.isItemNotFound() = httpCode == 404 && errorCode == ITEM_NOT_FOUND_CODE

    private companion object {
        val DRAFT_RETENTION = 7.days
        const val ITEM_NOT_FOUND_CODE = "INVENTORY_ITEM_NOT_FOUND"
    }
}
