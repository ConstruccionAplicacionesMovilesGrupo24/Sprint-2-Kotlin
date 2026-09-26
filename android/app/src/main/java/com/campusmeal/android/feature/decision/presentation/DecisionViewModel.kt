package com.campusmeal.android.feature.decision.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.decision.data.repository.RecommendationEvent
import com.campusmeal.android.feature.decision.data.repository.RecommendationEventReporter
import com.campusmeal.android.feature.decision.domain.CompareMealOptionsUseCase
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult
import com.campusmeal.android.feature.decision.domain.model.MealOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DecisionUiState {
    data object Loading : DecisionUiState
    data class Loaded(val result: MealDecisionResult) : DecisionUiState
}

/** Where the selection event is: nothing chosen, sending, accepted by the backend, or not sent. */
enum class SelectionStatus { NONE, SENDING, SENT, FAILED }

data class SelectionState(val alternative: String? = null, val status: SelectionStatus = SelectionStatus.NONE)

/**
 * BQ5 comparison plus its BQ8 events. The backend's order, scores and recommendation are shown as
 * received. One impression is sent per displayed recommendation, and one selection per
 * recommendation; a failed selection is retried with the same event (same `clientEventId`).
 */
class DecisionViewModel(
    private val compareMealOptions: CompareMealOptionsUseCase,
    private val eventReporter: RecommendationEventReporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DecisionUiState>(DecisionUiState.Loading)
    val uiState: StateFlow<DecisionUiState> = _uiState.asStateFlow()

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()

    private var lastContext: DecisionContext? = null
    private val impressionsSent = mutableSetOf<String>()
    private var pendingSelection: RecommendationEvent? = null

    fun compare(context: DecisionContext) {
        lastContext = context
        _selection.value = SelectionState()
        pendingSelection = null
        viewModelScope.launch {
            _uiState.value = DecisionUiState.Loading
            val result = compareMealOptions(context)
            _uiState.value = DecisionUiState.Loaded(result)
            val shown = when (result) {
                is MealDecisionResult.Content -> result.decision
                is MealDecisionResult.PartialContent -> result.decision
                else -> null
            }
            // Impression: once, when a recommendation with alternatives becomes visible.
            if (shown != null && impressionsSent.add(shown.recommendationId)) {
                eventReporter.report(RecommendationEvent(shown.recommendationId, selectedAlternative = null))
            }
        }
    }

    fun retryCompare() {
        lastContext?.let(::compare)
    }

    fun select(recommendationId: String, option: MealOption) {
        val current = _selection.value
        if (current.status == SelectionStatus.SENDING || current.status == SelectionStatus.SENT) return
        val event = pendingSelection ?: RecommendationEvent(recommendationId, selectedAlternative = option.wireName())
        pendingSelection = event
        _selection.value = SelectionState(event.selectedAlternative, SelectionStatus.SENDING)
        viewModelScope.launch {
            val accepted = eventReporter.report(event)
            _selection.value = SelectionState(event.selectedAlternative, if (accepted) SelectionStatus.SENT else SelectionStatus.FAILED)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DecisionViewModel(container.compareMealOptions, container.recommendationEventReporter) }
        }
    }
}

fun MealOption.wireName(): String = when (this) {
    is MealOption.Cook -> "COOK"
    is MealOption.Walk -> "WALK"
    is MealOption.Order -> "ORDER"
}
