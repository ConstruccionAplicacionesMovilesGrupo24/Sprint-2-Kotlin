package com.campusmeal.android.feature.reviews.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.feature.reviews.data.ReviewScores
import com.campusmeal.android.feature.reviews.data.ReviewSubmission
import com.campusmeal.android.feature.reviews.data.SubmissionState

@Composable
fun ReviewMealRoute(
    viewModel: ReviewMealViewModel,
    restaurantName: String,
    online: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val submissions by viewModel.submissions.collectAsStateWithLifecycle()
    ReviewMealScreen(
        restaurantName = restaurantName,
        state = state,
        submissions = submissions,
        online = online,
        onBack = onBack,
        onScore = viewModel::setScore,
        onWaitMinutes = viewModel::setWaitMinutes,
        onSubmit = viewModel::submit,
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewMealScreen(
    restaurantName: String,
    state: ReviewFormState,
    submissions: List<ReviewSubmission>,
    online: Boolean,
    onBack: () -> Unit,
    onScore: (ReviewAspect, Int) -> Unit,
    onWaitMinutes: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review $restaurantName", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Rate today's visit. Reviews are self-reported, anonymous in public summaries, and limited to one " +
                    "per restaurant per day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            if (!online) {
                Notice("You are offline. Your review is kept on this device and sent when the connection returns.")
            }
            if (!state.loaded) {
                CircularProgressIndicator()
                return@Column
            }

            ReviewAspect.entries.forEach { aspect ->
                ScoreRow(aspect, state.scores[aspect], enabled = !state.submitting) { onScore(aspect, it) }
            }

            OutlinedTextField(
                value = state.waitText,
                onValueChange = onWaitMinutes,
                enabled = !state.submitting,
                label = { Text("Waiting time (minutes)") },
                supportingText = { Text("From ordering to receiving your food, 0–${ReviewScores.MAX_WAIT_MINUTES}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!state.isEmpty && !state.submitting) {
                Text("Draft saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.campusMealColors.textSecondary)
            }
            state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Notice(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSubmit, enabled = !state.submitting) {
                    Text(if (state.submitting) "Saving…" else "Submit review")
                }
                TextButton(onClick = onClear, enabled = !state.submitting && !state.isEmpty) { Text("Clear") }
            }

            if (submissions.isNotEmpty()) {
                Text("Your reviews of this restaurant", style = MaterialTheme.typography.titleMedium)
                submissions.forEach { SubmissionRow(it) }
            }
        }
    }
}

@Composable
private fun ScoreRow(aspect: ReviewAspect, selected: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(aspect.label, fontWeight = FontWeight.SemiBold)
            Text(aspect.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.campusMealColors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { score ->
                FilterChip(
                    selected = selected == score,
                    onClick = { onSelect(score) },
                    enabled = enabled,
                    label = { Text("$score") },
                )
            }
        }
    }
}

@Composable
private fun SubmissionRow(submission: ReviewSubmission) {
    val (label, detail) = submission.describe()
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${BogotaCalendar.formatShortDate(submission.reviewDate)} · overall ${submission.scores.overall}/5 · " +
                    "${submission.scores.waitMinutes} min wait",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                label,
                color = if (submission.state == SubmissionState.REJECTED || submission.state == SubmissionState.NOT_DELIVERED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelLarge,
            )
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.campusMealColors.textSecondary) }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Label and optional explanation of a submission's delivery state. */
internal fun ReviewSubmission.describe(): Pair<String, String?> = when (state) {
    SubmissionState.QUEUED -> "Queued" to "Saved on this device; it will be sent when there is a connection."
    SubmissionState.SENDING -> "Sending…" to null
    SubmissionState.UNCONFIRMED ->
        "Waiting for confirmation" to "It was sent but no answer arrived. It is sent again with the same id, so it is stored only once."
    SubmissionState.SENT -> "Sent ✓" to "The server stored it. Public summaries include it once at least 5 students reviewed this restaurant."
    SubmissionState.REJECTED -> "Rejected" to rejectionMessage(rejectionReason)
    SubmissionState.NOT_DELIVERED -> "Could not be delivered" to "It stayed unsent too long and was removed from the delivery queue."
}

private fun rejectionMessage(reason: String?): String = when {
    reason == null -> "The server refused it."
    "REVIEW_ALREADY_SUBMITTED_TODAY" in reason -> "You had already reviewed this restaurant that day (maybe from another device)."
    "CLIENT_REVIEW_ID_CONFLICT" in reason -> "The server already holds a different review with this id."
    "RESTAURANT_NOT_FOUND" in reason || reason.startsWith("HTTP 404") -> "The restaurant is no longer available."
    reason.startsWith("HTTP 400") -> "The server refused the values, or the review is older than 30 days."
    reason == "unreadable payload" -> "This version of the app cannot send it."
    else -> "The server refused it ($reason)."
}
