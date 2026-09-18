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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.UiState
import com.campusmeal.android.core.designsystem.CampusMealTheme
import com.campusmeal.android.core.designsystem.campusMealColors
import java.text.DateFormat
import java.util.Date

private val PagePadding = 22.dp
private val CardShape = RoundedCornerShape(14.dp)
private val ButtonShape = RoundedCornerShape(11.dp)
private val ButtonHeight = 52.dp

@Composable
fun InventoryRoute(
    viewModel: InventoryViewModel,
    onAddItem: () -> Unit = {},
    onEditItem: (String) -> Unit = {},
    onConsumeItem: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    InventoryScreen(
        state = state,
        onRetry = viewModel::loadInventory,
        onAddItem = onAddItem,
        onEditItem = onEditItem,
        onConsumeItem = onConsumeItem,
    )
}

@Composable
fun InventoryScreen(
    state: UiState<InventoryScreenData>,
    onRetry: () -> Unit,
    onAddItem: () -> Unit,
    onEditItem: (String) -> Unit,
    onConsumeItem: (String) -> Unit,
) {
    when (state) {
        UiState.Initial,
        UiState.Loading -> InventoryLoadingScreen()

        UiState.Empty -> EmptyInventoryScreen(
            onAddItem = onAddItem,
        )

        is UiState.Content -> InventoryContentScreen(
            data = state.data,
            syncLabel = "Synced just now",
            isOffline = false,
            onAddItem = onAddItem,
            onEditItem = onEditItem,
            onConsumeItem = onConsumeItem,
        )

        is UiState.OfflineWithCache -> InventoryContentScreen(
            data = state.data,
            syncLabel = cachedSyncLabel(
                state.lastUpdatedEpochMillis,
            ),
            isOffline = true,
            onAddItem = onAddItem,
            onEditItem = onEditItem,
            onConsumeItem = onConsumeItem,
        )

        is UiState.Error -> InventoryErrorScreen(
            onRetry = onRetry,
        )

        UiState.Unauthorized -> InventoryMessageScreen(
            title = "Session expired",
            message = "Sign in again to view your inventory.",
        )

        is UiState.PermissionDenied -> InventoryMessageScreen(
            title = "Permission required",
            message = "This feature needs additional permission.",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryContentScreen(
    data: InventoryScreenData,
    syncLabel: String,
    isOffline: Boolean,
    onAddItem: () -> Unit,
    onEditItem: (String) -> Unit,
    onConsumeItem: (String) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            InventoryTopBar()
        },
        bottomBar = {
            AddItemBottomBar(
                onAddItem = onAddItem,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = 12.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            item {
                SyncRow(
                    syncLabel = syncLabel,
                    isOffline = isOffline,
                    onAddItem = onAddItem,
                )
            }

            if (data.today.isNotEmpty()) {
                item {
                    InventorySectionHeader(
                        title = "Due today",
                        itemCount = data.today.size,
                    )
                }

                items(
                    items = data.today,
                    key = { it.id },
                ) { item ->
                    InventoryItemCard(
                        item = item,
                        onConsume = {
                            onConsumeItem(item.id)
                        },
                        onEdit = {
                            onEditItem(item.id)
                        },
                    )
                }
            }

            if (data.soon.isNotEmpty()) {
                item {
                    InventorySectionHeader(
                        title = "Next 3 days",
                        itemCount = data.soon.size,
                    )
                }

                items(
                    items = data.soon,
                    key = { it.id },
                ) { item ->
                    InventoryItemCard(
                        item = item,
                        onConsume = {
                            onConsumeItem(item.id)
                        },
                        onEdit = {
                            onEditItem(item.id)
                        },
                    )
                }
            }

            if (data.later.isNotEmpty()) {
                item {
                    InventorySectionHeader(
                        title = "Later",
                        itemCount = data.later.size,
                    )
                }

                items(
                    items = data.later,
                    key = { it.id },
                ) { item ->
                    InventoryItemCard(
                        item = item,
                        onConsume = {
                            onConsumeItem(item.id)
                        },
                        onEdit = {
                            onEditItem(item.id)
                        },
                    )
                }
            }
        }
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun SyncRow(
    syncLabel: String,
    isOffline: Boolean,
    onAddItem: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = syncLabel,
            style = MaterialTheme.typography.bodySmall,
            color = if (isOffline) {
                MaterialTheme.campusMealColors.warningForeground
            } else {
                MaterialTheme.campusMealColors.textSecondary
            },
        )

        TextButton(
            onClick = onAddItem,
        ) {
            Text(
                text = "+ Add",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
            .padding(
                top = 8.dp,
                bottom = 2.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Text(
            text = if (itemCount == 1) {
                "1 item"
            } else {
                "$itemCount items"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.campusMealColors.textSecondary,
        )
    }
}

@Composable
private fun InventoryItemCard(
    item: InventoryItemUi,
    onConsume: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 13.dp,
            ),
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
                }

                Spacer(Modifier.width(12.dp))

                UrgencyBadge(item)
            }

            /*
             * Consume/Edit are intentionally kept even though the Figma
             * cards do not show them yet. The issue requires those actions.
             */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onConsume,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 0.dp,
                    ),
                ) {
                    Text(
                        text = "Consume",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                OutlinedButton(
                    onClick = onEdit,
                    shape = ButtonShape,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 0.dp,
                    ),
                ) {
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun UrgencyBadge(
    item: InventoryItemUi,
) {
    val semanticColors = MaterialTheme.campusMealColors

    val background: Color
    val foreground: Color
    val label: String

    when (item.urgency) {
        InventoryUrgency.TODAY -> {
            background = semanticColors.urgentBackground
            foreground = semanticColors.urgentForeground
            label = "Due today"
        }

        InventoryUrgency.SOON -> {
            background = semanticColors.warningBackground
            foreground = semanticColors.warningForeground
            label = when (item.remainingDays) {
                1 -> "In 1 day"
                else -> "In ${item.remainingDays} days"
            }
        }

        InventoryUrgency.LATER -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            foreground = semanticColors.textSecondary
            label = when (item.remainingDays) {
                1 -> "In 1 day"
                else -> "In ${item.remainingDays} days"
            }
        }
    }

    Surface(
        color = background,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 11.dp,
                vertical = 7.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            UrgencyMark(
                urgency = item.urgency,
                color = foreground,
            )

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
            )
        }
    }
}

@Composable
private fun UrgencyMark(
    urgency: InventoryUrgency,
    color: Color,
) {
    when (urgency) {
        InventoryUrgency.TODAY -> {
            Canvas(
                modifier = Modifier.size(9.dp),
            ) {
                drawWarningTriangle(color)
            }
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
            Canvas(
                modifier = Modifier.size(10.dp),
            ) {
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2f,
                    style = Stroke(
                        width = 2.dp.toPx(),
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawWarningTriangle(
    color: Color,
) {
    val path = Path().apply {
        moveTo(
            size.width / 2f,
            0f,
        )
        lineTo(
            size.width,
            size.height,
        )
        lineTo(
            0f,
            size.height,
        )
        close()
    }

    drawPath(
        path = path,
        color = color,
    )
}

@Composable
private fun AddItemBottomBar(
    onAddItem: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
    ) {
        Button(
            onClick = onAddItem,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 22.dp,
                    vertical = 14.dp,
                )
                .height(ButtonHeight),
            shape = ButtonShape,
        ) {
            Text(
                text = "Add item",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyInventoryScreen(
    onAddItem: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            InventoryTopBar()
        },
        bottomBar = {
            AddItemBottomBar(
                onAddItem = onAddItem,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    horizontal = PagePadding,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                modifier = Modifier.height(100.dp),
            )

            EmptyInventoryIllustration()

            Spacer(
                modifier = Modifier.height(28.dp),
            )

            Text(
                text = "Your inventory is empty",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.campusMealColors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(
                modifier = Modifier.height(10.dp),
            )

            Text(
                text = "Add what you have at home and CampusMeal can " +
                        "suggest recipes before anything expires.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        Canvas(
            modifier = Modifier.size(42.dp),
        ) {
            val strokeWidth = 3.dp.toPx()

            drawCircle(
                color = strokeColor,
                radius = size.minDimension / 2f,
                style = Stroke(
                    width = strokeWidth,
                ),
            )

            drawLine(
                color = strokeColor,
                start = androidx.compose.ui.geometry.Offset(
                    x = 3.dp.toPx(),
                    y = size.height / 2f,
                ),
                end = androidx.compose.ui.geometry.Offset(
                    x = size.width - 3.dp.toPx(),
                    y = size.height / 2f,
                ),
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
private fun InventoryLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
            )

            Text(
                text = "Checking your inventory…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.campusMealColors.textSecondary,
            )
        }
    }
}

@Composable
private fun InventoryErrorScreen(
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "We couldn't load your inventory",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        Text(
            text = "Check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
        )

        Spacer(
            modifier = Modifier.height(24.dp),
        )

        Button(
            onClick = onRetry,
            shape = ButtonShape,
        ) {
            Text("Try again")
        }
    }
}

@Composable
private fun InventoryMessageScreen(
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.campusMealColors.textPrimary,
        )

        Spacer(
            modifier = Modifier.height(8.dp),
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.campusMealColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun cachedSyncLabel(
    timestamp: Long?,
): String {
    if (timestamp == null) {
        return "Offline · Last sync unavailable"
    }

    return "Offline · ${relativeSyncLabel(timestamp)}"
}

private fun relativeSyncLabel(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val elapsedMillis = (now - timestamp).coerceAtLeast(0L)
    val minutes = elapsedMillis / 60_000L

    return when {
        minutes < 1L ->
            "Synced just now"

        minutes == 1L ->
            "Synced 1 minute ago"

        minutes < 60L ->
            "Synced $minutes minutes ago"

        else -> {
            val formatted = DateFormat
                .getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                )
                .format(Date(timestamp))

            "Synced $formatted"
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Previews                                                                    */
/* -------------------------------------------------------------------------- */

@Preview(
    showBackground = true,
    backgroundColor = 0xFFF3F5F4,
    showSystemUi = true,
)
@Composable
private fun InventoryContentPreview() {
    CampusMealTheme {
        InventoryScreen(
            state = UiState.Content(
                InventoryScreenData(
                    today = listOf(
                        InventoryItemUi(
                            id = "1",
                            name = "Whole milk",
                            quantityLabel = "1 L",
                            expirationDate = "2026-09-17",
                            remainingDays = 0,
                            urgency = InventoryUrgency.TODAY,
                        ),
                    ),
                    soon = listOf(
                        InventoryItemUi(
                            id = "2",
                            name = "Tomatoes",
                            quantityLabel = "6 units",
                            expirationDate = "2026-09-19",
                            remainingDays = 2,
                            urgency = InventoryUrgency.SOON,
                        ),
                        InventoryItemUi(
                            id = "3",
                            name = "Chicken breast",
                            quantityLabel = "500 g",
                            expirationDate = "2026-09-20",
                            remainingDays = 3,
                            urgency = InventoryUrgency.SOON,
                        ),
                    ),
                    later = listOf(
                        InventoryItemUi(
                            id = "4",
                            name = "White rice",
                            quantityLabel = "1 kg",
                            expirationDate = "2026-10-27",
                            remainingDays = 40,
                            urgency = InventoryUrgency.LATER,
                        ),
                        InventoryItemUi(
                            id = "5",
                            name = "Olive oil",
                            quantityLabel = "500 ml",
                            expirationDate = "2026-12-16",
                            remainingDays = 90,
                            urgency = InventoryUrgency.LATER,
                        ),
                    ),
                ),
            ),
            onRetry = {},
            onAddItem = {},
            onEditItem = {},
            onConsumeItem = {},
        )
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFFF3F5F4,
    showSystemUi = true,
)
@Composable
private fun InventoryEmptyPreview() {
    CampusMealTheme {
        InventoryScreen(
            state = UiState.Empty,
            onRetry = {},
            onAddItem = {},
            onEditItem = {},
            onConsumeItem = {},
        )
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFFF3F5F4,
    showSystemUi = true,
)
@Composable
private fun InventoryOfflinePreview() {
    CampusMealTheme {
        InventoryScreen(
            state = UiState.OfflineWithCache(
                data = InventoryScreenData(
                    today = listOf(
                        InventoryItemUi(
                            id = "1",
                            name = "Whole milk",
                            quantityLabel = "1 L",
                            expirationDate = "2026-09-17",
                            remainingDays = 0,
                            urgency = InventoryUrgency.TODAY,
                        ),
                    ),
                    soon = listOf(
                        InventoryItemUi(
                            id = "2",
                            name = "Tomatoes",
                            quantityLabel = "6 units",
                            expirationDate = "2026-09-19",
                            remainingDays = 2,
                            urgency = InventoryUrgency.SOON,
                        ),
                    ),
                    later = emptyList(),
                ),
                lastUpdatedEpochMillis =
                    System.currentTimeMillis() - (5 * 60 * 1000),
            ),
            onRetry = {},
            onAddItem = {},
            onEditItem = {},
            onConsumeItem = {},
        )
    }
}