package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * One active item of an account's complete inventory snapshot (`GET inventory`), expired items and
 * zero quantities included. [remainingDays] is the server's value on the day of the sync and is kept
 * as received; the Inventory screen computes its own Bogotá day difference from [expirationDate].
 * [position] restores the backend's order within the snapshot.
 */
@Entity(tableName = "inventory_items", primaryKeys = ["accountId", "itemId"])
data class InventoryItemEntity(
    val accountId: String,
    val itemId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
    val remainingDays: Int,
    val position: Int,
)

@Dao
abstract class InventoryItemDao {

    @Query("SELECT * FROM inventory_items WHERE accountId = :accountId ORDER BY position ASC")
    abstract suspend fun getItems(accountId: String): List<InventoryItemEntity>

    @Insert
    abstract suspend fun insertItems(items: List<InventoryItemEntity>)

    @Query("DELETE FROM inventory_items WHERE accountId = :accountId")
    abstract suspend fun deleteItems(accountId: String)

    @Query("DELETE FROM inventory_items WHERE accountId != :accountId")
    abstract suspend fun deleteItemsExcept(accountId: String)

    /** Replaces [accountId]'s snapshot atomically. An empty list leaves that account with no rows. */
    @Transaction
    open suspend fun replaceItems(accountId: String, items: List<InventoryItemEntity>) {
        deleteItems(accountId)
        insertItems(items)
    }
}
