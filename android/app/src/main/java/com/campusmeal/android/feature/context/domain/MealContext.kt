package com.campusmeal.android.feature.context.domain

/**
 * Dietary filters supported by the restaurant-search context.
 * Names intentionally match the backend enum values.
 */
enum class DietaryPreference {
    VEGETARIAN,
    VEGAN,
    GLUTEN_FREE,
}

/**
 * Complete user context required by BQ4.
 *
 * [location] may come from approximate device location or from
 * a manually selected campus.
 */
data class MealContext(
    val location: MealLocation,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val dietaryPreferences: Set<DietaryPreference>,
    val includeDelivery: Boolean,
)