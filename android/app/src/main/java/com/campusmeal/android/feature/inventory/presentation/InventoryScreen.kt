package com.campusmeal.android.feature.inventory.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.formatSyncedAt
import com.campusmeal.android.core.designsystem.CampusMealTheme
import com.campusmeal.android.core.designsystem.SlowResponseNotice
import com.campusmeal.android.core.designsystem.campusMealColors
import com.campusmeal.android.core.network.ReconnectEffect
import com.campusmeal.android.feature.inventory.domain.model.DraftFields

private val PagePadding = 22.dp
private val CardShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(11.dp)
private val ButtonHeight = 52.dp

/** Callbacks of the Inventory screen, grouped so previews and the route pass them in one place. */
data class InventoryActions(
    val onRefresh: () -> Unit = {},
    val onCancelRefresh: () -> Unit = {},
    val onAddItem: () -> Unit = {},
    val onEditItem: (String) -> Unit = {},
    val onConsumeItem: (String) -> Unit = {},
    val onContinueDraft: (DraftUi) -> Unit = {},
    val onSubmitDraft: (DraftUi) -> Unit = {},
    val onDiscardDraft: (String) -> Unit = {},
    val onDismissConsumeNotice: (String) -> Unit = {},
)

@Composable
fun InventoryRoute(
    viewModel: InventoryViewModel,
    online: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()

    LaunchedEffect(online) { viewModel.setOnline(online) }
    // Each return to the foreground re-checks the Bogotá date and refreshes a snapshot that is due.
    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStarted()
        onStopOrDispose { }
    }
    ReconnectEffect(online, viewModel::onReconnected)

    InventoryScreen(
        state = state,
        actions = InventoryActions(
            onRefresh = viewModel::refreshNow,
            onCancelRefresh = viewModel::cancelRefresh,
            onAddItem = viewModel::startAdd,
            onEditItem = viewModel::startEdit,
            onConsumeItem = viewModel::consumeItem,
            onContinueDraft = viewModel::continueDraft,
            onSubmitDraft = viewModel::submitDraft,
            onDiscardDraft = viewModel::discardDraft,
            onDismissConsumeNotice = viewModel::dismissConsumeNotice,
        ),
    )

    editor?.let { current ->
        InventoryDraftDialog(
            editor = current,
            onChange = viewModel::onEditorChanged,
            onSave = viewModel::submitEditor,
            onClose = viewModel::closeEditor,
            onDiscard = { viewModel.discardDraft(current.draftId) },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            text = { Text(text) },
        )
    }
}

/**
 * Add/edit form. Every change is saved on the device as a draft (shortly after typing stops) and
 * survives navigation and process recreation. Only Save sends it; Close keeps it; Discard deletes it.
 */
