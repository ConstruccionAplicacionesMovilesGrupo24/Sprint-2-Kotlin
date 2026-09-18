package com.campusmeal.android.feature.context.data.remote

import com.campusmeal.android.feature.context.domain.MealContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable

@Serializable
data class RestaurantSearchRequestDto(
    val location: RestaurantSearchLocationDto,
    val campusId: String?,
    val availableMinutes: Int,
    val maximumBudget: Long,
    val dietaryPreferences: List<String>,
    val includeDelivery: Boolean,
    val requestedAt: String,
)

@Serializable
data class RestaurantSearchLocationDto(
    val latitude: Double,
    val longitude: Double,
)

fun MealContext.toRestaurantSearchRequestDto(
    requestedAtEpochMillis: Long,
): RestaurantSearchRequestDto =
    RestaurantSearchRequestDto(
        location = RestaurantSearchLocationDto(
            latitude = location.coordinates.latitude,
            longitude = location.coordinates.longitude,
        ),
        campusId = location.campusId,
        availableMinutes = availableMinutes,
        maximumBudget = maximumBudget,
        dietaryPreferences = dietaryPreferences
            .map { it.name }
            .sorted(),
        includeDelivery = includeDelivery,
        requestedAt = requestedAtEpochMillis.toIsoUtc(),
    )

private fun Long.toIsoUtc(): String =
    SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(this))