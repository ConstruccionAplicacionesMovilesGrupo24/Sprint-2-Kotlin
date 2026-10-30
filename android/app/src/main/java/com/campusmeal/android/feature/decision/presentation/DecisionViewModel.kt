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
import com.campusmeal.android.feature.decision.data.local.MealPlan
import com.campusmeal.android.feature.decision.data.local.MealPlanStore
import com.campusmeal.android.feature.decision.data.local.PlanIngredient
import com.campusmeal.android.feature.decision.data.local.RecentDecision
import com.campusmeal.android.feature.decision.data.local.RecentDecisionStore
import com.campusmeal.android.feature.decision.data.repository.EventDelivery
import com.campusmeal.android.feature.decision.data.repository.MealDecisionRepository
import com.campusmeal.android.feature.decision.data.repository.RecommendationEvent
import com.campusmeal.android.feature.decision.data.repository.RecommendationEventReporter
import com.campusmeal.android.feature.decision.domain.CompareMealOptionsUseCase
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult
import com.campusmeal.android.feature.decision.domain.model.MealOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DecisionUiState {
    data object Loading : DecisionUiState

    /** [comparedAtEpochMillis] is set for a current result: when the backend produced it. */
    data class Loaded(val result: MealDecisionResult, val comparedAtEpochMillis: Long? = null) : DecisionUiState
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
 *
 * A current recommendation, and the choice made on it, stay valid for [RECENT_WINDOW_MILLIS] for the
 * same context: coming back to the screen, or reopening the app after the process ended, shows them
 * again instead of creating a new recommendation. After that window, or when the user taps
 * "Recalculate", the backend is asked again.
 */
class DecisionViewModel(
    private val compareMealOptions: CompareMealOptionsUseCase,
    private val repository: MealDecisionRepository,
    private val eventReporter: RecommendationEventReporter,
    private val outbox: OutboxRepository,
    private val recentDecisions: RecentDecisionStore,
    private val mealPlans: MealPlanStore,
    private val coordinator: AccountSessionCoordinator,
    /** The session this screen belongs to: results of another session are never shown or reported. */
    private val scope: AccountSessionScope,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
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

    /**
     * The screen asks for its context every time it enters composition (coming back from another
     * tab included). The same context keeps a recent current recommendation and the choice made on
     * it; otherwise a recent one saved before the process ended is restored, or the backend is asked.
     */
    fun onContext(context: DecisionContext) {
        val loaded = _uiState.value as? DecisionUiState.Loaded
        if (context == lastContext) {
            if (compareJob?.isActive == true) return
            if (loaded != null && loaded.result.isCurrent() && isRecent(loaded.comparedAtEpochMillis)) return
        }
        launchComparison(context, allowRestore = true)
    }

    /** "Recalculate": always a new recommendation from the backend for the same context. */
    fun recalculate() {
        lastContext?.let { launchComparison(it, allowRestore = false) }
    }

    fun compare(context: DecisionContext) = launchComparison(context, allowRestore = false)

    fun retryCompare() {
        // A retry while a comparison is still loading would only duplicate the POST.
        if (compareJob?.isActive == true) return
        lastContext?.let(::compare)
    }

    private fun launchComparison(context: DecisionContext, allowRestore: Boolean) {
        lastContext = context
        _selection.value = SelectionState()
        pendingSelection = null
        selectionJob?.cancel()
        compareJob?.cancel()
        val request = ++compareRequest
        compareJob = viewModelScope.launch {
            _uiState.value = DecisionUiState.Loading
            if (allowRestore && restoreRecent(context, request)) return@launch
            val result = compareMealOptions(scope, context)
            // Cancellation is cooperative: also drop an answer that is no longer the latest request.
            if (request != compareRequest || !coordinator.isCurrent(scope)) return@launch
            val shown = when (result) {
                is MealDecisionResult.Content -> result.decision
                is MealDecisionResult.PartialContent -> result.decision
                else -> null
            }
            val comparedAt = if (shown != null) currentTimeMillis() else null
            _uiState.value = DecisionUiState.Loaded(result, comparedAt)
            // BQ6: one use per explicit comparison request (a recomposition never reaches here).
            outbox.enqueueUsage(scope, UsageFeature.MEAL_COMPARISON)
            if (shown != null && comparedAt != null) {
                RecentDecisionStore.contextKey(context)?.let { key ->
                    recentDecisions.write(scope, RecentDecision(shown.recommendationId, key, comparedAt))
                }
            }
            // Impression: once, when a recommendation with alternatives becomes visible.
            if (shown != null && impressionsSent.add(shown.recommendationId)) {
                eventReporter.record(scope, RecommendationEvent(shown.recommendationId, selectedAlternative = null))
            }
        }
    }

    /**
     * Shows the recommendation saved for this exact context if it is younger than the window, with
     * its choice. Its impression and selection were already recorded with stable ids, so nothing new
     * is sent. Returns false when there is nothing recent to restore.
     */
    private suspend fun restoreRecent(context: DecisionContext, request: Long): Boolean {
        val key = RecentDecisionStore.contextKey(context) ?: return false
        val recent = recentDecisions.read(scope)?.takeIf { it.contextKey == key && isRecent(it.comparedAtEpochMillis) }
            ?: return false
        val result = repository.restore(scope, recent.recommendationId) ?: return false
        if (request != compareRequest || !coordinator.isCurrent(scope)) return true
        impressionsSent.add(recent.recommendationId)
        _uiState.value = DecisionUiState.Loaded(result, recent.comparedAtEpochMillis)
        val alternative = recent.selectedAlternative
        val eventId = recent.selectionEventId
        val occurredAt = recent.selectionOccurredAtEpochMillis
        if (alternative != null && eventId != null && occurredAt != null) {
            val event = RecommendationEvent(recent.recommendationId, alternative, eventId, occurredAt)
            pendingSelection = event
            _selection.value = SelectionState(alternative, SelectionStatus.PENDING)
            observeSelection(event)
        }
        return true
    }

    fun select(recommendationId: String, option: MealOption) {
        // A previous (offline) comparison is not a current recommendation: it cannot be selected.
        val loaded = (_uiState.value as? DecisionUiState.Loaded) ?: return
        if (!loaded.result.isCurrent()) return
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
            rememberSelection(event, loaded.comparedAtEpochMillis)
            savePlan(event, option)
            collectDelivery(event)
        }
    }

    /** Keeps the choice next to its recommendation, so a reopening within the window shows it. */
    private suspend fun rememberSelection(event: RecommendationEvent, comparedAtEpochMillis: Long?) {
        val key = lastContext?.let(RecentDecisionStore::contextKey) ?: return
        val comparedAt = comparedAtEpochMillis ?: return
        recentDecisions.write(
            scope,
            RecentDecision(
                recommendationId = event.recommendationId,
                contextKey = key,
                comparedAtEpochMillis = comparedAt,
                selectedAlternative = event.selectedAlternative,
                selectionEventId = event.clientEventId,
                selectionOccurredAtEpochMillis = event.occurredAtEpochMillis,
            ),
        )
    }

    /** Home shows the chosen alternative for the rest of the available lunch time. */
    private suspend fun savePlan(event: RecommendationEvent, option: MealOption) {
        val minutes = lastContext?.availableMinutes ?: return
        val base = MealPlan(
            recommendationId = event.recommendationId,
            alternative = option.wireName(),
            chosenAtEpochMillis = event.occurredAtEpochMillis,
            availableMinutes = minutes,
        )
        val plan = when (option) {
            is MealOption.Cook -> base.copy(ingredients = option.expiringIngredients.map { PlanIngredient(it.itemId, it.name) })
            is MealOption.Walk -> base.copy(restaurantId = option.restaurant.id, restaurantName = option.restaurant.name)
            is MealOption.Order -> base.copy(restaurantId = option.restaurant.id, restaurantName = option.restaurant.name)
        }
        mealPlans.write(scope, plan)
    }

    private fun observeSelection(event: RecommendationEvent) {
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch { collectDelivery(event) }
    }

    private suspend fun collectDelivery(event: RecommendationEvent) {
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

    private fun isRecent(comparedAtEpochMillis: Long?): Boolean =
        comparedAtEpochMillis != null && currentTimeMillis() - comparedAtEpochMillis < RECENT_WINDOW_MILLIS

    private fun MealDecisionResult.isCurrent() =
        this is MealDecisionResult.Content || this is MealDecisionResult.PartialContent

    companion object {
        /** How long a current recommendation is shown again instead of asking the backend. */
        val RECENT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5)

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DecisionViewModel(
                    container.compareMealOptions,
                    container.mealDecisionRepository,
                    container.recommendationEventReporter,
                    container.outboxRepository,
                    container.recentDecisionStore,
                    container.mealPlanStore,
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
