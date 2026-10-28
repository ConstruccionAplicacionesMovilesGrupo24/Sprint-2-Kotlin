package com.campusmeal.android.feature.inventory.data.mapper

import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryEntity
import com.campusmeal.android.feature.inventory.data.local.InventoryDraftEntity
import com.campusmeal.android.feature.inventory.data.local.InventoryItemEntity
import com.campusmeal.android.feature.inventory.data.local.PendingConsumeEntity
import com.campusmeal.android.feature.inventory.data.remote.InventoryItemsResponseDto
import com.campusmeal.android.feature.inventory.data.remote.InventoryItemDto
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import com.campusmeal.android.feature.inventory.domain.model.DraftFields
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume

private val ISO_DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * Maps the response to the items that answer BQ2, keeping the backend's order. Nothing is sorted
 * and `remainingDays` is never recomputed.
 *
 * Returns null when any item breaks the contract or two items share an id, so the whole response is
 * treated as invalid instead of silently dropping an item. Valid items that are inactive or outside
 * `0..withinDays` are excluded.
 */
fun InventoryItemsResponseDto.toExpiringItemsOrNull(
    withinDays: Int = EXPIRING_WITHIN_DAYS,
): List<InventoryItem>? {
    val mapped = items.map { dto -> dto.toDomainOrNull() ?: return null }
    if (mapped.distinctBy { it.id }.size != mapped.size) return null

    return items.zip(mapped)
        .filter { (dto, item) -> dto.active && item.remainingDays in 0..withinDays }
        .map { (_, item) -> item }
}

/**
 * Maps the complete inventory (`GET inventory`) without any BQ2 filtering: expired items, items
 * beyond three days and zero quantities are all kept, in the backend's order. Inactive rows are not
 * part of the active inventory and are left out.
 *
 * Returns null when any item breaks the contract or two items share an id, so an invalid response
 * never replaces a stored snapshot.
 */
fun InventoryItemsResponseDto.toCompleteInventoryOrNull(): List<InventoryItem>? {
    val mapped = items.map { dto -> dto.toDomainOrNull() ?: return null }
    if (mapped.distinctBy { it.id }.size != mapped.size) return null
    return items.zip(mapped).filter { (dto, _) -> dto.active }.map { (_, item) -> item }
}

/**
 * Maps one item, or returns null when a required text is blank, the quantity is negative or not
 * finite, or the expiration date is not a real ISO `YYYY-MM-DD` date.
 */
fun InventoryItemDto.toDomainOrNull(): InventoryItem? {
    val valid = id.isNotBlank() &&
        name.isNotBlank() &&
        unit.isNotBlank() &&
        quantity.isFinite() &&
        quantity >= 0.0 &&
        isIsoCalendarDate(expirationDate)
    if (!valid) return null

    return InventoryItem(
        id = id,
        name = name,
        quantity = quantity,
        unit = unit,
        expirationDate = expirationDate,
        remainingDays = remainingDays,
    )
}

/**
 * [accountId] owns the row. [priorityIndex] is the item's position in the backend response; Room
 * restores the order from it.
 */
fun InventoryItem.toEntity(accountId: String, priorityIndex: Int): ExpiringInventoryEntity =
    ExpiringInventoryEntity(
        accountId = accountId,
        itemId = id,
        name = name,
        quantity = quantity,
        unit = unit,
        expirationDate = expirationDate,
        remainingDays = remainingDays,
        priorityIndex = priorityIndex,
    )

fun List<InventoryItem>.toEntities(accountId: String): List<ExpiringInventoryEntity> =
    mapIndexed { index, item -> item.toEntity(accountId = accountId, priorityIndex = index) }

fun ExpiringInventoryEntity.toDomain(): InventoryItem =
    InventoryItem(
        id = itemId,
        name = name,
        quantity = quantity,
        unit = unit,
        expirationDate = expirationDate,
        remainingDays = remainingDays,
    )

/** [position] is the item's index in the complete response. */
fun List<InventoryItem>.toCompleteEntities(accountId: String): List<InventoryItemEntity> =
    mapIndexed { index, item ->
        InventoryItemEntity(
            accountId = accountId,
            itemId = item.id,
            name = item.name,
            quantity = item.quantity,
            unit = item.unit,
            expirationDate = item.expirationDate,
            remainingDays = item.remainingDays,
            position = index,
        )
    }

fun InventoryItemEntity.toDomain(): InventoryItem =
    InventoryItem(
        id = itemId,
        name = name,
        quantity = quantity,
        unit = unit,
        expirationDate = expirationDate,
        remainingDays = remainingDays,
    )

fun InventoryDraftEntity.toDomain(): InventoryDraft =
    InventoryDraft(
        draftId = draftId,
        targetItemId = targetItemId,
        fields = DraftFields(name, quantityText, unit, expirationDateText),
        updatedAtEpochMillis = updatedAtEpochMillis,
        lastSubmitOutcome = lastSubmitOutcome,
        lastSubmitAtEpochMillis = lastSubmitAtEpochMillis,
        lastHttpCode = lastHttpCode,
        lastErrorCode = lastErrorCode,
    )

fun PendingConsumeEntity.toDomain(): PendingConsume =
    PendingConsume(itemId = itemId, itemName = itemName, outcome = outcome, startedAtEpochMillis = startedAtEpochMillis)

internal fun isIsoCalendarDate(value: String): Boolean {
    if (!ISO_DATE_PATTERN.matches(value)) return false
    val (year, month, day) = value.split('-').map(String::toInt)
    if (month !in 1..12 || day < 1) return false

    val isLeapYear = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val daysInMonth = when (month) {
        2 -> if (isLeapYear) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day <= daysInMonth
}
