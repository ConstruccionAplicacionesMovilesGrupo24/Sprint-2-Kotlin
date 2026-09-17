package com.campusmeal.android.feature.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.analytics.AnalyticsEvent
import com.campusmeal.android.core.analytics.AnalyticsTracker
import com.campusmeal.android.core.location.LocationProvider
import com.campusmeal.android.core.location.LocationResult
import com.campusmeal.android.feature.context.domain.Campus
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.context.domain.Coordinates
import com.campusmeal.android.feature.context.domain.MealLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Location states of the BQ4 context flow. The form states (`InvalidContext` and the submitted
 * search) belong to `ContextViewModel`, which consumes [LocationContextViewModel.mealLocation].
 */
sealed interface LocationContextUiState {

    /** The contextual feature has not been opened yet. */
    data object Initial : LocationContextUiState

    /** Permission is missing: explain why location helps before showing the system dialog. */
    data object PermissionRequired : LocationContextUiState

    data object Loading : LocationContextUiState

    data class LocationGranted(
        val campus: Campus?,
        val accuracyMeters: Float?,
    ) : LocationContextUiState

    data object PermissionDenied : LocationContextUiState

    data class ManualCampusSelection(
        val campuses: List<Campus>,
        val selected: Campus?,
    ) : LocationContextUiState

    /** Location services are off or no fix was obtained; manual selection is still available. */
    data object Error : LocationContextUiState
}

/**
 * Owns the approximate-location flow for BQ4: permission, a recent fix, and manual campus
 * selection as the fallback. Precise coordinates stay in memory, are never persisted, and never
 * reach an analytics event; only the campus zone does.
 */
class LocationContextViewModel(
    private val locationProvider: LocationProvider,
    private val analyticsTracker: AnalyticsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LocationContextUiState>(LocationContextUiState.Initial)
    val uiState: StateFlow<LocationContextUiState> = _uiState.asStateFlow()

    /** The location part of the search request, or null while no source is resolved yet. */
    private val _mealLocation = MutableStateFlow<MealLocation?>(null)
    val mealLocation: StateFlow<MealLocation?> = _mealLocation.asStateFlow()

    /** Called when the user opens the contextual feature. */
    fun onContextOpened() {
        if (_uiState.value != LocationContextUiState.Initial) return
        if (locationProvider.hasLocationPermission()) requestLocation() else showRationale()
    }

    fun showRationale() {
        _uiState.value = LocationContextUiState.PermissionRequired
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) requestLocation() else onPermissionDenied()
    }

    fun requestLocation() {
        viewModelScope.launch {
            _uiState.value = LocationContextUiState.Loading
            when (val result = locationProvider.currentLocation()) {
                is LocationResult.Available -> onLocationAvailable(result)
                LocationResult.PermissionDenied -> onPermissionDenied()
                LocationResult.Unavailable -> {
                    _uiState.value = LocationContextUiState.Error
                    track("context_location_unavailable")
                }
            }
        }
    }

    fun openCampusSelection() {
        _uiState.value = LocationContextUiState.ManualCampusSelection(
            campuses = CampusCatalog.campuses,
            selected = _mealLocation.value?.campus,
        )
    }

    fun onCampusSelected(campus: Campus) {
        _mealLocation.value = MealLocation.forCampus(campus)
        // Denied and Error keep their banner, as in the design; only the picker state records the choice.
        if (_uiState.value is LocationContextUiState.ManualCampusSelection) {
            _uiState.value = LocationContextUiState.ManualCampusSelection(CampusCatalog.campuses, campus)
        }
        track("context_campus_selected_manually", campus.zone)
    }

    /**
     * Drops the device position once the request has been built. The campus centre, which carries
     * no personal location, is kept so the user can still run another search.
     */
    fun clearPreciseLocation() {
        _mealLocation.value = _mealLocation.value?.withoutDeviceCoordinates()
    }

    private suspend fun onLocationAvailable(result: LocationResult.Available) {
        val coordinates = Coordinates(result.latitude, result.longitude, result.accuracyMeters)
        val campus = CampusCatalog.zoneFor(coordinates)
        _mealLocation.value = MealLocation.fromDevice(coordinates, campus)
        _uiState.value = LocationContextUiState.LocationGranted(campus, result.accuracyMeters)
        analyticsTracker.track(
            AnalyticsEvent(
                name = "context_location_granted",
                properties = mapOf("zone" to (campus?.zone ?: MealLocation.UNKNOWN_ZONE)),
            ),
        )
    }

    private fun onPermissionDenied() {
        _uiState.value = LocationContextUiState.PermissionDenied
        track("context_location_permission_denied")
    }

    /** Analytics properties carry a coarse zone only; coordinates are never attached. */
    private fun track(name: String, zone: String? = null) {
        viewModelScope.launch {
            analyticsTracker.track(
                AnalyticsEvent(name, zone?.let { mapOf("zone" to it) } ?: emptyMap()),
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LocationContextViewModel(container.locationProvider, container.analyticsTracker)
            }
        }
    }
}
