package com.campusmeal.android.feature.context.domain

import kotlin.math.cos
import kotlin.math.hypot

/**
 * A campus the user can pick when device location is denied or unavailable.
 *
 * [zone] is the coarse area reported to analytics. Precise coordinates never leave the device
 * through an analytics event, so the zone is the only location detail analytics ever sees.
 */
data class Campus(
    val id: String,
    val name: String,
    val zone: String,
    val latitude: Double,
    val longitude: Double,
)

/** Approximate device position. Kept in memory only; never cached in Room or DataStore. */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)

/** Where [MealLocation.coordinates] came from. */
enum class LocationSource {
    /** An approximate fix from the device. Precise enough to be dropped after the request. */
    DEVICE,

    /** The central point of a manually chosen campus, used when the device gives no fix. */
    CAMPUS_CENTER,
}

/**
 * The location input BQ4 and BQ5 need. Coordinates are always present: a device fix when
 * location works, otherwise the centre of the campus the user picked.
 */
data class MealLocation(
    val coordinates: Coordinates,
    val campus: Campus?,
    val source: LocationSource,
) {
    val campusId: String? get() = campus?.id

    /** Coarse area for analytics. Never build an analytics property from [coordinates]. */
    val analyticsZone: String get() = campus?.zone ?: UNKNOWN_ZONE

    /**
     * Drops the device position once the request no longer needs it, falling back to the campus
     * centre. Returns null when there is no campus to fall back to.
     */
    fun withoutDeviceCoordinates(): MealLocation? =
        if (source == LocationSource.CAMPUS_CENTER) this else campus?.let(::forCampus)

    companion object {
        const val UNKNOWN_ZONE = "unknown"

        fun fromDevice(coordinates: Coordinates, campus: Campus?) =
            MealLocation(coordinates, campus, LocationSource.DEVICE)

        fun forCampus(campus: Campus) = MealLocation(
            coordinates = Coordinates(campus.latitude, campus.longitude, accuracyMeters = null),
            campus = campus,
            source = LocationSource.CAMPUS_CENTER,
        )
    }
}

/**
 * Provisional campus list for the prototype. Replace it with the backend campus endpoint
 * once that contract exists; the ids are the `campusId` values the search request sends.
 */
object CampusCatalog {

    val campuses: List<Campus> = listOf(
        Campus("campus-001", "Uniandes", "bogota-centro", 4.6025, -74.0653),
        Campus("campus-002", "Javeriana", "bogota-chapinero", 4.6281, -74.0645),
        Campus("campus-003", "Nacional", "bogota-teusaquillo", 4.6360, -74.0830),
    )

    /** The campus a position belongs to, or null when it is farther than [MAX_ZONE_RADIUS_METERS]. */
    fun zoneFor(coordinates: Coordinates): Campus? = campuses
        .minByOrNull { distanceMeters(coordinates, it) }
        ?.takeIf { distanceMeters(coordinates, it) <= MAX_ZONE_RADIUS_METERS }

    private fun distanceMeters(from: Coordinates, to: Campus): Double {
        val latitudeRadians = Math.toRadians(from.latitude)
        val deltaLatitude = Math.toRadians(to.latitude - from.latitude)
        val deltaLongitude = Math.toRadians(to.longitude - from.longitude) * cos(latitudeRadians)
        return EARTH_RADIUS_METERS * hypot(deltaLatitude, deltaLongitude)
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Beyond this the user is not on a known campus, so the analytics zone stays unknown. */
    private const val MAX_ZONE_RADIUS_METERS = 5_000.0
}
