package com.campusmeal.android.feature.restaurants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.restaurants.domain.RestaurantResults
import com.campusmeal.android.feature.restaurants.domain.RestaurantSearchResult
import com.campusmeal.android.feature.restaurants.domain.RouteEstimateSnapshot
import com.campusmeal.android.feature.restaurants.domain.ResultSource
import com.campusmeal.android.feature.restaurants.domain.RouteProviderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RestaurantResultsUiState {

    data object Initial : RestaurantResultsUiState

    data object Loading : RestaurantResultsUiState

    data class Content(
        val results: RestaurantResults,
    ) : RestaurantResultsUiState

    data class RoutesUnavailableWithPartialResults(
        val results: RestaurantResults,
    ) : RestaurantResultsUiState

    data class OfflineWithCache(
        val results: RestaurantResults,
    ) : RestaurantResultsUiState

    data object NoResults : RestaurantResultsUiState

    data class Error(
        val message: String,
    ) : RestaurantResultsUiState

    data object Unauthorized : RestaurantResultsUiState
}

/**
 * A restaurant opened from the results on screen: its route estimate plus the exact validated request
 * that produced it, so the detail screen can check it still matches the current search context. Kept
 * in memory only (the request contains coordinates).
 */
data class SelectedRoute(
    val scope: AccountSessionScope,
    val request: RestaurantSearchRequestDto,
    val estimate: RouteEstimateSnapshot,
)

/** Bound to the session [scope] it was created for; results of another scope are never shown. */
class RestaurantResultsViewModel(
    private val repository: RestaurantRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<RestaurantResultsUiState>(
            RestaurantResultsUiState.Initial,
        )

    val uiState: StateFlow<RestaurantResultsUiState> =
        _uiState.asStateFlow()

    private var lastRequest: RestaurantSearchRequestDto? = null

    /** The request whose results are on screen; null while none are. */
    private var shownRequest: RestaurantSearchRequestDto? = null

    /**
     * The estimate [restaurantId] has in the results currently shown, for the detail screen. Null when
     * no successful search is shown or the restaurant is not in it.
     */
    fun routeFor(restaurantId: String): SelectedRoute? {
        val request = shownRequest ?: return null
        val results = when (val state = _uiState.value) {
            is RestaurantResultsUiState.Content -> state.results
            is RestaurantResultsUiState.RoutesUnavailableWithPartialResults -> state.results
            is RestaurantResultsUiState.OfflineWithCache -> state.results
            else -> return null
        }
        // Judged per restaurant: a PARTIAL search can still hold a valid estimate for this one.
        val restaurant = results.restaurants.firstOrNull { it.id == restaurantId } ?: return null
        return SelectedRoute(
            scope = scope,
            request = request,
            estimate = RouteEstimateSnapshot(
                restaurantId = restaurant.id,
                walkingMinutes = restaurant.walkingMinutes,
                estimatedTotalMinutes = restaurant.estimatedTotalMinutes,
                searchedAtEpochMillis = results.lastUpdatedAtEpochMillis,
                source = results.source,
            ),
        )
    }

    fun search(
        request: RestaurantSearchRequestDto,
    ) {
        // Coming back from a detail recomposes the route with the same request: keep its results and
        // their time. A new search always has a new `requestedAt`, and retry() re-runs explicitly.
        if (request == lastRequest) return
        lastRequest = request
        load(request)
    }

    fun retry() {
        val request = lastRequest ?: return
        load(request)
    }

    private fun load(
        request: RestaurantSearchRequestDto,
    ) {
        viewModelScope.launch {

            _uiState.value =
                RestaurantResultsUiState.Loading
            shownRequest = null

            val result =
                repository.search(scope, request)

            if (!coordinator.isCurrent(scope)) return@launch
            // A newer search replaced this one: its late answer must not be shown (or handed to detail).
            if (request != lastRequest) return@launch
            if (result is RestaurantSearchResult.Success) shownRequest = request

            when (result) {

                is RestaurantSearchResult.Success -> {

                    val results = result.results

                    if (results.restaurants.isEmpty()) {
                        _uiState.value =
                            RestaurantResultsUiState.NoResults

                        return@launch
                    }

                    _uiState.value =
                        when {

                            results.source ==
                                    ResultSource.CACHE -> {

                                RestaurantResultsUiState
                                    .OfflineWithCache(
                                        results = results,
                                    )
                            }

                            results.routeProviderStatus ==
                                    RouteProviderStatus.PARTIAL ||
                                    results.routeProviderStatus ==
                                    RouteProviderStatus.UNAVAILABLE -> {

                                RestaurantResultsUiState
                                    .RoutesUnavailableWithPartialResults(
                                        results = results,
                                    )
                            }

                            else -> {
                                RestaurantResultsUiState.Content(
                                    results = results,
                                )
                            }
                        }
                }

                RestaurantSearchResult.Unauthorized -> {
                    _uiState.value =
                        RestaurantResultsUiState.Unauthorized
                }

                is RestaurantSearchResult.Failure -> {
                    _uiState.value =
                        RestaurantResultsUiState.Error(
                            message =
                                "Unable to load restaurants.",
                        )
                }
            }
        }
    }

    companion object {

        fun factory(
            container: AppContainer,
            scope: AccountSessionScope,
        ): ViewModelProvider.Factory =
            viewModelFactory {

                initializer {
                    RestaurantResultsViewModel(
                        repository =
                            container.restaurantRepository,
                        coordinator =
                            container.sessionCoordinator,
                        scope = scope,
                    )
                }
            }
    }
}