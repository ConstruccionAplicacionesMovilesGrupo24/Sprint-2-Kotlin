package com.campusmeal.android.feature.inventory.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmeal.android.core.common.UiState
import java.text.DateFormat
import java.util.Date

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
        UiState.Loading -> LoadingInventory()

        UiState.Empty -> EmptyInventory(onAddItem)

        is UiState.Content -> InventoryContent(
            data = state.data,
            onAddItem = onAddItem,
            onEditItem = onEditItem,
            onConsumeItem = onConsumeItem,
        )

        is UiState.OfflineWithCache -> InventoryContent(
            data = state.data,
            lastUpdated = state.lastUpdatedEpochMillis,
            onAddItem = onAddItem,
            onEditItem = onEditItem,
            onConsumeItem = onConsumeItem,
        )

        is UiState.Error -> ErrorInventory(onRetry)

        UiState.Unauthorized ->
            CenterMessage("Your session has expired.")

        is UiState.PermissionDenied ->
            CenterMessage("Permission denied.")
    }
}

@Composable
private fun InventoryContent(
    data: InventoryScreenData,
    onAddItem: () -> Unit,
    onEditItem: (String) -> Unit,
    onConsumeItem: (String) -> Unit,
    lastUpdated: Long? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "My Inventory",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                Button(onClick = onAddItem) {
                    Text("+ Add")
                }
            }
        }

        if (lastUpdated != null) {
            item {
                OfflineBanner(lastUpdated)
            }
        }

        if (data.today.isNotEmpty()) {
            item { SectionTitle("Expires today") }

            items(data.today, key = { it.id }) { food ->
                InventoryCard(
                    item = food,
                    onEdit = { onEditItem(food.id) },
                    onConsume = { onConsumeItem(food.id) },
                )
            }
        }

        if (data.soon.isNotEmpty()) {
            item { SectionTitle("Expires within 3 days") }

            items(data.soon, key = { it.id }) { food ->
                InventoryCard(
                    item = food,
                    onEdit = { onEditItem(food.id) },
                    onConsume = { onConsumeItem(food.id) },
                )
            }
        }

        if (data.later.isNotEmpty()) {
            item { SectionTitle("Later") }

            items(data.later, key = { it.id }) { food ->
                InventoryCard(
                    item = food,
                    onEdit = { onEditItem(food.id) },
                    onConsume = { onConsumeItem(food.id) },
                )
            }
        }
    }
}

@Composable
private fun InventoryCard(
    item: InventoryItemUi,
    onEdit: () -> Unit,
    onConsume: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(item.quantityLabel)
            Text("Expiration: ${item.expirationDate}")

            Text(
                text = when (item.urgency) {
                    InventoryUrgency.TODAY ->
                        "⚠ Expires today"

                    InventoryUrgency.SOON ->
                        "⏱ ${item.remainingDays} days remaining"

                    InventoryUrgency.LATER ->
                        "${item.remainingDays} days remaining"
                },
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onConsume) {
                    Text("Consume")
                }

                OutlinedButton(onClick = onEdit) {
                    Text("Edit")
                }
            }
        }
    }
}

@Composable
private fun EmptyInventory(
    onAddItem: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Your inventory is empty",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(8.dp))

        Text("Add food to start tracking expiration dates.")

        Spacer(Modifier.height(24.dp))

        Button(onClick = onAddItem) {
            Text("Add food")
        }
    }
}

@Composable
private fun OfflineBanner(
    lastUpdated: Long,
) {
    val formatted = DateFormat
        .getDateTimeInstance()
        .format(Date(lastUpdated))

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Offline data · Last synchronized: $formatted",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun LoadingInventory() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorInventory(
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Unable to load inventory")

        Spacer(Modifier.height(12.dp))

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun CenterMessage(
    text: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}

@Composable
private fun SectionTitle(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}