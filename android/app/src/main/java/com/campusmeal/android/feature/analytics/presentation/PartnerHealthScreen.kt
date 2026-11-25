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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.analytics.data.MenuStatus
import com.campusmeal.android.feature.analytics.data.PartnerHealthAssociationDto
import com.campusmeal.android.feature.analytics.data.PartnerHealthCohortDto
import com.campusmeal.android.feature.analytics.data.PartnerHealthRestaurantDto
import com.campusmeal.android.feature.analytics.data.PartnerHealthResult
import com.campusmeal.android.feature.analytics.data.ReportSource
import java.util.Locale

@Composable
fun PartnerHealthRoute(viewModel: PartnerHealthViewModel, online: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReconnectEffect(online, viewModel::onReconnected)
    PartnerHealthScreen(
        state = state,
        online = online,
        onBack = onBack,
        onApplyDates = viewModel::applyDates,
        onShift = viewModel::shift,
        onIncludeSynthetic = viewModel::setIncludeSynthetic,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerHealthScreen(
    state: PartnerHealthUiState,
    online: Boolean,
    onBack: () -> Unit,
    onApplyDates: (String, String) -> Unit,
    onShift: (Int) -> Unit,
    onIncludeSynthetic: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var from by remember(state.filters.from) { mutableStateOf(state.filters.from) }
    var to by remember(state.filters.to) { mutableStateOf(state.filters.to) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partner health (BQ13)") },
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
                "Which restaurant partners keep menus and prices updated within a seven-day window, and does that " +
                    "update frequency correlate with higher student selection rates and review scores?",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onShift(-1) }) { Text("‹ Earlier") }
                OutlinedButton(onClick = { onApplyDates(from, to) }) { Text("Apply") }
                TextButton(onClick = { onShift(1) }) { Text("Later ›") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include synthetic demo data")
                    Text(
                        "Labeled seed rows used to verify the calculation. Off = real data only.",
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
                is PartnerHealthResult.Content -> ReportContent(result, onRefresh)
                PartnerHealthResult.Forbidden -> Text("This report is only available to analyst accounts.")
                PartnerHealthResult.Unauthorized -> Text("Your session is no longer valid. Log in again.")
                is PartnerHealthResult.Rejected -> Text("The server refused this range. Use at most 92 days ending today or earlier.")
                is PartnerHealthResult.Unavailable -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ReportContent(content: PartnerHealthResult.Content, onRefresh: () -> Unit) {
    val report = content.report
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Text(
            "${short(report.from)} – ${short(report.to)} · menu fresh = revision in ${short(report.freshnessWindow.from)} – " +
                "${short(report.freshnessWindow.to)} (${report.freshnessWindow.days} days) · cells under " +
                "${report.minDistinctUsers} distinct users are hidden",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            report.revisionHistoryStartsAt?.let { "Menu history recorded since ${short(it.take(10))}; earlier updates are unknown." }
                ?: "No menu revision recorded yet: every restaurant is UNKNOWN, not \"never updated\".",
            style = MaterialTheme.typography.bodySmall,
        )
        if (report.includesSyntheticData) {
            Tag("Includes synthetic demo data", MaterialTheme.colorScheme.tertiaryContainer)
        }

        SectionTitle("By menu status")
        report.cohorts.forEach { CohortCard(it) }

        SectionTitle("Association (observational, not causal)")
        report.associations.forEach { AssociationRow(it) }

        SectionTitle("Restaurants")
        report.restaurants.forEach { RestaurantCard(it) }

        if (report.limitations.isNotEmpty()) {
            SectionTitle("Limitations")
            report.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun CohortCard(cohort: PartnerHealthCohortDto) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusTag(cohort.menuStatus)
            Text("  ${count(cohort.restaurants, "restaurant")}", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Selection rate ${percent(cohort.selectionRate)} (${cohort.selections} of ${cohort.exposures} exposures)" +
                excluded(cohort.restaurantsWithSuppressedBehavior),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Review mean ${one(cohort.meanOverall)} (${cohort.reviewCount} reviews)" + excluded(cohort.restaurantsWithSuppressedReviews),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AssociationRow(association: PartnerHealthAssociationDto) {
    Card {
        Text("${association.x} vs ${association.y}", fontWeight = FontWeight.SemiBold)
        Text(
            if (association.status == "COMPUTED" && association.coefficient != null) {
                "Spearman ρ = ${"%.2f".format(Locale.US, association.coefficient)} across ${count(association.sampleSize, "restaurant")}"
            } else {
                "Not enough data (${count(association.sampleSize, "restaurant")} with published values)" +
                    (association.reason?.let { ": $it" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RestaurantCard(restaurant: PartnerHealthRestaurantDto) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(restaurant.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusTag(restaurant.menuStatus)
        }
        Text(
            if (restaurant.menuStatus == MenuStatus.UNKNOWN) {
                "No menu revision recorded: update history unknown"
            } else {
                "Last revision ${restaurant.lastRevisionDate?.let(::short) ?: "—"} (${restaurant.daysSinceRevision?.let { count(it, "day") } ?: "—"} before the cutoff) · " +
                    "${restaurant.revisionsInRange} in range"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val behavior = restaurant.recommendations
        Text(
            when {
                behavior.suppressed -> "Recommendations: hidden (fewer than 5 users)"
                behavior.exposures == 0 -> "Recommendations: 0 exposures, no selection rate"
                else -> "Recommendations: ${behavior.selections} of ${behavior.exposures} exposures selected · ${percent(behavior.selectionRate)}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val reviews = restaurant.reviews
        Text(
            when {
                reviews.suppressed -> "Reviews: hidden (fewer than 5 reviewers)"
                reviews.reviewCount == 0 -> "Reviews: 0 in range"
                else -> "Reviews: ${reviews.reviewCount} · mean ${one(reviews.meanOverall)}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun StatusTag(status: String) = Tag(
    status,
    when (status) {
        MenuStatus.FRESH -> MaterialTheme.colorScheme.primaryContainer
        MenuStatus.STALE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    },
)

@Composable
private fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

private fun excluded(hidden: Int): String = if (hidden > 0) " · ${count(hidden, "hidden restaurant")} not counted" else ""

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun percent(value: Double?): String = value?.let { "%.1f%%".format(Locale.US, it * 100) } ?: "—"

private fun one(value: Double?): String = value?.let { "%.2f".format(Locale.US, it) } ?: "—"

private fun short(isoDate: String): String = BogotaCalendar.formatShortDate(isoDate)
