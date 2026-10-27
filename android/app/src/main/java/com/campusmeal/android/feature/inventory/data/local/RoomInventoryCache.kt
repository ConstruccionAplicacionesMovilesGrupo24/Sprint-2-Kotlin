package com.campusmeal.android.feature.inventory.data.local

import androidx.room.withTransaction
import com.campusmeal.android.core.database.CacheMetadataEntity
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.feature.inventory.data.mapper.toDomain
import com.campusmeal.android.feature.inventory.data.mapper.toEntities
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem

/**
 * Room-backed [ExpiringInventoryCache]. Items live in `expiring_inventory_items`, owned by an account;
 * each account's sync timestamp lives in the shared `cache_metadata` table under [cacheKey]. The
 * metadata row, not the item count, tells whether a sync ever happened, so an empty successful result
 * is still a cached answer.
 */
class RoomInventoryCache(private val database: CampusMealDatabase) : ExpiringInventoryCache {

    private val itemsDao = database.expiringInventoryDao()
    private val metadataDao = database.cacheMetadataDao()

    override suspend fun read(accountId: String): CachedExpiringInventory? =
        database.withTransaction {
            val metadata = metadataDao.get(cacheKey(accountId)) ?: return@withTransaction null
            CachedExpiringInventory(
                items = itemsDao.getItems(accountId).map { it.toDomain() },
                lastSyncedAtEpochMillis = metadata.lastSyncedAtEpochMillis,
            )
        }

    override suspend fun replace(accountId: String, items: List<InventoryItem>, syncedAtEpochMillis: Long) {
        database.withTransaction {
            itemsDao.replaceItems(accountId, items.toEntities(accountId))
            metadataDao.upsert(CacheMetadataEntity(cacheKey = cacheKey(accountId), lastSyncedAtEpochMillis = syncedAtEpochMillis))
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        database.withTransaction {
            itemsDao.deleteItems(accountId)
            metadataDao.delete(cacheKey(accountId))
        }
    }

    override suspend fun deleteAllExcept(accountId: String) {
        database.withTransaction {
            itemsDao.deleteItemsExcept(accountId)
            metadataDao.deleteByPrefixExcept(prefix = "$CACHE_KEY_PREFIX%", keep = cacheKey(accountId))
        }
    }

    companion object {
        /** Also matches the ownerless Sprint 2 key `inventory.expiring`, which migration 2 → 3 deletes. */
        private const val CACHE_KEY_PREFIX = "inventory.expiring"

        fun cacheKey(accountId: String) = "$CACHE_KEY_PREFIX:$accountId"
    }
}
