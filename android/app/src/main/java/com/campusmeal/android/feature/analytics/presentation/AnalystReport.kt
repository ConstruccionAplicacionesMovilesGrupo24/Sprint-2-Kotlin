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
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionRepository
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionResult
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionRowDto
import com.campusmeal.android.feature.analytics.data.ReportSource
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [result] null while loading. */
data class AnalystReportUiState(val month: String, val result: FeatureAdoptionResult? = null)

/**
 * BQ6 screen state (S3-11). Bound to one session [scope]. Changing the month cancels the request of
 * the previous month, and an answer that is no longer for the shown month is dropped.
 */
class AnalystReportViewModel(
    private val repository: FeatureAdoptionRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalystReportUiState(month = previousMonth(currentTimeMillis())))
    val uiState: StateFlow<AnalystReportUiState> = _uiState.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            repository.savedMonth(scope)?.let { saved -> _uiState.value = AnalystReportUiState(saved) }
            load(forceRefresh = false)
        }
    }

    fun changeMonth(delta: Int) {
        val month = shiftMonth(_uiState.value.month, delta)
        _uiState.value = AnalystReportUiState(month)
        viewModelScope.launch { repository.saveMonth(scope, month) }
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    /** Connectivity came back: refresh only what is not current from the network. */
    fun onReconnected() {
        val shown = _uiState.value.result
        if (shown !is FeatureAdoptionResult.Content || shown.source != ReportSource.NETWORK) load(forceRefresh = false)
    }

    private fun load(forceRefresh: Boolean) {
        loading?.cancel()
        val month = _uiState.value.month
        _uiState.value = AnalystReportUiState(month, result = null)
        loading = viewModelScope.launch {
            val result = repository.report(scope, month, forceRefresh)
            if (coordinator.isCurrent(scope) && _uiState.value.month == month) {
                _uiState.value = AnalystReportUiState(month, result)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { AnalystReportViewModel(container.featureAdoptionRepository, container.sessionCoordinator, scope) }
        }

        /** The month before the current one in Bogotá: the latest complete month. */
        fun previousMonth(nowEpochMillis: Long): String =
            shiftMonth(BogotaCalendar.dateOf(nowEpochMillis).substring(0, 7), -1)

        fun shiftMonth(month: String, delta: Int): String {
            val calendar = Calendar.getInstance(BogotaCalendar.ZONE, Locale.US).apply {
                clear()
                set(month.substring(0, 4).toInt(), month.substring(5, 7).toInt() - 1, 1)
                add(Calendar.MONTH, delta)
            }
            return String.format(Locale.US, "%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalystReportRoute(viewModel: AnalystReportViewModel, online: Boolean, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReconnectEffect(online, viewModel::onReconnected)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feature adoption (BQ6)") },
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
                "Core features used fewer than 2 times this month by at least 80% of monthly active users.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.changeMonth(-1) }) { Text("‹ Prev") }
                Text(state.month, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.changeMonth(1) }) { Text("Next ›") }
            }
            when (val result = state.result) {
                null -> CircularProgressIndicator()
                is FeatureAdoptionResult.Content -> ReportContent(result, onRefresh = viewModel::refresh)
                FeatureAdoptionResult.Forbidden -> Text("This report is only available to analyst accounts.")
                FeatureAdoptionResult.Unauthorized -> Text("Your session is no longer valid. Log in again.")
                is FeatureAdoptionResult.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (result.httpCode == null) {
                            "No saved report for ${state.month} on this device. Connect to load it."
                        } else {
                            "The report could not be loaded (HTTP ${result.httpCode})."
                        },
                    )
                    OutlinedButton(onClick = viewModel::refresh) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun ReportContent(content: FeatureAdoptionResult.Content, onRefresh: () -> Unit) {
    val report = content.report
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val syncedAt = formatSyncedAt(content.freshness.syncedAtEpochMillis)
        val origin = when {
            content.source == ReportSource.NETWORK -> "Updated $syncedAt"
            content.freshness is Freshness.Fresh -> "Saved $syncedAt"
            else -> "Offline · saved $syncedAt (may be outdated)"
        }
        Text(origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.campusMealColors.textSecondary)
        if (report.partialMonth) Text("Partial month: events are still arriving.", style = MaterialTheme.typography.bodySmall)
        if (report.includesSyntheticData) Text("Includes synthetic demo data.", style = MaterialTheme.typography.bodySmall)

        if (report.status == "NO_DATA") {
            Text("No monthly active users in ${report.month}.")
        } else {
            Text(
                "${report.monthlyActiveUsers} monthly active users · ${report.eventsReceived} events · " +
                    "flagged at ≥ ${(report.threshold * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            report.results.forEach { FeatureRow(it, report.monthlyActiveUsers) }
        }
        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun FeatureRow(row: FeatureAdoptionRowDto, mau: Int) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (row.flagged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.campusMealColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.feature, fontWeight = FontWeight.Bold)
                Text("${row.lowUseUsers} of $mau users with fewer than 2 uses", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${(row.lowUseRate * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                if (row.flagged) Text("Low adoption", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
