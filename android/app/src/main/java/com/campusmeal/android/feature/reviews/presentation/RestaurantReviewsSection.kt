package com.campusmeal.android.feature.reviews.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.analytics.data.ReportSource
import com.campusmeal.android.feature.reviews.data.RestaurantReviewsResponseDto
import com.campusmeal.android.feature.reviews.data.ReviewRepository
import com.campusmeal.android.feature.reviews.data.ReviewSubmission
import com.campusmeal.android.feature.reviews.data.ReviewSummaryResult
import com.campusmeal.android.feature.reviews.data.SubmissionState
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [result] null before the first answer. */
data class RestaurantReviewsUiState(val result: ReviewSummaryResult? = null, val loading: Boolean = true)

/**
 * Public review summary on the restaurant detail (S4-04). A new request cancels the previous one; answers of
 * another session are dropped. When one of the account's reviews is confirmed, the cached summary is
 * invalidated and reloaded here, without counting pending local reviews in the server aggregate.
 */
class RestaurantReviewsViewModel(
    private val repository: ReviewRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val restaurantId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(RestaurantReviewsUiState())
    val state: StateFlow<RestaurantReviewsUiState> = _state.asStateFlow()

    val submissions: StateFlow<List<ReviewSubmission>> =
        repository.observeSubmissions(scope, restaurantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            var first = true
            repository.observeSummaryInvalidation(scope, restaurantId).collect { invalidatedAt ->
                // First value: initial load. Later: only a new invalidation (a confirmed review) reloads.
                if (first || invalidatedAt != null) load(forceRefresh = false)
                first = false
            }
        }
    }

    fun refresh() = load(forceRefresh = true)

    fun onReconnected() {
        val shown = _state.value.result
        if (shown !is ReviewSummaryResult.Content || shown.source != ReportSource.NETWORK) load(forceRefresh = false)
    }

    private fun load(forceRefresh: Boolean) {
        loading?.cancel()
        _state.value = _state.value.copy(loading = true)
        loading = viewModelScope.launch {
            val result = repository.summary(scope, restaurantId, forceRefresh)
            if (coordinator.isCurrent(scope)) _state.value = RestaurantReviewsUiState(result, loading = false)
        }
    }

    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope, restaurantId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { RestaurantReviewsViewModel(container.reviewRepository, container.sessionCoordinator, scope, restaurantId) }
            }
    }
}

@Composable
fun RestaurantReviewsSection(
    viewModel: RestaurantReviewsViewModel,
    online: Boolean,
    onWriteReview: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    ReconnectEffect(online, viewModel::onReconnected)

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Student reviews", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            when (val result = state.result) {
                null -> Unit
                is ReviewSummaryResult.Content -> SummaryContent(result)
                ReviewSummaryResult.Unauthorized -> Text("Your session is no longer valid. Log in again to see reviews.")
                is ReviewSummaryResult.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (result.httpCode == null) {
                            "No saved reviews for this restaurant on this device. Connect to load them."
                        } else {
                            "Reviews could not be loaded (HTTP ${result.httpCode})."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = viewModel::refresh) { Text("Try again") }
                }
            }

            // Own reviews the server has not confirmed: shown apart, never inside the summary above.
            submissions.filter { it.state != SubmissionState.SENT }.forEach { own ->
                Text(
                    "Your review of ${BogotaCalendar.formatShortDate(own.reviewDate)}: ${own.describe().first.lowercase(Locale.US)}. " +
                        "It is not part of the summary until the server confirms it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }
            OutlinedButton(onClick = onWriteReview, modifier = Modifier.fillMaxWidth()) { Text("Write a review") }
        }
    }
}

@Composable
private fun SummaryContent(content: ReviewSummaryResult.Content) {
    val response: RestaurantReviewsResponseDto = content.response
    val summary = response.summary
    val syncedAt = formatSyncedAt(content.freshness.syncedAtEpochMillis)
    Text(
        when {
            content.source == ReportSource.NETWORK -> "Updated $syncedAt"
            content.freshness is Freshness.Fresh -> "Saved $syncedAt"
            else -> "Saved $syncedAt · may be outdated"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.campusMealColors.textSecondary,
    )
    when {
        summary.suppressed -> Text(
            "Fewer than 5 students have reviewed this restaurant, so scores stay hidden to protect their privacy.",
            style = MaterialTheme.typography.bodyMedium,
        )
        summary.reviewCount == 0 -> Text("No student reviews yet. Be the first.", style = MaterialTheme.typography.bodyMedium)
        else -> {
            Text(
                "${one(summary.meanOverall)} overall · ${summary.reviewCount} reviews from ${summary.distinctReviewers} students",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Portion ${one(summary.meanPortion)} · Taste ${one(summary.meanTaste)} · Freshness ${one(summary.meanFreshness)} · " +
                    "Value ${one(summary.meanValue)} · Wait ${summary.meanWaitMinutes?.let { "%.0f min".format(Locale.US, it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
            response.recent.take(3).forEach { review ->
                Text(
                    "${BogotaCalendar.formatShortDate(review.reviewDate)} · ${review.overall}/5 overall · ${review.waitMinutes} min wait",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }
        }
    }
    response.catalogRating?.let { rating ->
        Text(
            "Catalog rating ${one(rating.value)} · from the restaurant catalog, not from these reviews",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

private fun one(value: Double?): String = value?.let { "%.1f".format(Locale.US, it) } ?: "—"
