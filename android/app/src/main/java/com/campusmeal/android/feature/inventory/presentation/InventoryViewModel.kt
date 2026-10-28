package com.campusmeal.android.feature.inventory.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.inventory.domain.model.CompleteInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.DraftFields
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraftIds
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryRefreshResult
import com.campusmeal.android.feature.inventory.domain.model.MutationOutcome
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why the last refresh did not update the snapshot. The stored snapshot, if any, is still shown. */
sealed interface RefreshProblem {
    data object Offline : RefreshProblem
    data class ServerUnavailable(val httpCode: Int?) : RefreshProblem
    data class Refused(val httpCode: Int) : RefreshProblem
    data object InvalidResponse : RefreshProblem
    data object SessionExpired : RefreshProblem
}

data class RefreshUi(val running: Boolean = false, val problem: RefreshProblem? = null)

/** The stored complete snapshot as displayed on [todayIsoDate]. */
data class SnapshotUi(
    val sections: InventorySections,
    val syncedAtEpochMillis: Long,
    /** Older than the TTL or invalidated: shown while (or instead of) refreshing. */
    val stale: Boolean,
    /** A confirmed write happened after this sync; the list has not caught up yet. */
    val awaitingRefresh: Boolean,
    val todayIsoDate: String,
)

enum class DraftStatus {
    /** Saved on the device, not submitted. */
    LOCAL,
    NOT_SENT,
    REJECTED,
    SUBMITTING,

    /** The outcome is unknown and no complete sync has happened since: refresh first. */
    UNKNOWN_NEEDS_SYNC,

    /** The outcome is unknown, the list was refreshed since: the user reviews it and decides. */
    UNKNOWN_REVIEW,
}

data class DraftUi(
    val draftId: String,
    val targetItemId: String?,
    val title: String,
    val fields: DraftFields,
    val savedAtEpochMillis: Long,
    val status: DraftStatus,
    val lastHttpCode: Int?,
    /** Edit drafts only: the item is no longer in the refreshed list. */
    val targetMissing: Boolean,
    /** Edit drafts only: the item already shows exactly these values. */
    val targetMatches: Boolean,
)

enum class ConsumeNoticeStatus { CHECKING, CONSUMED, NOT_APPLIED }

data class ConsumeNoticeUi(val itemId: String, val itemName: String, val status: ConsumeNoticeStatus)

/** The add/edit form that is open. Its fields are saved as a draft while the user types. */
data class DraftEditorUi(val draftId: String, val targetItemId: String?, val fields: DraftFields)

data class InventoryUiState(
    /** False until Room has answered once; never confused with "no data". */
    val loaded: Boolean = false,
    /** Null: no complete snapshot was ever synchronized on this device for this account. */
    val snapshot: SnapshotUi? = null,
    val refresh: RefreshUi = RefreshUi(),
    val drafts: List<DraftUi> = emptyList(),
    val consumeNotices: List<ConsumeNoticeUi> = emptyList(),
)

/**
 * Bound to the session [scope] it was created for; results of another scope are never shown.
 *
 * The screen renders Room immediately and refreshes in the background when the snapshot is missing,
 * stale, invalidated or from another Bogotá date. Writes only happen on an explicit Save or Consume.
 */
