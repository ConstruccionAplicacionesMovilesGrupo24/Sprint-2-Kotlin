package com.campusmeal.android.feature.inventory.data.remote

import kotlinx.serialization.Serializable

/**
 * NestJS contract shared by `GET inventory` and `GET inventory/expiring` (`InventoryItemsResponseDto`
 * in CampusMealBack). Every field is required, so a missing field fails decoding; unknown fields are
 * ignored by the shared Json configuration. Keep these types out of the UI: map them with the
 * inventory mappers.
 */
@Serializable
data class InventoryItemsResponseDto(
    val items: List<InventoryItemDto>,
)

@Serializable
data class InventoryItemDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
    val remainingDays: Int,
    val active: Boolean,
)

/** Body of `POST inventory`. [expirationDate] is a calendar date `YYYY-MM-DD`. */
@Serializable
data class CreateInventoryItemRequestDto(
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
)

/** Body of `PATCH inventory/{id}`. Null fields are omitted by the shared Json (explicitNulls = false). */
@Serializable
data class UpdateInventoryItemRequestDto(
    val name: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val expirationDate: String? = null,
)
