package com.campusmeal.android.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.SlowResponseNotice
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.inventory.presentation.InventoryItemUi
import com.campusmeal.android.feature.inventory.presentation.UrgencyBadge
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(14.dp)
private val PagePadding = 20.dp

/**
 * Home, following the CampusMeal design: greeting, the context in use, the live expiring items and
 * the two main actions. Values are real: the name comes from `/auth/me`, the context is the last one
 * the user validated (memory only) and the list is BQ2 from the backend.
 */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    context: ContextSummary?,
    online: Boolean,
    onChangeContext: () -> Unit,
    onCompare: () -> Unit,
    onFindRestaurants: () -> Unit,
    onOpenInventory: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Each return to the foreground re-checks the Bogota date and refreshes what is due.
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose { }
    }
    ReconnectEffect(online, viewModel::onReconnected)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state.userName) }
        item { ContextCard(context, onChangeContext) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Expiring soon", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenInventory) { Text("See all") }
            }
        }
        when (val expiring = state.expiring) {
            ExpiringState.Loading -> item {
                Column {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    SlowResponseNotice(active = true)
                }
            }
            ExpiringState.Unavailable -> item { Hint("Your inventory could not be loaded right now.") }
            is ExpiringState.Items -> {
                // remainingDays is valid as of the sync: always say when that was.
                item { Hint(expiringLabel(expiring)) }
                if (expiring.items.isEmpty()) item { Hint("No items expire in the next 3 days.") }
                items(expiring.items, key = { it.id }) { ExpiringCard(it) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onCompare, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(11.dp)) {
                    Text("Decide what to eat")
                }
                OutlinedButton(
                    onClick = onFindRestaurants,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text("Find restaurants")
                }
            }
        }
    }
}

@Composable
private fun Header(userName: String?) {
    val now = remember { Date() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CampusMeal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = greeting(now) + (userName?.let { ",\n${displayName(it)}" } ?: ""),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = SimpleDateFormat("EEEE, MMMM d 'at' HH:mm", Locale.US).format(now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

@Composable
private fun ContextCard(context: ContextSummary?, onChange: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your context right now", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onChange) { Text(if (context == null) "Set" else "Change") }
            }
            if (context == null) {
                Text(
                    "Choose your campus, available time and budget to get meal options.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            } else {
                ContextRow("Campus", context.place)
                ContextRow("Time available", "${context.availableMinutes} minutes")
                ContextRow("Budget", cop(context.maximumBudget))
            }
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.campusMealColors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ExpiringCard(item: InventoryItemUi) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.campusMealColors.surface,
        border = BorderStroke(1.dp, MaterialTheme.campusMealColors.border.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${item.quantityLabel} · expires ${item.expirationDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
            }
            UrgencyBadge(item)
        }
    }
}

private fun expiringLabel(expiring: ExpiringState.Items): String {
    val asOf = "As of ${formatSyncedAt(expiring.syncedAt.syncedAtEpochMillis)}"
    return when {
        expiring.historical -> "$asOf (an earlier day): days left were counted then and may have changed."
        expiring.refreshFailed -> "$asOf · could not refresh; showing the copy saved on this device."
        expiring.syncedAt is Freshness.Stale -> "$asOf · may be out of date."
        else -> asOf
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
}

private fun greeting(now: Date): String = when (Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** "Juan Pablo Bedoya" -> "Juan Pablo": the given names, as in the design. */
private fun displayName(name: String): String {
    val parts = name.trim().split(Regex("\\s+"))
    return if (parts.size >= 3) parts.take(2).joinToString(" ") else parts.first()
}

internal fun cop(value: Long): String = "$" + NumberFormat.getIntegerInstance(Locale.US).format(value)