class InventoryViewModel(
    private val repository: InventoryRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val cachePolicy: CachePolicy,
    private val savedState: SavedStateHandle,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val today = MutableStateFlow(BogotaCalendar.today(currentTimeMillis()))
    private val refreshUi = MutableStateFlow(RefreshUi())
    private val busyKeys = MutableStateFlow<Set<String>>(emptySet())
    private var online = true

    private val _message = MutableStateFlow<String?>(null)

    /** Result of the last Save or Consume that needs the user's attention; null when none. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _editor = MutableStateFlow(restoreEditor())

    /** The open add/edit form, restored after process recreation from the saved draft id. */
    val editor: StateFlow<DraftEditorUi?> = _editor.asStateFlow()

    private var refreshJob: Job? = null
    private var draftSaveJob: Job? = null

    private val local = combine(
        repository.observeCompleteInventory(scope),
        repository.observeDrafts(scope),
        repository.observePendingConsumes(scope),
    ) { snapshot, drafts, consumes -> LocalData(snapshot, drafts, consumes) }

    val uiState: StateFlow<InventoryUiState> =
        combine(local, today, refreshUi, busyKeys) { data, today, refresh, busy -> data.toUiState(today, refresh, busy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    init {
        viewModelScope.launch {
            repository.recoverSubmissions(scope)
            refresh(force = false)
        }
        // Labels and usable-ingredient eligibility follow the Bogotá calendar across midnight. The timer
        // can run late while the device sleeps, so the screen also calls onScreenStarted on resume.
        viewModelScope.launch {
            while (isActive) {
                delay(BogotaCalendar.millisUntilNextMidnight(currentTimeMillis()) + MIDNIGHT_MARGIN_MILLIS)
                onDateMaybeChanged()
            }
        }
    }

    /** The screen became visible (first composition or return to the foreground). */
    fun onScreenStarted() {
        onDateMaybeChanged()
        refresh(force = false)
    }

    fun setOnline(online: Boolean) {
        this.online = online
    }

    /** Connectivity returned while the screen is visible: retry a failed or due refresh once. */
    fun onReconnected() {
        refresh(force = refreshUi.value.problem != null)
    }

    /** Explicit "Refresh" or "Try again". */
    fun refreshNow() = refresh(force = true)

    fun cancelRefresh() {
        refreshJob?.cancel()
    }

    private fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshUi.update { it.copy(running = true) }
            try {
                val result = repository.refreshCompleteInventory(scope, force)
                if (!coordinator.isCurrent(scope)) return@launch
                refreshUi.value = RefreshUi(running = false, problem = result.toProblem())
            } finally {
                refreshUi.update { it.copy(running = false) }
            }
        }
    }

    private fun onDateMaybeChanged() {
        val now = BogotaCalendar.today(currentTimeMillis())
        if (now != today.value) {
            today.value = now
            refresh(force = false)
        }
    }

    // Drafts

    fun startAdd() = openEditor(InventoryDraftIds.CREATE, targetItemId = null)

    fun startEdit(itemId: String) = openEditor(InventoryDraftIds.edit(itemId), targetItemId = itemId)

    fun continueDraft(draft: DraftUi) = openEditor(draft.draftId, draft.targetItemId)

    private fun openEditor(draftId: String, targetItemId: String?) {
        viewModelScope.launch {
            val fields = loadEditorFields(draftId, targetItemId)
            if (coordinator.isCurrent(scope)) setEditor(DraftEditorUi(draftId, targetItemId, fields))
        }
    }

    /** The saved draft if there is one, otherwise the item's current values, otherwise an empty form. */
    private suspend fun loadEditorFields(draftId: String, targetItemId: String?): DraftFields =
        repository.observeDrafts(scope).first().firstOrNull { it.draftId == draftId }?.fields
            ?: targetItemId?.let { id ->
                repository.observeCompleteInventory(scope).first()?.items?.firstOrNull { it.id == id }?.toUi(daysLeft = 0)?.toDraftFields()
            }
            ?: EMPTY_FIELDS

    /** Every change is saved on the device shortly after typing stops; nothing is sent. */
    fun onEditorChanged(fields: DraftFields) {
        val editor = _editor.value ?: return
        _editor.value = editor.copy(fields = fields)
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            repository.saveDraft(scope, editor.draftId, editor.targetItemId, fields)
        }
    }

    /** Closes the form; what was typed stays saved as a draft. */
    fun closeEditor() {
        val editor = _editor.value ?: return
        setEditor(null)
        if (draftSaveJob?.isActive == true) {
            draftSaveJob?.cancel()
            viewModelScope.launch { repository.saveDraft(scope, editor.draftId, editor.targetItemId, editor.fields) }
        }
    }

    fun discardDraft(draftId: String) {
        if (_editor.value?.draftId == draftId) {
            draftSaveJob?.cancel()
            setEditor(null)
        }
        viewModelScope.launch { repository.discardDraft(scope, draftId) }
    }

    /** Save from the open form: the draft is stored first, then sent once. */
    fun submitEditor() {
        val editor = _editor.value ?: return
        if (editor.draftId in busyKeys.value) return
        draftSaveJob?.cancel()
        setEditor(null)
        runWrite(editor.draftId) {
            repository.saveDraft(scope, editor.draftId, editor.targetItemId, editor.fields)
            val outcome = repository.submitDraft(scope, editor.draftId, online)
            draftMessage(outcome, created = editor.targetItemId == null)
        }
    }

    /** Explicit "Submit again" from a draft card. */
    fun submitDraft(draft: DraftUi) {
        runWrite(draft.draftId) {
            val outcome = repository.submitDraft(scope, draft.draftId, online)
            draftMessage(outcome, created = draft.targetItemId == null)
        }
    }

    fun consumeItem(itemId: String) {
        val row = uiState.value.snapshot?.sections?.rowById(itemId) ?: return
        runWrite(itemId) {
            consumeMessage(repository.consumeItem(scope, itemId, row.item.name, online), row.item.name)
        }
    }

    fun dismissConsumeNotice(itemId: String) {
        viewModelScope.launch { repository.dismissPendingConsume(scope, itemId) }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** One write per key at a time: a second tap while it runs is ignored. */
    private fun runWrite(key: String, block: suspend () -> String?) {
        if (key in busyKeys.value) return
        busyKeys.update { it + key }
        viewModelScope.launch {
            try {
                val text = block()
                if (coordinator.isCurrent(scope) && text != null) _message.value = text
            } finally {
                busyKeys.update { it - key }
            }
        }
    }

    private fun draftMessage(outcome: MutationOutcome, created: Boolean): String? {
        if (outcome == MutationOutcome.OutcomeUnknown) refreshAfterUnknown()
        return when (outcome) {
            is MutationOutcome.Confirmed -> {
                val done = if (created) "Item added." else "Item updated."
                if (outcome.listRefreshed) done else "$done The list could not be refreshed yet; tap Refresh to update it."
            }
            is MutationOutcome.NotSent ->
                if (outcome.offline) {
                    "You're offline. Not saved: your draft is kept on this device."
                } else {
                    "Not saved: CampusMeal could not be reached. Your draft is kept; try again when you are connected."
                }
            is MutationOutcome.Rejected -> rejectionMessage(outcome.httpCode, editing = !created)
            MutationOutcome.Unauthorized -> "Your session expired. Not saved: your draft is kept."
            MutationOutcome.OutcomeUnknown ->
                "We could not confirm whether this was saved. Your draft is kept. Check the refreshed list before submitting again."
            MutationOutcome.NeedsReconciliation ->
                "A previous attempt may already have been saved. Refresh your inventory and check it before submitting again."
            MutationOutcome.InvalidInput -> "Complete every field with a valid value before saving. Your draft is kept."
            MutationOutcome.AlreadyConsumed,
            MutationOutcome.AlreadyRunning,
            MutationOutcome.SessionEnded -> null
        }
    }

    private fun consumeMessage(outcome: MutationOutcome, itemName: String): String? {
        if (outcome == MutationOutcome.OutcomeUnknown) refreshAfterUnknown()
        return when (outcome) {
            is MutationOutcome.Confirmed ->
                if (outcome.listRefreshed) "$itemName marked as consumed." else "$itemName marked as consumed. Tap Refresh to update the list."
            MutationOutcome.AlreadyConsumed -> "$itemName was already consumed."
            is MutationOutcome.NotSent ->
                if (outcome.offline) "You're offline. $itemName was not marked as consumed." else "$itemName was not marked as consumed: CampusMeal could not be reached."
            is MutationOutcome.Rejected -> "$itemName was not marked as consumed (HTTP ${outcome.httpCode})."
            MutationOutcome.Unauthorized -> "Your session expired. $itemName was not marked as consumed."
            MutationOutcome.OutcomeUnknown ->
                "We could not confirm whether $itemName was consumed. Check the refreshed list before trying again."
            MutationOutcome.NeedsReconciliation ->
                "A previous attempt may already have consumed $itemName. Refresh your inventory first."
            MutationOutcome.InvalidInput,
            MutationOutcome.AlreadyRunning,
            MutationOutcome.SessionEnded -> null
        }
    }

    /** Reading the list is safe after an unknown outcome; the write itself is never resent. */
    private fun refreshAfterUnknown() {
        if (online) viewModelScope.launch { refreshJob?.join(); refresh(force = true) }
    }

    private fun rejectionMessage(httpCode: Int, editing: Boolean): String = when (httpCode) {
        400 -> "The server rejected these values. Check the fields and try again; your draft is kept."
        404 -> if (editing) "This item is no longer in your inventory. Your draft is kept so you can discard it." else "Not saved (HTTP 404)."
        429 -> "The server is busy. Not saved: try again in a moment."
        else -> "The change was rejected (HTTP $httpCode). Your draft is kept."
    }

    private fun setEditor(editor: DraftEditorUi?) {
        _editor.value = editor
        savedState[EDITOR_DRAFT_ID] = editor?.draftId
        savedState[EDITOR_TARGET_ID] = editor?.targetItemId
    }

    /** After process recreation the form reopens with its saved draft, loaded once Room answers. */
    private fun restoreEditor(): DraftEditorUi? {
        val draftId = savedState.get<String>(EDITOR_DRAFT_ID) ?: return null
        val targetItemId = savedState.get<String>(EDITOR_TARGET_ID)
        viewModelScope.launch {
            val fields = loadEditorFields(draftId, targetItemId)
            val current = _editor.value
            // Only fill the form if the user has not started typing in it meanwhile.
            if (current?.draftId == draftId && current.fields == EMPTY_FIELDS) _editor.value = current.copy(fields = fields)
        }
        return DraftEditorUi(draftId, targetItemId, EMPTY_FIELDS)
    }

    private fun LocalData.toUiState(today: String, refresh: RefreshUi, busy: Set<String>): InventoryUiState {
        val now = currentTimeMillis()
        val syncedAt = snapshot?.syncedAtEpochMillis
        val consumeNotices = consumes
            .filterNot { it.outcome == SubmitOutcome.SUBMITTING && it.itemId in busy }
            .map { marker -> marker.toNotice(snapshot) }
        val snapshotUi = snapshot?.let { snap ->
            val rows = snap.items.map { item ->
                val days = BogotaCalendar.daysBetween(today, item.expirationDate) ?: item.remainingDays
                val editDraftId = InventoryDraftIds.edit(item.id)
                val editDraft = drafts.firstOrNull { it.draftId == editDraftId }
                val notice = consumeNotices.firstOrNull { it.itemId == item.id }
                val itemBusy = item.id in busy || editDraftId in busy
                InventoryRowUi(
                    item = item.toUi(daysLeft = days),
                    hasEditDraft = editDraft != null,
                    busy = itemBusy,
                    consumeEnabled = !itemBusy && (notice == null || notice.status == ConsumeNoticeStatus.NOT_APPLIED),
                    editEnabled = !itemBusy && editDraft?.lastSubmitOutcome != SubmitOutcome.SUBMITTING,
                )
            }
            val freshness = Freshness.synced(snap.syncedAtEpochMillis, snap.invalidatedAtEpochMillis, cachePolicy.inventory, now)
            SnapshotUi(
                sections = rows.toSections(),
                syncedAtEpochMillis = snap.syncedAtEpochMillis,
                stale = freshness is Freshness.Stale,
                awaitingRefresh = snap.invalidatedAtEpochMillis != null,
                todayIsoDate = today,
            )
        }
        return InventoryUiState(
            loaded = loaded,
            snapshot = snapshotUi,
            refresh = refresh,
            drafts = drafts.map { it.toUi(snapshot, syncedAt, busy) },
            consumeNotices = consumeNotices,
        )
    }

    private fun InventoryDraft.toUi(snapshot: CompleteInventorySnapshot?, syncedAt: Long?, busy: Set<String>): DraftUi {
        val target = targetItemId?.let { id -> snapshot?.items?.firstOrNull { it.id == id } }
        val reconciled = syncedAt != null && syncedAt > (lastSubmitAtEpochMillis ?: Long.MIN_VALUE)
        val status = when {
            draftId in busy -> DraftStatus.SUBMITTING
            lastSubmitOutcome == SubmitOutcome.OUTCOME_UNKNOWN || lastSubmitOutcome == SubmitOutcome.SUBMITTING ->
                if (reconciled) DraftStatus.UNKNOWN_REVIEW else DraftStatus.UNKNOWN_NEEDS_SYNC
            lastSubmitOutcome == SubmitOutcome.NOT_SENT -> DraftStatus.NOT_SENT
            lastSubmitOutcome == SubmitOutcome.REJECTED -> DraftStatus.REJECTED
            else -> DraftStatus.LOCAL
        }
        val write = fields.toWriteOrNull()
        return DraftUi(
            draftId = draftId,
            targetItemId = targetItemId,
            title = when {
                targetItemId == null -> "New item" + fields.name.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                target != null -> "Edit ${target.name}"
                else -> "Edit " + fields.name.trim().ifEmpty { "item" }
            },
            fields = fields,
            savedAtEpochMillis = updatedAtEpochMillis,
            status = status,
            lastHttpCode = lastHttpCode,
            targetMissing = targetItemId != null && snapshot != null && target == null,
            targetMatches = target != null && write != null && target.name == write.name && target.quantity == write.quantity &&
                target.unit == write.unit && target.expirationDate == write.expirationDate,
        )
    }

    private fun PendingConsume.toNotice(snapshot: CompleteInventorySnapshot?): ConsumeNoticeUi {
        val reconciled = snapshot != null && snapshot.syncedAtEpochMillis > startedAtEpochMillis
        val status = when {
            !reconciled -> ConsumeNoticeStatus.CHECKING
            snapshot.items.none { it.id == itemId } -> ConsumeNoticeStatus.CONSUMED
            else -> ConsumeNoticeStatus.NOT_APPLIED
        }
        return ConsumeNoticeUi(itemId, itemName, status)
    }

    private fun InventoryRefreshResult.toProblem(): RefreshProblem? = when (this) {
        InventoryRefreshResult.Refreshed,
        InventoryRefreshResult.AlreadyCurrent,
        InventoryRefreshResult.SessionEnded -> null
        InventoryRefreshResult.Unauthorized -> RefreshProblem.SessionExpired
        is InventoryRefreshResult.Failed -> when (val error = error) {
            is InventoryError.BackendUnavailable ->
                if (error.httpCode == null && !online) RefreshProblem.Offline else RefreshProblem.ServerUnavailable(error.httpCode)
            is InventoryError.Http -> RefreshProblem.Refused(error.code)
            InventoryError.InvalidResponse -> RefreshProblem.InvalidResponse
        }
    }

    private data class LocalData(
        val snapshot: CompleteInventorySnapshot?,
        val drafts: List<InventoryDraft>,
        val consumes: List<PendingConsume>,
        val loaded: Boolean = true,
    )

    companion object {
        private const val DRAFT_SAVE_DEBOUNCE_MILLIS = 300L
        private const val MIDNIGHT_MARGIN_MILLIS = 1_000L
        private const val EDITOR_DRAFT_ID = "inventory.editor.draftId"
        private const val EDITOR_TARGET_ID = "inventory.editor.targetItemId"
        private val EMPTY_FIELDS = DraftFields(name = "", quantityText = "", unit = "", expirationDateText = "")

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InventoryViewModel(
                    repository = container.inventoryRepository,
                    coordinator = container.sessionCoordinator,
                    scope = scope,
                    cachePolicy = container.cachePolicy,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
