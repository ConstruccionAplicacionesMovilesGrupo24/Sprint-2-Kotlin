package com.campusmeal.android.core.common

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Cache lifetimes agreed in the Sprint 3 plan (§5.4). They are planning defaults: features read them
 * from the instance in `AppContainer` instead of hard-coding durations, so they can be tuned in one place.
 */
data class CachePolicy(
    val inventory: Duration = 5.minutes,
    val recipeCatalog: Duration = 24.hours,
    val restaurantSearch: Duration = 5.minutes,
    val restaurantDetail: Duration = 30.minutes,
    val analyticsReport: Duration = 15.minutes,
)
