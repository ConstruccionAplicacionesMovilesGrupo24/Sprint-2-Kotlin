package com.campusmeal.android.feature.inventory.domain.model

/**
 * An active inventory item, independent of Retrofit and Room. Used by both the complete inventory and
 * the BQ2 expiring list.
 *
 * [expirationDate] is a validated ISO `YYYY-MM-DD` string: `java.time` needs API 26 and core library
 * desugaring is not configured for minSdk 24. [remainingDays] is computed by the backend on the day of
 * the response (0 means the item expires that day); it is kept as received and never recomputed.
 */
data class InventoryItem(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
    val remainingDays: Int,
)
