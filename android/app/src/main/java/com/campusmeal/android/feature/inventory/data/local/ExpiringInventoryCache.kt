package com.campusmeal.android.feature.inventory.data.local

import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

/** Last successful BQ2 result. An empty [items] list is a valid cached answer. */
data class CachedExpiringInventory(
    val items: List<InventoryItem>,
    val lastSyncedAtEpochMillis: Long,
)

/** Local storage for the last successful BQ2 result; the repository depends on this interface. */
interface ExpiringInventoryCache {

    /** Returns null when no successful sync has been stored. */
    suspend fun read(): CachedExpiringInventory?

    /** Replaces the stored items and the sync timestamp in one transaction. */
    suspend fun replace(items: List<InventoryItem>, syncedAtEpochMillis: Long)

    suspend fun clear()
}
