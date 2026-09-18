package com.campusmeal.android.feature.inventory.data.mapper

import com.campusmeal.android.feature.inventory.data.remote.ExpiringInventoryResponseDto
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.inventoryItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryMappersTest {

    @Test
    fun dtoMapsEveryFieldToDomain() {
        val dto = inventoryItemDto(
            id = "item-001",
            name = "Milk",
            quantity = 1.5,
            unit = "L",
            expirationDate = "2026-09-16",
            remainingDays = 1,
        )

        assertEquals(
            InventoryItem(
                id = "item-001",
                name = "Milk",
                quantity = 1.5,
                unit = "L",
                expirationDate = "2026-09-16",
                remainingDays = 1,
            ),
            dto.toDomainOrNull(),
        )
    }

    @Test
    fun responseKeepsBackendOrderWithoutSorting() {
        val response = ExpiringInventoryResponseDto(
            listOf(
                inventoryItemDto(id = "c", expirationDate = "2026-09-18", remainingDays = 3),
                inventoryItemDto(id = "a", expirationDate = "2026-09-15", remainingDays = 0),
                inventoryItemDto(id = "b", expirationDate = "2026-09-16", remainingDays = 1),
            ),
        )

        assertEquals(listOf("c", "a", "b"), response.toExpiringItemsOrNull()?.map { it.id })
    }

    @Test
    fun inactiveItemsAreExcluded() {
        val response = ExpiringInventoryResponseDto(
            listOf(
                inventoryItemDto(id = "active"),
                inventoryItemDto(id = "inactive", active = false),
            ),
        )

        assertEquals(listOf("active"), response.toExpiringItemsOrNull()?.map { it.id })
    }

    @Test
    fun itemsOutsideTheThreeDayWindowAreExcluded() {
        val response = ExpiringInventoryResponseDto(
            listOf(
                inventoryItemDto(id = "expired", remainingDays = -1),
                inventoryItemDto(id = "today", remainingDays = 0),
                inventoryItemDto(id = "in-three-days", remainingDays = 3),
                inventoryItemDto(id = "in-four-days", remainingDays = 4),
            ),
        )

        assertEquals(listOf("today", "in-three-days"), response.toExpiringItemsOrNull()?.map { it.id })
    }

    @Test
    fun emptyResponseIsAValidEmptyAnswer() {
        assertEquals(emptyList<InventoryItem>(), ExpiringInventoryResponseDto(emptyList()).toExpiringItemsOrNull())
    }

    @Test
    fun invalidRequiredValuesRejectTheWholeResponse() {
        val invalidItems = listOf(
            inventoryItemDto(id = " "),
            inventoryItemDto(name = ""),
            inventoryItemDto(unit = ""),
            inventoryItemDto(quantity = -1.0),
            inventoryItemDto(quantity = Double.NaN),
            inventoryItemDto(expirationDate = "16/09/2026"),
            inventoryItemDto(expirationDate = "2026-02-30"),
        )

        invalidItems.forEach { invalid ->
            val response = ExpiringInventoryResponseDto(listOf(inventoryItemDto(id = "valid"), invalid))
            assertNull("Expected rejection for $invalid", response.toExpiringItemsOrNull())
        }
    }

    @Test
    fun duplicateIdsRejectTheWholeResponse() {
        val response = ExpiringInventoryResponseDto(listOf(inventoryItemDto(id = "same"), inventoryItemDto(id = "same")))

        assertNull(response.toExpiringItemsOrNull())
    }

    @Test
    fun entityRoundTripKeepsFieldsAndPriorityIndex() {
        val items = listOf(inventoryItemDto(id = "first"), inventoryItemDto(id = "second", remainingDays = 2))
            .map { requireNotNull(it.toDomainOrNull()) }

        val entities = items.toEntities()

        assertEquals(listOf(0, 1), entities.map { it.priorityIndex })
        assertEquals(items, entities.map { it.toDomain() })
    }

    @Test
    fun isoDateValidationChecksRealCalendarDates() {
        assertTrue(isIsoCalendarDate("2028-02-29"))
        assertFalse(isIsoCalendarDate("2026-02-29"))
        assertFalse(isIsoCalendarDate("2026-13-01"))
        assertFalse(isIsoCalendarDate("2026-9-16"))
    }
}
