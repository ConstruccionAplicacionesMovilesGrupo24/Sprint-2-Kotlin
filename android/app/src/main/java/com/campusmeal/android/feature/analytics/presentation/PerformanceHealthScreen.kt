package com.campusmeal.android.feature.analytics.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.analytics.data.PerformanceCombinationDto
import com.campusmeal.android.feature.analytics.data.PerformanceCoverageDto
import com.campusmeal.android.feature.analytics.data.PerformanceHealthResult
import com.campusmeal.android.feature.analytics.data.PerformanceSamplePolicyDto
import com.campusmeal.android.feature.analytics.data.ReportSource
import java.util.Locale

@Composable
fun PerformanceHealthRoute(viewModel: PerformanceHealthViewModel, online: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReconnectEffect(online, viewModel::onReconnected)
    PerformanceHealthScreen(
        state = state,
        online = online,
        onBack = onBack,
        onApplyDates = viewModel::applyDates,
        onIncludeSynthetic = viewModel::setIncludeSynthetic,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceHealthScreen(
    state: PerformanceHealthUiState,
    online: Boolean,
    onBack: () -> Unit,
    onApplyDates: (String, String) -> Unit,
    onIncludeSynthetic: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var from by remember(state.filters.from) { mutableStateOf(state.filters.from) }
    var to by remember(state.filters.to) { mutableStateOf(state.filters.to) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App performance (BQ1)") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Which device model, operating-system version, and application version combinations have a crash rate " +
                    "above 1% or a 95th-percentile loading time above two seconds on the meal-comparison screen?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )

            if (state.accessRemoved) {
                Text("This report is only available to analyst accounts. Saved copies were removed from this device.")
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = { onApplyDates(from, to) }) { Text("Apply") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include demo data")
                    Text(
                        "Labeled seed rows and crashes injected on purpose. Off = real usage only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.campusMealColors.textSecondary,
                    )
                }
                Switch(checked = state.filters.includeSynthetic, onCheckedChange = onIncludeSynthetic)
            }
            state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loading) CircularProgressIndicator()

            when (val result = state.result) {
                null -> Unit
                is PerformanceHealthResult.Content -> ReportContent(result, onRefresh)
                PerformanceHealthResult.Forbidden -> Unit
                PerformanceHealthResult.Unauthorized -> Text("Your session is no longer valid. Log in again.")
                PerformanceHealthResult.Rejected -> Text("The server refused this range. Use at most 92 days ending today or earlier.")
                is PerformanceHealthResult.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            result.httpCode != null -> "The report could not be loaded (HTTP ${result.httpCode})."
                            !online -> "You are offline and no saved report exists for these filters. Connect to load it."
                            else -> "The report could not be loaded. Check the connection and try again."
                        },
                    )
                    OutlinedButton(onClick = onRefresh) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun ReportContent(content: PerformanceHealthResult.Content, onRefresh: () -> Unit) {
    val report = content.report
    val syncedAt = formatSyncedAt(content.freshness.syncedAtEpochMillis)
    Text(
        when {
            content.source == ReportSource.NETWORK -> "Updated $syncedAt"
            content.freshness is Freshness.Fresh -> "Saved $syncedAt"
            else -> "Offline · saved $syncedAt (may be outdated)"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.campusMealColors.textSecondary,
    )
    if (report.includesSyntheticData) {
        Text("Includes demo data: it does not describe real usage.", color = MaterialTheme.colorScheme.error)
    }
    Text(
        "Flagged strictly above ${percent(report.thresholds.crashRate)} crash rate or ${report.thresholds.p95Ms} ms p95.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (report.combinations.isEmpty()) {
        Text("No sessions or comparison loads were recorded in this range.")
    } else {
        report.combinations.forEach { CombinationCard(it, report.samplePolicy) }
    }
    Coverage(report.coverage)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How to read it", fontWeight = FontWeight.Bold)
        report.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
}

@Composable
private fun CombinationCard(item: PerformanceCombinationDto, policy: PerformanceSamplePolicyDto) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (item.flagged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.campusMealColors.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.deviceModel, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (item.flagged) Text("Flagged", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${item.osVersion} · app ${item.appVersion} · ${item.platform}" + if (item.synthetic) " · demo data" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )

            val sessions = item.sessions
            Metric(
                label = "Crash rate",
                value = sessions.crashRate?.let(::percent) ?: "No data",
                flagged = sessions.flagged,
                detail = "${sessions.crashed} of ${sessions.started} sessions crashed" +
                    smallSample(sessions.confidence, "${policy.minSessions} sessions"),
            )
            val loading = item.loading
            Metric(
                label = "Comparison p95",
                value = loading.p95Ms?.let { "$it ms" } ?: "No data",
                flagged = loading.flagged,
                detail = "${loading.freshNetwork} successful network loads" +
                    smallSample(loading.confidence, "${policy.minFreshLoads} loads"),
            )
            Text(
                "Not in the p95: ${loading.cachedRestore} restored · ${loading.offlineHistory} offline history · " +
                    "${loading.errors} errors · ${loading.timeouts} timeouts · ${loading.cancelled} cancelled" +
                    (loading.failureRate?.let { " · ${percent(it)} failed" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, flagged: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (flagged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            // In words too: a card can be flagged by only one of its metrics.
            if (flagged) Text("above limit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Coverage(coverage: PerformanceCoverageDto) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Coverage", fontWeight = FontWeight.Bold)
        Text(
            "${coverage.events} records" +
                (if (coverage.platforms.isEmpty()) "" else " (" + coverage.platforms.joinToString { "${it.platform} ${it.events}" } + ")") +
                " · ${coverage.lateEvents} arrived more than a day later",
            style = MaterialTheme.typography.bodySmall,
        )
        if (coverage.crashesWithoutSession > 0) {
            Text("${coverage.crashesWithoutSession} crash(es) without a recorded session, not in the rate.", style = MaterialTheme.typography.bodySmall)
        }
        if (coverage.excludedInjectedSessions > 0) {
            Text("${coverage.excludedInjectedSessions} session(s) with an injected crash left out.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Offline devices may still deliver until ${coverage.lateSyncPossibleUntil}.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun smallSample(confidence: String, minimum: String): String =
    if (confidence == "INSUFFICIENT") " · small sample (under $minimum): not a reliable rate" else ""

private fun percent(rate: Double): String = String.format(Locale.US, "%.2f%%", rate * 100)
