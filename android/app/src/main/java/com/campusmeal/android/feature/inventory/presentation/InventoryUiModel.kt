package com.campusmeal.android.feature.inventory.presentation

import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

enum class InventoryUrgency {
    TODAY,
    SOON,
    LATER,
}

data class InventoryItemUi(
    val id: String,
    val name: String,
    val quantityLabel: String,
    val expirationDate: String,
    val remainingDays: Int,
    val urgency: InventoryUrgency,
)

data class InventoryScreenData(
    val today: List<InventoryItemUi>,
    val soon: List<InventoryItemUi>,
    val later: List<InventoryItemUi>,
)

fun List<InventoryItem>.toInventoryScreenData(): InventoryScreenData {
    val mapped = map { item ->
        val quantity = if (item.quantity % 1.0 == 0.0) {
            item.quantity.toInt().toString()
        } else {
            item.quantity.toString()
        }

        InventoryItemUi(
            id = item.id,
            name = item.name,
            quantityLabel = "$quantity ${item.unit}",
            expirationDate = item.expirationDate,
            remainingDays = item.remainingDays,
            urgency = when {
                item.remainingDays <= 0 -> InventoryUrgency.TODAY
                item.remainingDays <= 3 -> InventoryUrgency.SOON
                else -> InventoryUrgency.LATER
            },
        )
    }

    return InventoryScreenData(
        today = mapped.filter { it.urgency == InventoryUrgency.TODAY },
        soon = mapped.filter { it.urgency == InventoryUrgency.SOON },
        later = mapped.filter { it.urgency == InventoryUrgency.LATER },
    )
}