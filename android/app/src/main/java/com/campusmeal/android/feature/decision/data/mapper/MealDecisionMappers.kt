package com.campusmeal.android.feature.decision.data.mapper

import com.campusmeal.android.feature.decision.data.remote.CompareMealOptionsRequestDto
import com.campusmeal.android.feature.decision.data.remote.CompareMealOptionsResponseDto
import com.campusmeal.android.feature.decision.data.remote.ExpiringIngredientDto
import com.campusmeal.android.feature.decision.data.remote.LocationDto
import com.campusmeal.android.feature.decision.data.remote.MealAlternativeDto
import com.campusmeal.android.feature.decision.data.remote.RestaurantDto
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.ExpiringIngredient
import com.campusmeal.android.feature.decision.domain.model.MealAlternative
import com.campusmeal.android.feature.decision.domain.model.MealDecision
import com.campusmeal.android.feature.decision.domain.model.MealOption
import com.campusmeal.android.feature.decision.domain.model.Restaurant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** BQ5 prioritises food expiring within this many days (0 = today). */
private const val MAX_EXPIRING_DAYS = 3

fun DecisionContext.toRequestDto(requestedAtEpochMillis: Long) = CompareMealOptionsRequestDto(
    location = LocationDto(location.coordinates.latitude, location.coordinates.longitude),
    campusId = location.campusId,
    availableMinutes = availableMinutes,
    maximumBudget = maximumBudget,
    dietaryPreferences = dietaryPreferences.map { it.name }.sorted(),
    includeDelivery = includeDelivery,
    requestedAt = isoUtc(requestedAtEpochMillis),
)

/** `java.time` needs API 26 and minSdk is 24, so the timestamp is formatted by hand. */
private fun isoUtc(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(epochMillis))

/** A mapped response plus how many alternatives were left out as incomplete or unsupported. */
data class MappedDecision(val decision: MealDecision, val skippedAlternatives: Int)

/**
 * Validates the response and maps it without reordering or rescoring anything. Returns null when
 * the envelope itself breaks the contract: blank identifier, blank explanation next to alternatives,
 * or more than one recommended alternative, since Android must not choose between them.
 *
 * Alternatives are checked one by one. An invalid one, an unsupported type, or a repeated type is
 * skipped and counted, and the rest keep the backend order.
 */
fun CompareMealOptionsResponseDto.toDomainOrNull(): MappedDecision? {
    if (recommendationId.isBlank()) return null

    val seenTypes = mutableSetOf<String>()
    val valid = alternatives.mapNotNull { dto ->
        dto.toDomainOrNull()?.takeIf { seenTypes.add(dto.type.orEmpty()) }
    }
    if (valid.count { it.recommended } > 1) return null
    if (valid.isNotEmpty() && mainExplanation.isBlank()) return null

    return MappedDecision(
        decision = MealDecision(
            recommendationId = recommendationId,
            alternatives = valid,
            mainExplanation = mainExplanation,
            supportingReasons = supportingReasons.filter { it.isNotBlank() },
        ),
        skippedAlternatives = alternatives.size - valid.size,
    )
}

private fun MealAlternativeDto.toDomainOrNull(): MealAlternative? {
    val rank = rank?.takeIf { it >= 1 } ?: return null
    val score = score?.takeIf { it.isFinite() } ?: return null
    val recommended = recommended ?: return null
    val minutes = estimatedMinutes?.takeIf { it >= 0 } ?: return null
    val cost = estimatedCost?.takeIf { it >= 0 } ?: return null

    val option = when (type) {
        "COOK" -> MealOption.Cook(
            expiringIngredients = expiringIngredients.orEmpty().map { it.toDomainOrNull() ?: return null },
        )
        "WALK" -> {
            val restaurant = restaurant ?: return null
            MealOption.Walk(
                restaurant = restaurant.toDomainOrNull() ?: return null,
                walkingMinutes = restaurant.walkingMinutes?.takeIf { it >= 0 } ?: return null,
            )
        }
        "ORDER" -> {
            val restaurant = restaurant ?: return null
            MealOption.Order(
                restaurant = restaurant.toDomainOrNull() ?: return null,
                deliveryMinutes = restaurant.deliveryMinutes?.takeIf { it >= 0 } ?: return null,
                deliveryFee = restaurant.deliveryFee?.takeIf { it >= 0 } ?: return null,
            )
        }
        else -> return null
    }
    return MealAlternative(rank, score, recommended, minutes, cost, option)
}

private fun ExpiringIngredientDto.toDomainOrNull(): ExpiringIngredient? {
    val itemId = itemId?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    val days = remainingDays?.takeIf { it in 0..MAX_EXPIRING_DAYS } ?: return null
    return ExpiringIngredient(itemId, name, days)
}

private fun RestaurantDto.toDomainOrNull(): Restaurant? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return Restaurant(id, name)
}
