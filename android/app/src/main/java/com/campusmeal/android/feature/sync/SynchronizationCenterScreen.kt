package com.campusmeal.android.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.BuildConfig
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.outbox.OutboxItem
import com.campusmeal.android.core.outbox.OutboxKind
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.OutboxStatus
import com.campusmeal.android.core.telemetry.InjectedCrashException
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome

@Composable
fun SynchronizationCenterRoute(
    viewModel: SynchronizationCenterViewModel,
    online: Boolean,
    onOpenInventory: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SynchronizationCenterScreen(
        state = state,
        online = online,
        onRetry = viewModel::retry,
        onDismiss = viewModel::dismiss,
        onDeliver = viewModel::deliverPending,
        onOpenInventory = onOpenInventory,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynchronizationCenterScreen(
    state: SynchronizationCenterState,
    online: Boolean,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onDeliver: () -> Unit,
    onOpenInventory: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synchronization") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) } },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.loaded) {
                item { CircularProgressIndicator() }
                return@LazyColumn
            }
            item { Summary(state, online, onDeliver) }

            if (state.isEmpty) {
                item {
                    Text(
                        "Nothing is waiting on this device. What you do is listed here until it is delivered.",
                        color = MaterialTheme.campusMealColors.textSecondary,
                    )
                }
            }

            val attention = state.open.filter { it.status == OutboxStatus.FAILED || it.status == OutboxStatus.EXPIRED }
            val waiting = state.open - attention.toSet()

            inventoryWork(state.drafts, state.consumes, onOpenInventory)
            section("Needs attention", attention) { item ->
                EventCard(item) {
                    if (item.canRetry) OutlinedButton(onClick = { onRetry(item.clientEventId) }) { Text("Retry") }
                    TextButton(onClick = { onDismiss(item.clientEventId) }) { Text("Dismiss") }
                }
            }
            section("Waiting to be sent", waiting) { item -> EventCard(item) }
            section("Delivered recently", state.sent) { item -> EventCard(item) }

            // Never in a release or profile build.
            if (BuildConfig.DEBUG) item { DebugTools() }
        }
    }
}

