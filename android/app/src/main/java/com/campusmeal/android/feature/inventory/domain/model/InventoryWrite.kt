package com.campusmeal.android.feature.inventory.domain.model

/** Fields the user enters to add or edit an item. [expirationDate] is `YYYY-MM-DD`. */
data class InventoryItemDraft(
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
)

/** Outcome of add, edit or consume. The list is reloaded from the backend after a success. */
sealed interface InventoryWriteResult {

    data object Success : InventoryWriteResult

    /** There is no session, or the backend answered HTTP 401. */
    data object Unauthorized : InventoryWriteResult

    /** Writes are never queued offline: the backend owns ordering and `remainingDays`. */
    data class Failure(val error: InventoryError) : InventoryWriteResult
}
