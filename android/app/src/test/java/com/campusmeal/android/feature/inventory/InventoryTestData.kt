package com.campusmeal.android.feature.inventory

import com.campusmeal.android.feature.inventory.data.remote.InventoryItemDto
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

internal fun inventoryItemDto(
    id: String = "item-001",
    name: String = "Milk",
    quantity: Double = 1.0,
    unit: String = "L",
    expirationDate: String = "2026-09-16",
    remainingDays: Int = 1,
    active: Boolean = true,
) = InventoryItemDto(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
    expirationDate = expirationDate,
    remainingDays = remainingDays,
    active = active,
)

internal fun inventoryItem(
    id: String = "item-001",
    name: String = "Milk",
    remainingDays: Int = 1,
) = InventoryItem(
    id = id,
    name = name,
    quantity = 1.0,
    unit = "L",
    expirationDate = "2026-09-16",
    remainingDays = remainingDays,
)