@Composable
private fun Summary(state: SynchronizationCenterState, online: Boolean, onDeliver: () -> Unit) {
    val pending = (state.counts[OutboxStatus.PENDING] ?: 0) + (state.counts[OutboxStatus.SENDING] ?: 0)
    val attention = (state.counts[OutboxStatus.FAILED] ?: 0) + (state.counts[OutboxStatus.EXPIRED] ?: 0)
    Card {
        Text(
            when {
                pending == 0 && attention > 0 -> "Nothing is waiting to be sent, but some events could not be delivered."
                pending == 0 -> "Nothing is waiting to be sent."
                online -> "Pending events are sent automatically in the background."
                else -> "You are offline. Pending events are kept on this device and sent when you reconnect."
            },
        )
        Text(
            "$pending waiting · $attention need attention · ${state.drafts.size + state.consumes.size} to review",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
        // The lists are bounded; the counts above are not.
        if (pending + attention > state.open.size) {
            Text("Showing the latest ${state.open.size}.", style = MaterialTheme.typography.bodySmall)
        }
        if (pending > 0 && online) OutlinedButton(onClick = onDeliver) { Text("Send now") }
    }
}

private fun LazyListScope.inventoryWork(
    drafts: List<InventoryDraft>,
    consumes: List<PendingConsume>,
    onOpenInventory: () -> Unit,
) {
    if (drafts.isEmpty() && consumes.isEmpty()) return
    item { Header("Inventory work to review") }
    items(drafts, key = { "draft:${it.draftId}" }) { draft ->
        val name = draft.fields.name.trim().ifEmpty { "Item without a name" }
        Card {
            Text(if (draft.targetItemId == null) "New item: $name" else "Edit: $name", fontWeight = FontWeight.Bold)
            Text(draft.lastSubmitOutcome.draftLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Draft from ${formatSyncedAt(draft.updatedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            OutlinedButton(onClick = onOpenInventory) { Text("Review in Inventory") }
        }
    }
    items(consumes, key = { "consume:${it.itemId}" }) { consume ->
        Card {
            Text("Mark as consumed: ${consume.itemName}", fontWeight = FontWeight.Bold)
            Text(consume.outcome.consumeLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Started ${formatSyncedAt(consume.startedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            OutlinedButton(onClick = onOpenInventory) { Text("Review in Inventory") }
        }
    }
    item {
        Text(
            "Inventory changes are never sent automatically: sending one twice could change it twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

private fun LazyListScope.section(title: String, items: List<OutboxItem>, card: @Composable (OutboxItem) -> Unit) {
    if (items.isEmpty()) return
    item { Header(title) }
    items(items, key = { it.clientEventId }) { card(it) }
}

@Composable
private fun EventCard(item: OutboxItem, actions: (@Composable () -> Unit)? = null) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.kindLabel(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                item.statusLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = when (item.status) {
                    OutboxStatus.FAILED, OutboxStatus.EXPIRED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        Text("Happened ${formatSyncedAt(item.occurredAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
        Text(
            item.lastAttemptAtEpochMillis?.let { "Last attempt ${formatSyncedAt(it)} · ${item.attempts} attempt(s)" } ?: "Not attempted yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
        item.reason()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun DebugTools() {
    Card {
        Text("Debug build only", fontWeight = FontWeight.Bold)
        Text(
            "Ends the app with an injected fatal exception, marked so its session is never counted as real usage.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { throw InjectedCrashException() }) { Text("Inject a crash") }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.campusMealColors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

private fun OutboxItem.kindLabel(): String = when (kind) {
    OutboxKind.RECOMMENDATION -> "Recommendation seen or chosen"
    OutboxKind.USAGE -> "App usage"
    OutboxKind.REVIEW -> "Restaurant review"
    OutboxKind.PERFORMANCE -> "App performance"
    else -> "Other event"
}

private fun OutboxItem.statusLabel(): String = when (status) {
    OutboxStatus.PENDING -> "Pending"
    OutboxStatus.SENDING -> "Sending"
    OutboxStatus.SENT -> "Sent"
    OutboxStatus.FAILED -> "Not accepted"
    OutboxStatus.EXPIRED -> "Expired"
    else -> status
}

/** Never a server message: only a status code, or a reason written by this app. */
private fun OutboxItem.reason(): String? {
    val error = lastError
    return when {
        status == OutboxStatus.EXPIRED -> "It stayed on this device longer than the server accepts."
        status == OutboxStatus.SENT || error == null -> null
        error == "network error" -> "The last attempt had no connection."
        error == OutboxRepository.UNREADABLE_PAYLOAD -> "This version of the app cannot send it."
        error.startsWith("HTTP 401") -> "Waiting for a valid session."
        status == OutboxStatus.PENDING -> "The last attempt failed ($error). It will be tried again."
        kind == OutboxKind.REVIEW -> "The server refused it ($error). Open the restaurant to see why."
        else -> "The server refused it ($error)."
    }
}

private fun SubmitOutcome.draftLabel(): String = when (this) {
    SubmitOutcome.NONE -> "Saved on this device, never submitted."
    SubmitOutcome.NOT_SENT -> "It did not reach the server."
    SubmitOutcome.SUBMITTING -> "It is being submitted, or was interrupted."
    SubmitOutcome.REJECTED -> "The server refused it."
    SubmitOutcome.OUTCOME_UNKNOWN -> "We could not confirm whether it was saved."
}

private fun SubmitOutcome.consumeLabel(): String = when (this) {
    SubmitOutcome.OUTCOME_UNKNOWN -> "We could not confirm whether it was applied."
    SubmitOutcome.REJECTED -> "The server refused it."
    else -> "It is being submitted, or was interrupted."
}
