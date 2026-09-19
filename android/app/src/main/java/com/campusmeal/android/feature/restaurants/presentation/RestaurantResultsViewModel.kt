package com.campusmeal.android.feature.restaurants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.restaurants.domain.RestaurantResults
import com.campusmeal.android.feature.restaurants.domain.RestaurantSearchResult
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

class RestaurantResultsViewModel(
    private val repository: RestaurantRepository,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<RestaurantResultsUiState>(
            RestaurantResultsUiState.Initial,
        )

    val uiState: StateFlow<RestaurantResultsUiState> =
        _uiState.asStateFlow()

    private var lastRequest: RestaurantSearchRequestDto? = null

    fun search(
        request: RestaurantSearchRequestDto,
    ) {
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

            when (
                val result =
                    repository.search(request)
            ) {

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
        ): ViewModelProvider.Factory =
            viewModelFactory {

                initializer {
                    RestaurantResultsViewModel(
                        repository =
                            container.restaurantRepository,
                    )
                }
            }
    }
}