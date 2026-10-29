package com.campusmeal.android.feature.decision.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealAlternative
import com.campusmeal.android.feature.decision.domain.model.MealDecision
import com.campusmeal.android.feature.decision.domain.model.MealDecisionError
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult
import com.campusmeal.android.feature.decision.domain.model.MealOption
import java.text.NumberFormat
import java.util.Locale

/**
 * Minimal BQ5 screen: Cook / Walk / Order exactly as ranked by the backend. Android never re-sorts
 * or re-scores; it shows rank, score, time, cost, ingredients, restaurant and explanation as received.
 */
@Composable
fun DecisionRoute(
    viewModel: DecisionViewModel,
    context: DecisionContext?,
    onBack: () -> Unit,
    onSetContext: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    LaunchedEffect(context) {
        if (context != null) viewModel.compare(context)
    }

    DecisionScreen(
        state = if (context == null) null else state,
        selection = selection,
        onBack = onBack,
        onRetry = viewModel::retryCompare,
        onSelect = viewModel::select,
        onSetContext = onSetContext,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecisionScreen(
    state: DecisionUiState?,
    selection: SelectionState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String, MealOption) -> Unit,
    onSetContext: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cook · Walk · Order") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                null -> Message(
                    "Set your context first",
                    "Tell us your campus, available time and budget to compare Cook, Walk and Order.",
                    onSetContext,
                    actionLabel = "Set context",
                )
                DecisionUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is DecisionUiState.Loaded -> when (val result = state.result) {
                    is MealDecisionResult.Content -> DecisionContent(result.decision, 0, selection, onSelect)
                    is MealDecisionResult.PartialContent ->
                        DecisionContent(result.decision, result.skippedAlternatives, selection, onSelect)
                    is MealDecisionResult.Previous -> DecisionContent(
                        decision = result.decision,
                        skipped = result.skippedAlternatives,
                        selection = SelectionState(),
                        onSelect = { _, _ -> },
                        previous = result,
                        onRetry = onRetry,
                    )
                    is MealDecisionResult.NoAvailableAlternatives ->
                        Message("No alternatives available", result.mainExplanation, onRetry)
                    MealDecisionResult.Unauthorized ->
                        Message("Session expired", "Sign in again to compare options.", null)
                    is MealDecisionResult.Failure -> Message(
                        "Comparison unavailable",
                        when (val error = result.error) {
                            is MealDecisionError.BackendUnavailable -> "The CampusMeal service could not be reached."
                            is MealDecisionError.Http -> "The request was rejected (HTTP ${error.code})."
                            MealDecisionError.InvalidResponse -> "The service answered with unexpected data."
                            MealDecisionError.InvalidContext -> "Available time must be greater than zero."
                        },
                        onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun DecisionContent(
    decision: MealDecision,
    skipped: Int,
    selection: SelectionState,
    onSelect: (String, MealOption) -> Unit,
    previous: MealDecisionResult.Previous? = null,
    onRetry: () -> Unit = {},
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (previous != null) {
            item {
                ElevatedCard(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Previous comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "From ${formatSyncedAt(previous.comparedAtEpochMillis)} for ${previous.availableMinutes} min · " +
                                "${cop(previous.maximumBudget)}. It is not a current recommendation and cannot be chosen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onRetry) { Text("Compare again") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(decision.mainExplanation, style = MaterialTheme.typography.titleMedium)
                decision.supportingReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (skipped > 0) Text("$skipped alternative(s) could not be shown.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(decision.alternatives, key = { it.rank }) { alternative ->
            AlternativeCard(alternative, selection, selectable = previous == null) { onSelect(decision.recommendationId, alternative.option) }
        }
        if (previous == null) {
            item { SelectionFooter(selection, retry = { decision.alternatives.firstOrNull()?.let { onSelect(decision.recommendationId, it.option) } }) }
        }
    }
}

@Composable
private fun AlternativeCard(alternative: MealAlternative, selection: SelectionState, selectable: Boolean = true, onChoose: () -> Unit) {
    val name = alternative.option.wireName()
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        colors = if (alternative.recommended) {
            CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.elevatedCardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("#${alternative.rank} $name", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("score ${alternative.score.display()}", style = MaterialTheme.typography.bodyMedium)
            }
            if (alternative.recommended) Text("Recommended", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("${alternative.estimatedMinutes} min · ${cop(alternative.estimatedCost)}")
            when (val option = alternative.option) {
                is MealOption.Cook -> option.expiringIngredients.forEach {
                    Text("Uses ${it.name} (${daysLabel(it.remainingDays)})", style = MaterialTheme.typography.bodySmall)
                }
                is MealOption.Walk -> Text("${option.restaurant.name} · ${option.walkingMinutes} min walk", style = MaterialTheme.typography.bodySmall)
                is MealOption.Order -> Text(
                    "${option.restaurant.name} · delivery ${option.deliveryMinutes} min · fee ${cop(option.deliveryFee)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val chosen = selection.alternative == name
            if (selectable && selection.status == SelectionStatus.NONE) {
                Button(onClick = onChoose) { Text("Choose $name") }
            } else if (chosen) {
                Text("Your choice", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SelectionFooter(selection: SelectionState, retry: () -> Unit) {
    when (selection.status) {
        SelectionStatus.NONE -> Unit
        SelectionStatus.PENDING -> Text("Choice saved on this device. It will sync when you're online.")
        SelectionStatus.SENT -> Text("Choice saved.")
        SelectionStatus.FAILED -> Column {
            Text("Your choice could not be sent.")
            OutlinedButton(onClick = retry) { Text("Retry") }
        }
    }
}

@Composable
private fun Message(title: String, message: String, onRetry: (() -> Unit)?, actionLabel: String = "Try again") {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text(actionLabel) }
    }
}

private fun Double.display(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun cop(value: Long): String = "$" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO")).format(value) + " COP"

private fun daysLabel(days: Int): String = when (days) {
    0 -> "expires today"
    1 -> "expires tomorrow"
    else -> "expires in $days days"
}
