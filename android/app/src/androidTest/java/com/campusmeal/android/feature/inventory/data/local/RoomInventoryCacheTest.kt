package com.campusmeal.android.feature.inventory.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campusmeal.android.core.database.CacheMetadataEntity
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomInventoryCacheTest {

    private lateinit var database: CampusMealDatabase
    private lateinit var cache: RoomInventoryCache

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CampusMealDatabase::class.java,
        ).build()
        cache = RoomInventoryCache(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun daoReturnsItemsOrderedByPriorityIndex() = runTest {
        val dao = database.expiringInventoryDao()

        dao.replaceItems(
            listOf(
                entity(itemId = "second", priorityIndex = 1),
                entity(itemId = "third", priorityIndex = 2),
                entity(itemId = "first", priorityIndex = 0),
            ),
        )

        assertEquals(listOf("first", "second", "third"), dao.getItems().map { it.itemId })
    }

    @Test
    fun replaceStoresItemsInBackendOrderWithTimestamp() = runTest {
        val items = listOf(item(id = "yogurt", remainingDays = 2), item(id = "milk", remainingDays = 0))

        cache.replace(items, syncedAtEpochMillis = 1_000)

        assertEquals(CachedExpiringInventory(items, lastSyncedAtEpochMillis = 1_000), cache.read())
    }

    @Test
    fun replaceOverwritesPreviousResultAndTimestamp() = runTest {
        cache.replace(listOf(item(id = "milk"), item(id = "bread")), syncedAtEpochMillis = 1_000)
        val newItems = listOf(item(id = "eggs", remainingDays = 3))

        cache.replace(newItems, syncedAtEpochMillis = 2_000)

        assertEquals(CachedExpiringInventory(newItems, lastSyncedAtEpochMillis = 2_000), cache.read())
        assertEquals(1, database.expiringInventoryDao().getItems().size)
    }

    @Test
    fun emptyResultIsStoredAsACachedAnswer() = runTest {
        cache.replace(listOf(item(id = "milk")), syncedAtEpochMillis = 1_000)

        cache.replace(emptyList(), syncedAtEpochMillis = 2_000)

        assertEquals(CachedExpiringInventory(emptyList(), lastSyncedAtEpochMillis = 2_000), cache.read())
    }

    @Test
    fun readReturnsNullBeforeFirstSyncAndAfterClear() = runTest {
        assertNull(cache.read())

        cache.replace(listOf(item(id = "milk")), syncedAtEpochMillis = 1_000)
        cache.clear()

        assertNull(cache.read())
        assertEquals(emptyList<ExpiringInventoryEntity>(), database.expiringInventoryDao().getItems())
    }

    @Test
    fun clearKeepsMetadataOfOtherCaches() = runTest {
        val otherMetadata = CacheMetadataEntity(cacheKey = "other.cache", lastSyncedAtEpochMillis = 500)
        database.cacheMetadataDao().upsert(otherMetadata)
        cache.replace(listOf(item(id = "milk")), syncedAtEpochMillis = 1_000)

        cache.clear()

        assertEquals(otherMetadata, database.cacheMetadataDao().get("other.cache"))
    }

    private fun item(id: String, remainingDays: Int = 1) = InventoryItem(
        id = id,
        name = "Item $id",
        quantity = 2.0,
        unit = "units",
        expirationDate = "2026-09-16",
        remainingDays = remainingDays,
    )

    private fun entity(itemId: String, priorityIndex: Int) = ExpiringInventoryEntity(
        itemId = itemId,
        name = "Item $itemId",
        quantity = 1.0,
        unit = "L",
        expirationDate = "2026-09-16",
        remainingDays = 1,
        priorityIndex = priorityIndex,
    )
}
