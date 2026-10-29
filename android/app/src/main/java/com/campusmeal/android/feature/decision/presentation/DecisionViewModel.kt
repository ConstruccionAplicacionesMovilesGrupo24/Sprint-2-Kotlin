package com.campusmeal.android.feature.decision.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.UsageFeature
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.decision.data.repository.EventDelivery
import com.campusmeal.android.feature.decision.data.repository.RecommendationEvent
import com.campusmeal.android.feature.decision.data.repository.RecommendationEventReporter
import com.campusmeal.android.feature.decision.domain.CompareMealOptionsUseCase
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult
import com.campusmeal.android.feature.decision.domain.model.MealOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DecisionUiState {
    data object Loading : DecisionUiState
    data class Loaded(val result: MealDecisionResult) : DecisionUiState
}

/**
 * Where the selection event is: nothing chosen, saved on this device and waiting for the network
 * (PENDING), accepted by the backend (SENT), or rejected by it / not stored (FAILED).
 */
enum class SelectionStatus { NONE, PENDING, SENT, FAILED }

data class SelectionState(val alternative: String? = null, val status: SelectionStatus = SelectionStatus.NONE)

/**
 * BQ5 comparison plus its BQ8 events. The backend's order, scores and recommendation are shown as
 * received. One impression is recorded per displayed recommendation, and one selection per
 * recommendation. Events go to the durable outbox (S3-09) and are delivered in the background, so a
 * selection made offline is kept and sent later; a rejected one is retried with the same event.
 */
class DecisionViewModel(
    private val compareMealOptions: CompareMealOptionsUseCase,
    private val eventReporter: RecommendationEventReporter,
    private val outbox: OutboxRepository,
    private val coordinator: AccountSessionCoordinator,
    /** The session this screen belongs to: results of another session are never shown or reported. */
    private val scope: AccountSessionScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DecisionUiState>(DecisionUiState.Loading)
    val uiState: StateFlow<DecisionUiState> = _uiState.asStateFlow()

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    private var lastContext: DecisionContext? = null
    private val impressionsSent = mutableSetOf<String>()
    private var pendingSelection: RecommendationEvent? = null
    private var selectionJob: Job? = null

    /** The comparison in flight. A new request cancels it, so a late answer never replaces a newer one. */
    private var compareJob: Job? = null
    private var compareRequest = 0L

    fun compare(context: DecisionContext) {
        lastContext = context
        _selection.value = SelectionState()
        pendingSelection = null
        selectionJob?.cancel()
        compareJob?.cancel()
        val request = ++compareRequest
        compareJob = viewModelScope.launch {
            _uiState.value = DecisionUiState.Loading
            val result = compareMealOptions(scope, context)
            // Cancellation is cooperative: also drop an answer that is no longer the latest request.
            if (request != compareRequest || !coordinator.isCurrent(scope)) return@launch
            _uiState.value = DecisionUiState.Loaded(result)
            // BQ6: one use per explicit comparison request (a recomposition never reaches here).
            outbox.enqueueUsage(scope, UsageFeature.MEAL_COMPARISON)
            val shown = when (result) {
                is MealDecisionResult.Content -> result.decision
                is MealDecisionResult.PartialContent -> result.decision
                else -> null
            }
            // Impression: once, when a recommendation with alternatives becomes visible.
            if (shown != null && impressionsSent.add(shown.recommendationId)) {
                eventReporter.record(scope, RecommendationEvent(shown.recommendationId, selectedAlternative = null))
            }
        }
    }

    fun retryCompare() {
        // A retry while a comparison is still loading would only duplicate the POST.
        if (compareJob?.isActive == true) return
        lastContext?.let(::compare)
    }

    fun select(recommendationId: String, option: MealOption) {
        // A previous (offline) comparison is not a current recommendation: it cannot be selected.
        val loaded = (_uiState.value as? DecisionUiState.Loaded)?.result
        if (loaded !is MealDecisionResult.Content && loaded !is MealDecisionResult.PartialContent) return
        val current = _selection.value
        if (current.status == SelectionStatus.PENDING || current.status == SelectionStatus.SENT) return
        val event = pendingSelection ?: RecommendationEvent(recommendationId, selectedAlternative = option.wireName())
        pendingSelection = event
        _selection.value = SelectionState(event.selectedAlternative, SelectionStatus.PENDING)
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            // Saved locally first; the same event id is reused if the user retries a rejected one.
            if (!eventReporter.record(scope, event)) {
                if (coordinator.isCurrent(scope)) _selection.value = SelectionState(event.selectedAlternative, SelectionStatus.FAILED)
                return@launch
            }
            eventReporter.observe(event.clientEventId).collect { delivery ->
                if (!coordinator.isCurrent(scope)) return@collect
                _selection.value = SelectionState(
                    event.selectedAlternative,
                    when (delivery) {
                        EventDelivery.PENDING -> SelectionStatus.PENDING
                        EventDelivery.SENT -> SelectionStatus.SENT
                        EventDelivery.FAILED -> SelectionStatus.FAILED
                    },
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DecisionViewModel(
                    container.compareMealOptions,
                    container.recommendationEventReporter,
                    container.outboxRepository,
                    container.sessionCoordinator,
                    scope,
                )
            }
        }
    }
}

fun MealOption.wireName(): String = when (this) {
    is MealOption.Cook -> "COOK"
    is MealOption.Walk -> "WALK"
    is MealOption.Order -> "ORDER"
}
