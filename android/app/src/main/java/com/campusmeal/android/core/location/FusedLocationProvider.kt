package com.campusmeal.android.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun hasLocationPermission(): Boolean =
        LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun currentLocation(): LocationResult {
        if (!hasLocationPermission()) return LocationResult.PermissionDenied
        return try {
            // A recent cached fix is accurate enough for BQ4 and answers immediately; only ask for
            // a new one when there is none. The wait is bounded because Play Services leaves the
            // request pending indefinitely when no provider ever answers, which a device with
            // location switched off and a stock emulator both do; the caller must not hang on it.
            withTimeoutOrNull(LOCATION_TIMEOUT) {
                recentLocation()?.toResult() ?: freshLocation()?.toResult()
            } ?: LocationResult.Unavailable
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the request.
            LocationResult.PermissionDenied
        }
    }

    @SuppressLint("MissingPermission") // Guarded by currentLocation(): permission check plus SecurityException catch.
    private suspend fun recentLocation(): Location? =
        client.lastLocation.awaitOrNull()?.takeIf { it.isRecent() }

    @SuppressLint("MissingPermission") // Guarded by currentLocation(): permission check plus SecurityException catch.
    private suspend fun freshLocation(): Location? {
        val cancellation = CancellationTokenSource()
        return client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
            .awaitOrNull { cancellation.cancel() }
    }

    /** Resumes with null on failure or cancellation: an absent fix is [LocationResult.Unavailable]. */
    private suspend fun <T> Task<T>.awaitOrNull(onCancellation: () -> Unit = {}): T? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { onCancellation() }
            addOnSuccessListener { continuation.resume(it) }
            addOnFailureListener { continuation.resume(null) }
            addOnCanceledListener { continuation.resume(null) }
        }

    private fun Location.isRecent(): Boolean =
        SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos <= MAX_LOCATION_AGE.inWholeNanoseconds

    private fun Location.toResult(): LocationResult.Available = LocationResult.Available(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
    )

    companion object {
        /** Older than this and the cached fix is refreshed instead of reused. */
        private val MAX_LOCATION_AGE = 5.minutes

        /** Past this the request is given up on and reported as [LocationResult.Unavailable]. */
        private val LOCATION_TIMEOUT = 8.seconds

        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
