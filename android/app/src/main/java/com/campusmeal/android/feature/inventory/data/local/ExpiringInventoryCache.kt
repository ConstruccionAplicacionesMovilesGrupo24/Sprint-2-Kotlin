package com.campusmeal.android.feature.inventory.data.local

import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

/** Last successful BQ2 result. An empty [items] list is a valid cached answer. */
data class CachedExpiringInventory(
    val items: List<InventoryItem>,
    val lastSyncedAtEpochMillis: Long,
)

/**
 * Local storage for each account's last successful BQ2 result; the repository depends on this
 * interface. Every operation names the owning account, so one account never reads another's snapshot.
 */
interface ExpiringInventoryCache : AccountScopedStore {

    /** Returns null when no successful sync has been stored for [accountId]. */
    suspend fun read(accountId: String): CachedExpiringInventory?

    /** Replaces [accountId]'s stored items and sync timestamp in one transaction. */
    suspend fun replace(accountId: String, items: List<InventoryItem>, syncedAtEpochMillis: Long)
}
