package com.campusmeal.android.feature.analytics.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.analytics.data.ReportSource
import com.campusmeal.android.feature.analytics.data.ZoneDemandResult

@Composable
fun ZoneDemandReportRoute(
    viewModel: ZoneDemandReportViewModel,
    online: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ReconnectEffect(
        online = online,
        onReconnect = viewModel::onReconnected,
    )

    ZoneDemandReportScreen(
        state = state,
        onBack = onBack,
        onChangeZone = viewModel::changeZone,
        onApplyDates = viewModel::applyDates,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneDemandReportScreen(
    state: ZoneDemandUiState,
    onBack: () -> Unit,
    onChangeZone: (String) -> Unit,
    onApplyDates: (String, String) -> Unit,
    onRefresh: () -> Unit,
) {
    var from by remember(state.filters.from) {
        mutableStateOf(state.filters.from)
    }

    var to by remember(state.filters.to) {
        mutableStateOf(state.filters.to)
    }

    val zones =
        listOf(
            "bogota-centro",
            "bogota-chapinero",
            "bogota-teusaquillo",
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zone demand (BQ9)") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("‹")
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Demand indicators based on searches, restaurant detail openings " +
                    "and selections. These are interest indicators, not verified purchases.",
            )

            Text(
                "Zone",
                fontWeight = FontWeight.Bold,
            )

            zones.forEach { zone ->
                TextButton(
                    onClick = {
                        onChangeZone(zone)
                    },
                ) {
                    Text(
                        if (state.filters.zone == zone) {
                            "✓ $zone"
                        } else {
                            zone
                        },
                    )
                }
            }

            OutlinedTextField(
                value = from,
                onValueChange = { from = it },
                label = {
                    Text("From (YYYY-MM-DD)")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = {
                    Text("To (YYYY-MM-DD)")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = {
                    onApplyDates(from, to)
                },
            ) {
                Text("Apply dates")
            }

            state.validationError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.loading) {
                CircularProgressIndicator()
            }

            when (val result = state.result) {
                null -> Unit

                ZoneDemandResult.Forbidden -> {
                    Text(
                        "This report is only available to analyst accounts.",
                    )
                }

                ZoneDemandResult.Unauthorized -> {
                    Text(
                        "Your session is no longer valid.",
                    )
                }

                is ZoneDemandResult.Unavailable -> {
                    Text(
                        if (result.httpCode == null) {
                            "No saved report is available for these filters."
                        } else {
                            "Report unavailable (HTTP ${result.httpCode})."
                        },
                    )
                }

                is ZoneDemandResult.Content -> {
                    ZoneDemandContent(
                        content = result,
                        onRefresh = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneDemandContent(
    content: ZoneDemandResult.Content,
    onRefresh: () -> Unit,
) {
    val report = content.report

    val zone =
        report.zones.firstOrNull {
            it.zone == content.selectedZone
        }

    val syncedAt =
        formatSyncedAt(
            content.freshness.syncedAtEpochMillis,
        )

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            when {
                content.source == ReportSource.NETWORK ->
                    "Updated $syncedAt"

                content.freshness is Freshness.Fresh ->
                    "Saved $syncedAt"

                else ->
                    "Offline · saved $syncedAt (may be outdated)"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            "${report.from} → ${report.to} · ${report.timezone}",
            style = MaterialTheme.typography.bodySmall,
        )

        if (report.containsSyntheticData) {
            Text(
                "Includes synthetic demo data.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (report.suppressedCells > 0) {
            Text(
                "${report.suppressedCells} cells hidden because fewer than " +
                    "${report.minDistinctUsersPerCell} distinct users contributed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            zone == null -> {
                Text(
                    "No data is available for ${content.selectedZone}.",
                )
            }

            zone.totals.suppressed -> {
                Text(
                    "Totals are hidden because the sample is too small. " +
                        "Missing values do not mean zero demand.",
                )
            }

            else -> {
                Text(
                    content.selectedZone,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                MetricRow(
                    label = "Searches",
                    value = zone.totals.searches,
                )

                MetricRow(
                    label = "Detail openings",
                    value = zone.totals.detailOpenings,
                )

                MetricRow(
                    label = "Selections",
                    value = zone.totals.selections,
                )

                MetricRow(
                    label = "Distinct users",
                    value = zone.totals.distinctUsers,
                )
            }
        }

        OutlinedButton(
            onClick = onRefresh,
        ) {
            Text("Refresh")
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = value?.toString() ?: "Suppressed",
            fontWeight = FontWeight.Bold,
        )
    }
}