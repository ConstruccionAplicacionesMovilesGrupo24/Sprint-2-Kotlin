package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ExpiringInventoryDao {

    @Query("SELECT * FROM expiring_inventory_items WHERE accountId = :accountId ORDER BY priorityIndex ASC")
    abstract suspend fun getItems(accountId: String): List<ExpiringInventoryEntity>

    @Insert
    abstract suspend fun insertItems(items: List<ExpiringInventoryEntity>)

    @Query("DELETE FROM expiring_inventory_items WHERE accountId = :accountId")
    abstract suspend fun deleteItems(accountId: String)

    @Query("DELETE FROM expiring_inventory_items WHERE accountId != :accountId")
    abstract suspend fun deleteItemsExcept(accountId: String)

    /** Replaces [accountId]'s previous result atomically. An empty list leaves that account with no rows. */
    @Transaction
    open suspend fun replaceItems(accountId: String, items: List<ExpiringInventoryEntity>) {
        deleteItems(accountId)
        insertItems(items)
    }
}
