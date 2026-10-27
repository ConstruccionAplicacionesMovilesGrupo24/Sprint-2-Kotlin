package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * One item of an account's last successful BQ2 result. [accountId] is the owner (the `auth/me` id);
 * [priorityIndex] restores the backend's order within that account's snapshot.
 */
@Entity(
    tableName = "expiring_inventory_items",
    primaryKeys = ["accountId", "itemId"],
    indices = [Index(value = ["accountId", "priorityIndex"], unique = true)],
)
data class ExpiringInventoryEntity(
    val accountId: String,
    val itemId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
    val remainingDays: Int,
    val priorityIndex: Int,
)
