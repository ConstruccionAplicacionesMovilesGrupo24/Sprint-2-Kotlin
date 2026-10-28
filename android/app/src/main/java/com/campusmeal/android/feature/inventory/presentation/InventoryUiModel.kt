package com.campusmeal.android.feature.inventory.presentation

import com.campusmeal.android.feature.inventory.domain.model.DraftFields
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

enum class InventoryUrgency {
    EXPIRED,
    TODAY,
    SOON,
    LATER,
}

/**
 * One item as displayed. [daysLeft] is whichever day count the caller is entitled to show: Home passes
 * the server's BQ2 `remainingDays` (valid as of its sync date); Inventory passes its own Bogotá calendar
 * difference from `expirationDate`, which changes at midnight without a new request.
 */
data class InventoryItemUi(
    val id: String,
    val name: String,
    val quantityLabel: String,
    val expirationDate: String,
    val daysLeft: Int,
    val urgency: InventoryUrgency,
    /** Raw values used to prefill the edit form. */
    val quantity: Double = 0.0,
    val unit: String = "",
)

fun InventoryItem.toUi(daysLeft: Int): InventoryItemUi =
    InventoryItemUi(
        id = id,
        name = name,
        quantityLabel = "${formatQuantity(quantity)} $unit",
        expirationDate = expirationDate,
        daysLeft = daysLeft,
        urgency = when {
            daysLeft < 0 -> InventoryUrgency.EXPIRED
            daysLeft == 0 -> InventoryUrgency.TODAY
            daysLeft <= 3 -> InventoryUrgency.SOON
            else -> InventoryUrgency.LATER
        },
        quantity = quantity,
        unit = unit,
    )

fun InventoryItemUi.toDraftFields(): DraftFields =
    DraftFields(name = name, quantityText = formatQuantity(quantity), unit = unit, expirationDateText = expirationDate)

fun formatQuantity(quantity: Double): String =
    if (quantity % 1.0 == 0.0) quantity.toLong().toString() else quantity.toString()

/** A row of the complete inventory with what the user may do with it right now. */
data class InventoryRowUi(
    val item: InventoryItemUi,
    /** A saved edit draft exists for this item. */
    val hasEditDraft: Boolean,
    /** A save or consume of this item is running. */
    val busy: Boolean,
    val consumeEnabled: Boolean,
    val editEnabled: Boolean,
)

/** The complete inventory grouped by today's Bogotá date, backend order kept inside each group. */
data class InventorySections(
    val expired: List<InventoryRowUi>,
    val today: List<InventoryRowUi>,
    val soon: List<InventoryRowUi>,
    val later: List<InventoryRowUi>,
) {
    val isEmpty: Boolean get() = expired.isEmpty() && today.isEmpty() && soon.isEmpty() && later.isEmpty()

    fun rowById(itemId: String): InventoryRowUi? = (expired + today + soon + later).firstOrNull { it.item.id == itemId }
}

fun List<InventoryRowUi>.toSections(): InventorySections =
    InventorySections(
        expired = filter { it.item.urgency == InventoryUrgency.EXPIRED },
        today = filter { it.item.urgency == InventoryUrgency.TODAY },
        soon = filter { it.item.urgency == InventoryUrgency.SOON },
        later = filter { it.item.urgency == InventoryUrgency.LATER },
    )