@Composable
private fun InventoryDraftDialog(
    editor: DraftEditorUi,
    onChange: (DraftFields) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onDiscard: () -> Unit,
) {
    val fields = editor.fields
    val valid = fields.toWriteOrNull() != null

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (editor.targetItemId == null) "Add item" else "Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fields.name,
                    onValueChange = { onChange(fields.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = fields.quantityText,
                    onValueChange = { onChange(fields.copy(quantityText = it)) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = fields.unit,
                    onValueChange = { onChange(fields.copy(unit = it)) },
                    label = { Text("Unit") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = fields.expirationDateText,
                    onValueChange = { onChange(fields.copy(expirationDateText = it)) },
                    label = { Text("Expiration (YYYY-MM-DD)") },
                    singleLine = true,
                )
                Text(
                    text = "Draft saved on this device. It is sent only when you tap Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
                TextButton(onClick = onDiscard) { Text("Discard draft") }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    state: InventoryUiState,
    actions: InventoryActions,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { InventoryTopBar() },
        bottomBar = { AddItemBottomBar(onAddItem = actions.onAddItem) },
    ) { innerPadding ->
        if (!state.loaded) {
            InventoryLoadingScreen(Modifier.padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = PagePadding, end = PagePadding, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SyncRow(state, onRefresh = actions.onRefresh) }

            if (state.refresh.running) {
                item { RefreshingRow(onCancel = actions.onCancelRefresh) }
            }

            state.refresh.problem?.let { problem ->
                item { ProblemBanner(problem, hasSnapshot = state.snapshot != null, onRetry = actions.onRefresh) }
            }

            items(state.consumeNotices, key = { "consume:${it.itemId}" }) { notice ->
                ConsumeNoticeCard(notice, onRefresh = actions.onRefresh, onDismiss = { actions.onDismissConsumeNotice(notice.itemId) })
            }

            items(state.drafts, key = { "draft:${it.draftId}" }) { draft ->
                DraftCard(draft, actions)
            }

            val snapshot = state.snapshot
            when {
                snapshot == null && state.refresh.running -> item { FirstLoadBlock() }
                snapshot == null -> item { NoLocalDataBlock(problem = state.refresh.problem, onRetry = actions.onRefresh) }
                snapshot.sections.isEmpty -> item { EmptyInventoryBlock() }
                else -> inventorySections(snapshot.sections, actions)
            }
        }
    }
}

private fun LazyListScope.inventorySections(sections: InventorySections, actions: InventoryActions) {
    section("Expired", sections.expired, actions)
    section("Due today", sections.today, actions)
    section("Next 3 days", sections.soon, actions)
    section("Later", sections.later, actions)
}

private fun LazyListScope.section(title: String, rows: List<InventoryRowUi>, actions: InventoryActions) {
    if (rows.isEmpty()) return
    item(key = "header:$title") { InventorySectionHeader(title = title, itemCount = rows.size) }
    items(rows, key = { it.item.id }) { row ->
        InventoryItemCard(
            row = row,
            onConsume = { actions.onConsumeItem(row.item.id) },
            onEdit = { actions.onEditItem(row.item.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryTopBar() {
    TopAppBar(
        title = {
            Text(
                text = "Inventory",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.campusMealColors.textPrimary,
            )
        },
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Source and age of what is shown. A stored empty inventory gets the same label as any other snapshot. */
@Composable
private fun SyncRow(state: InventoryUiState, onRefresh: () -> Unit) {
    val snapshot = state.snapshot
    val label = when {
        snapshot == null -> "Not synced on this device yet"
        state.refresh.problem != null -> "Saved copy · updated ${formatSyncedAt(snapshot.syncedAtEpochMillis)}"
        snapshot.awaitingRefresh -> "Updated ${formatSyncedAt(snapshot.syncedAtEpochMillis)} · your last change is not shown yet"
        snapshot.stale -> "Updated ${formatSyncedAt(snapshot.syncedAtEpochMillis)} · may be out of date"
        else -> "Updated ${formatSyncedAt(snapshot.syncedAtEpochMillis)}"
    }
    val warn = snapshot == null || state.refresh.problem != null || snapshot.stale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.campusMealColors.warningForeground else MaterialTheme.campusMealColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh, enabled = !state.refresh.running) {
            Text("Refresh", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RefreshingRow(onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Refreshing from CampusMeal…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        SlowResponseNotice(active = true)
    }
}

@Composable
private fun ProblemBanner(problem: RefreshProblem, hasSnapshot: Boolean, onRetry: () -> Unit) {
    val cause = problem.describe()
    NoticeCard(
        title = if (hasSnapshot) "Couldn't refresh" else "Couldn't load your inventory",
        message = if (hasSnapshot) "$cause Showing the copy saved on this device." else cause,
        actionLabel = "Try again",
        onAction = onRetry,
    )
}

private fun RefreshProblem.describe(): String = when (this) {
    RefreshProblem.Offline -> "You're offline."
    is RefreshProblem.ServerUnavailable ->
        if (httpCode == null) "CampusMeal could not be reached." else "CampusMeal is temporarily unavailable (HTTP $httpCode)."
    is RefreshProblem.Refused -> "The server refused the request (HTTP $httpCode)."
    RefreshProblem.InvalidResponse -> "The server sent an unexpected response."
    RefreshProblem.SessionExpired -> "Your session expired. Sign in again."
}

@Composable
private fun ConsumeNoticeCard(notice: ConsumeNoticeUi, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    when (notice.status) {
        ConsumeNoticeStatus.CHECKING -> NoticeCard(
            title = "Checking ${notice.itemName}",
            message = "We could not confirm whether it was consumed. Refresh your inventory to find out before trying again.",
            actionLabel = "Refresh",
            onAction = onRefresh,
        )
        ConsumeNoticeStatus.CONSUMED -> NoticeCard(
            title = "${notice.itemName} was consumed",
            message = "The refreshed inventory no longer includes it.",
            actionLabel = "OK",
            onAction = onDismiss,
        )
        ConsumeNoticeStatus.NOT_APPLIED -> NoticeCard(
            title = "${notice.itemName} was not consumed",
            message = "The refreshed inventory still includes it. You can mark it as consumed again.",
            actionLabel = "OK",
            onAction = onDismiss,
        )
    }
}

@Composable
private fun DraftCard(draft: DraftUi, actions: InventoryActions) {
    val status = when (draft.status) {
        DraftStatus.LOCAL -> "Not submitted yet."
        DraftStatus.NOT_SENT -> "Not saved: CampusMeal could not be reached when you tried."
        DraftStatus.REJECTED -> "Not saved: the server rejected it" + (draft.lastHttpCode?.let { " (HTTP $it)." } ?: ".")
        DraftStatus.SUBMITTING -> "Sending…"
        DraftStatus.UNKNOWN_NEEDS_SYNC ->
            "We could not confirm whether this was saved. Refresh your inventory before trying again."
        DraftStatus.UNKNOWN_REVIEW -> when {
            draft.targetMissing -> "We could not confirm whether this was saved. This item is no longer in your inventory."
            draft.targetMatches -> "We could not confirm the save, but the item already shows these values. Discard the draft if it looks right."
            draft.targetItemId == null ->
                "We could not confirm whether this was saved. Check the refreshed list below and submit again only if the item is missing."
            else -> "We could not confirm whether this was saved. Compare it with the refreshed item before submitting again."
        }
    }
    val canSubmit = draft.status in setOf(DraftStatus.LOCAL, DraftStatus.NOT_SENT, DraftStatus.REJECTED, DraftStatus.UNKNOWN_REVIEW) &&
        !draft.targetMissing && draft.fields.toWriteOrNull() != null
    val sending = draft.status == DraftStatus.SUBMITTING

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(draft.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.campusMealColors.textPrimary)
            Text(
                text = "Draft saved on this device ${formatSyncedAt(draft.savedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
            if (sending) SlowResponseNotice(active = true)
            if (!sending) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { actions.onContinueDraft(draft) }) { Text("Continue") }
                    if (draft.status == DraftStatus.UNKNOWN_NEEDS_SYNC) {
                        TextButton(onClick = actions.onRefresh) { Text("Refresh") }
                    } else if (canSubmit) {
                        TextButton(onClick = { actions.onSubmitDraft(draft) }) {
                            Text(if (draft.status == DraftStatus.LOCAL) "Submit" else "Submit again")
                        }
                    }
                    TextButton(onClick = { actions.onDiscardDraft(draft.draftId) }) { Text("Discard") }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, message: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.campusMealColors.textPrimary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.campusMealColors.textSecondary)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun InventorySectionHeader(
    title: String,
    itemCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.campusMealColors.textPrimary,
        )
        Text(
            text = if (itemCount == 1) "1 item" else "$itemCount items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

@Composable
private fun InventoryItemCard(
    row: InventoryRowUi,
    onConsume: () -> Unit,
    onEdit: () -> Unit,
) {
    val item = row.item
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.campusMealColors.textPrimary,
                    )
                    Text(
                        text = "${item.quantityLabel} · Expires ${item.expirationDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.campusMealColors.textSecondary,
                    )
                    if (row.hasEditDraft) {
                        Text(
                            text = "Unsaved edit draft",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.campusMealColors.warningForeground,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                UrgencyBadge(item)
            }

            if (row.busy) {
                Text(
                    text = "Sending…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.campusMealColors.textSecondary,
                )
                SlowResponseNotice(active = true)
            }

            // Consume/Edit are intentionally kept even though the Figma cards do not show them yet.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onConsume,
                    enabled = row.consumeEnabled,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(text = "Consume", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onEdit,
                    enabled = row.editEnabled,
                    shape = ButtonShape,
                    border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(text = if (row.hasEditDraft) "Continue edit" else "Edit", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Shared with Home's "Expiring soon" list. */
@Composable
internal fun UrgencyBadge(
    item: InventoryItemUi,
) {
    val semanticColors = MaterialTheme.campusMealColors

    val background: Color
    val foreground: Color
    val label: String

    when (item.urgency) {
        InventoryUrgency.EXPIRED -> {
            background = semanticColors.urgentBackground
            foreground = semanticColors.urgentForeground
            label = when (item.daysLeft) {
                -1 -> "Expired yesterday"
                else -> "Expired ${-item.daysLeft} days ago"
            }
        }

        InventoryUrgency.TODAY -> {
            background = semanticColors.urgentBackground
            foreground = semanticColors.urgentForeground
            label = "Due today"
        }

        InventoryUrgency.SOON -> {
            background = semanticColors.warningBackground
            foreground = semanticColors.warningForeground
            label = if (item.daysLeft == 1) "In 1 day" else "In ${item.daysLeft} days"
        }

        InventoryUrgency.LATER -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            foreground = semanticColors.textSecondary
            label = "In ${item.daysLeft} days"
        }
    }

    Surface(
        color = background,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            UrgencyMark(urgency = item.urgency, color = foreground)
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = foreground)
        }
    }
}

@Composable
private fun UrgencyMark(
    urgency: InventoryUrgency,
    color: Color,
) {
    when (urgency) {
        InventoryUrgency.EXPIRED,
        InventoryUrgency.TODAY -> {
            Canvas(modifier = Modifier.size(9.dp)) { drawWarningTriangle(color) }
        }

        InventoryUrgency.SOON -> {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }

        InventoryUrgency.LATER -> {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = color, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawWarningTriangle(
    color: Color,
) {
    val path = Path().apply {
        moveTo(size.width / 2f, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path = path, color = color)
}

@Composable
private fun AddItemBottomBar(
    onAddItem: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = onAddItem,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp)
                .height(ButtonHeight),
            shape = ButtonShape,
        ) {
            Text(text = "Add item", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A synchronized empty snapshot: a valid answer, labelled with its age by [SyncRow]. */
@Composable
private fun EmptyInventoryBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyInventoryIllustration()
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Your inventory is empty",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.campusMealColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Add what you have at home and CampusMeal can suggest recipes before anything expires.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** No snapshot was ever synchronized here: not the same as an empty inventory. */
@Composable
private fun NoLocalDataBlock(problem: RefreshProblem?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Your inventory is not available on this device yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.campusMealColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = (problem?.describe()?.plus(" ") ?: "") + "It has not been synchronized here, so nothing can be shown offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetry, shape = ButtonShape) { Text("Try again") }
    }
}

@Composable
private fun FirstLoadBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = "Loading your inventory…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

@Composable
private fun EmptyInventoryIllustration() {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val strokeColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(42.dp)) {
            val strokeWidth = 3.dp.toPx()
            drawCircle(color = strokeColor, radius = size.minDimension / 2f, style = Stroke(width = strokeWidth))
            drawLine(
                color = strokeColor,
                start = androidx.compose.ui.geometry.Offset(x = 3.dp.toPx(), y = size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(x = size.width - 3.dp.toPx(), y = size.height / 2f),
                strokeWidth = strokeWidth,
            )
        }
    }
}

/** Only until Room answers for the first time, which is immediate in practice. */
@Composable
private fun InventoryLoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/* -------------------------------------------------------------------------- */
/* Previews                                                                    */
/* -------------------------------------------------------------------------- */

private fun previewRow(id: String, name: String, quantity: String, date: String, days: Int, urgency: InventoryUrgency) =
    InventoryRowUi(
        item = InventoryItemUi(id, name, quantity, date, days, urgency),
        hasEditDraft = false,
        busy = false,
        consumeEnabled = true,
        editEnabled = true,
    )

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4, showSystemUi = true)
@Composable
private fun InventoryContentPreview() {
    CampusMealTheme {
        InventoryScreen(
            state = InventoryUiState(
                loaded = true,
                snapshot = SnapshotUi(
                    sections = InventorySections(
                        expired = listOf(previewRow("0", "Bread", "1 unit", "2026-10-25", -2, InventoryUrgency.EXPIRED)),
                        today = listOf(previewRow("1", "Whole milk", "1 L", "2026-10-27", 0, InventoryUrgency.TODAY)),
                        soon = listOf(previewRow("2", "Tomatoes", "6 units", "2026-10-29", 2, InventoryUrgency.SOON)),
                        later = listOf(previewRow("4", "White rice", "1 kg", "2026-12-16", 50, InventoryUrgency.LATER)),
                    ),
                    syncedAtEpochMillis = System.currentTimeMillis() - 2 * 60_000,
                    stale = false,
                    awaitingRefresh = false,
                    todayIsoDate = "2026-10-27",
                ),
            ),
            actions = InventoryActions(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F5F4, showSystemUi = true)
@Composable
private fun InventoryNoLocalDataPreview() {
    CampusMealTheme {
        InventoryScreen(
            state = InventoryUiState(loaded = true, refresh = RefreshUi(problem = RefreshProblem.Offline)),
            actions = InventoryActions(),
        )
    }
}
