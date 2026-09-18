package com.campusmeal.android.feature.restaurants.data

import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.restaurants.domain.Meal
import com.campusmeal.android.feature.restaurants.domain.OpeningStatus
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetail
import com.campusmeal.android.feature.restaurants.domain.RestaurantResults
import com.campusmeal.android.feature.restaurants.domain.RestaurantSummary
import com.campusmeal.android.feature.restaurants.domain.ResultSource
import com.campusmeal.android.feature.restaurants.domain.RouteProviderStatus
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Rules: a restaurant missing a required field (id, name, category, a known opening status, a
 * non-negative minimum price) is dropped. A bad optional value (negative time, rating outside 0–5,
 * unknown dietary tag, blank reason) becomes null or is ignored, and the restaurant stays. The
 * backend order is kept.
 */

/**
 * Returns null when the backend sent restaurants but none could be shown: that is a broken contract,
 * not an honest "no results". [fallbackTimeMillis] is used when `lastUpdatedAt` is absent.
 */
fun RestaurantSearchResponseDto.toResultsOrNull(fallbackTimeMillis: Long, source: ResultSource): RestaurantResults? {
    val valid = restaurants.mapNotNull { it.toDomainOrNull() }
    if (restaurants.isNotEmpty() && valid.isEmpty()) return null
    return RestaurantResults(
        restaurants = valid,
        routeProviderStatus = routeStatus(routeProviderStatus, valid),
        lastUpdatedAtEpochMillis = parseIsoUtc(lastUpdatedAt) ?: fallbackTimeMillis,
        source = source,
    )
}

fun RestaurantDetailResponseDto.toDetailOrNull(fallbackTimeMillis: Long): RestaurantDetail? {
    val summary = restaurant.toDomainOrNull() ?: return null
    return RestaurantDetail(
        summary = summary,
        address = address?.takeIf { it.isNotBlank() },
        meals = meals.mapNotNull { it.toDomainOrNull() },
        routeProviderStatus = routeStatus(routeProviderStatus, listOf(summary)),
        lastUpdatedAtEpochMillis = parseIsoUtc(lastUpdatedAt) ?: fallbackTimeMillis,
    )
}

private fun RestaurantDto.toDomainOrNull(): RestaurantSummary? = RestaurantSummary(
    id = id?.takeIf { it.isNotBlank() } ?: return null,
    name = name?.takeIf { it.isNotBlank() } ?: return null,
    category = category?.takeIf { it.isNotBlank() } ?: return null,
    openingStatus = enumOrNull<OpeningStatus>(openingStatus) ?: return null,
    walkingMinutes = walkingMinutes?.takeIf { it >= 0 },
    estimatedTotalMinutes = estimatedTotalMinutes?.takeIf { it >= 0 },
    minimumMealPrice = minimumMealPrice?.takeIf { it >= 0 } ?: return null,
    dietaryTags = dietaryTags.toDietaryPreferences(),
    averageRating = averageRating?.takeIf { it in 0.0..5.0 },
    recommendationReason = recommendationReason?.takeIf { it.isNotBlank() },
)

private fun MealDto.toDomainOrNull(): Meal? = Meal(
    id = id?.takeIf { it.isNotBlank() } ?: return null,
    name = name?.takeIf { it.isNotBlank() } ?: return null,
    price = price?.takeIf { it >= 0 } ?: return null,
    dietaryTags = dietaryTags.toDietaryPreferences(),
)

/**
 * The backend's value is kept as-is when it is one of the three known ones. When it is missing or
 * unknown, the status is derived from the estimates actually present, so the screen never claims
 * routes are available when they are not.
 */
private fun routeStatus(value: String?, restaurants: List<RestaurantSummary>): RouteProviderStatus =
    enumOrNull<RouteProviderStatus>(value) ?: when {
        restaurants.isNotEmpty() && restaurants.all { it.walkingMinutes != null } -> RouteProviderStatus.AVAILABLE
        restaurants.any { it.walkingMinutes != null } -> RouteProviderStatus.PARTIAL
        else -> RouteProviderStatus.UNAVAILABLE
    }

private fun List<String>.toDietaryPreferences(): Set<DietaryPreference> =
    mapNotNull { enumOrNull<DietaryPreference>(it) }.toSet()

private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
    enumValues<T>().firstOrNull { it.name == value }

/** `java.time` needs API 26 and minSdk is 24. Accepts ISO-8601 UTC with or without milliseconds. */
private fun parseIsoUtc(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { pattern ->
        try {
            SimpleDateFormat(pattern, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                .parse(value)
                ?.time
        } catch (_: ParseException) {
            null
        }
    }
}
