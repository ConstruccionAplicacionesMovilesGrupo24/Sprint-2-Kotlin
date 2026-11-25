package com.campusmeal.android.feature.reviews.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.reviews.data.ReviewDraftEntity
import com.campusmeal.android.feature.reviews.data.ReviewRepository
import com.campusmeal.android.feature.reviews.data.ReviewScores
import com.campusmeal.android.feature.reviews.data.ReviewSubmission
import com.campusmeal.android.feature.reviews.data.SubmitResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ReviewAspect(val label: String, val hint: String) {
    OVERALL("Overall", "Your visit as a whole"),
    PORTION("Portion", "Was it enough food?"),
    TASTE("Taste", "How good did it taste?"),
    FRESHNESS("Freshness", "Did it feel freshly prepared?"),
    VALUE("Value", "Worth its price?"),
}

data class ReviewFormState(
    /** False until the saved draft was read; input is disabled meanwhile so it is not overwritten. */
    val loaded: Boolean = false,
    val scores: Map<ReviewAspect, Int> = emptyMap(),
    val waitText: String = "",
    val submitting: Boolean = false,
    val validationError: String? = null,
    /** Shown after an admission: the form is empty again. */
    val notice: String? = null,
) {
    val isEmpty: Boolean get() = scores.isEmpty() && waitText.isBlank()
}

/**
 * ReviewMealScreen state (S4-04). Bound to one session [scope] and one restaurant.
 *
 * Every edit is written to the Room draft, so the form survives process death. Draft writes and the
 * submission share one [Mutex], so they run in order and a late draft write can never resurrect a submitted
 * form. The admission runs in [NonCancellable]: leaving the screen does not abandon it halfway.
 */
class ReviewMealViewModel(
    private val repository: ReviewRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val restaurantId: String,
    private val restaurantName: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewFormState())
    val state: StateFlow<ReviewFormState> = _state.asStateFlow()

    /** The account's own reviews of this restaurant with their delivery state, while the screen is visible. */
    val submissions: StateFlow<List<ReviewSubmission>> =
        repository.observeSubmissions(scope, restaurantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val writes = Mutex()

    init {
        viewModelScope.launch {
            val draft = writes.withLock { repository.draft(scope, restaurantId) }
            _state.value = ReviewFormState(
                loaded = true,
                scores = draft?.let { d ->
                    buildMap {
                        d.overall?.let { put(ReviewAspect.OVERALL, it) }
                        d.portion?.let { put(ReviewAspect.PORTION, it) }
                        d.taste?.let { put(ReviewAspect.TASTE, it) }
                        d.freshness?.let { put(ReviewAspect.FRESHNESS, it) }
                        d.value?.let { put(ReviewAspect.VALUE, it) }
                    }
                }.orEmpty(),
                waitText = draft?.waitMinutesText.orEmpty(),
            )
        }
    }

    fun setScore(aspect: ReviewAspect, score: Int) {
        if (!_state.value.loaded || _state.value.submitting || score !in 1..5) return
        _state.update { it.copy(scores = it.scores + (aspect to score), validationError = null, notice = null) }
        persistDraft()
    }

    fun setWaitMinutes(text: String) {
        if (!_state.value.loaded || _state.value.submitting) return
        // Digits only, at most 3: the field cannot hold anything the draft could not restore.
        val digits = text.filter(Char::isDigit).take(3)
        _state.update { it.copy(waitText = digits, validationError = null, notice = null) }
        persistDraft()
    }

    fun clear() {
        if (!_state.value.loaded || _state.value.submitting) return
        _state.value = ReviewFormState(loaded = true)
        persistDraft()
    }

    fun submit() {
        val form = _state.value
        if (!form.loaded || form.submitting) return
        val scores = form.toScores()
        if (scores == null) {
            _state.update {
                it.copy(validationError = "Rate all five aspects from 1 to 5 and enter a wait between 0 and ${ReviewScores.MAX_WAIT_MINUTES} minutes.")
            }
            return
        }
        _state.update { it.copy(submitting = true, validationError = null, notice = null) }
        viewModelScope.launch {
            val result = withContext(NonCancellable) {
                writes.withLock { repository.submit(scope, restaurantId, restaurantName, scores) }
            }
            if (!coordinator.isCurrent(scope)) return@launch
            _state.update { current ->
                when (result) {
                    is SubmitResult.Admitted -> ReviewFormState(
                        loaded = true,
                        notice = "Review saved on this device. It is sent automatically, also after a restart or without connection.",
                    )
                    SubmitResult.AlreadyReviewedToday -> current.copy(
                        submitting = false,
                        validationError = "You already reviewed this restaurant today. You can review it again tomorrow.",
                    )
                    SubmitResult.InvalidScores -> current.copy(submitting = false, validationError = "Check the scores and the waiting time.")
                    is SubmitResult.NotStored -> current.copy(
                        submitting = false,
                        validationError = if (result.reason == "outbox full") {
                            "Too many items are waiting to be sent. Connect to the internet and try again."
                        } else {
                            "Your session changed. The review was not saved."
                        },
                    )
                }
            }
        }
    }

    private fun persistDraft() {
        val form = _state.value
        viewModelScope.launch {
            writes.withLock {
                // A submission may have run between the edit and this write: never write a stale form.
                if (_state.value != form) return@withLock
                if (form.isEmpty) {
                    repository.discardDraft(scope, restaurantId)
                } else {
                    repository.saveDraft(
                        scope,
                        ReviewDraftEntity(
                            accountId = scope.accountId,
                            restaurantId = restaurantId,
                            restaurantName = restaurantName,
                            overall = form.scores[ReviewAspect.OVERALL],
                            portion = form.scores[ReviewAspect.PORTION],
                            taste = form.scores[ReviewAspect.TASTE],
                            freshness = form.scores[ReviewAspect.FRESHNESS],
                            value = form.scores[ReviewAspect.VALUE],
                            waitMinutesText = form.waitText,
                            updatedAtEpochMillis = 0L,
                        ),
                    )
                }
            }
        }
    }

    private fun ReviewFormState.toScores(): ReviewScores? {
        val wait = waitText.toIntOrNull() ?: return null
        return ReviewScores(
            overall = scores[ReviewAspect.OVERALL] ?: return null,
            portion = scores[ReviewAspect.PORTION] ?: return null,
            taste = scores[ReviewAspect.TASTE] ?: return null,
            freshness = scores[ReviewAspect.FRESHNESS] ?: return null,
            value = scores[ReviewAspect.VALUE] ?: return null,
            waitMinutes = wait,
        ).takeIf { it.isValid() }
    }

    companion object {
        fun factory(
            container: AppContainer,
            scope: AccountSessionScope,
            restaurantId: String,
            restaurantName: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReviewMealViewModel(container.reviewRepository, container.sessionCoordinator, scope, restaurantId, restaurantName)
            }
        }
    }
}
