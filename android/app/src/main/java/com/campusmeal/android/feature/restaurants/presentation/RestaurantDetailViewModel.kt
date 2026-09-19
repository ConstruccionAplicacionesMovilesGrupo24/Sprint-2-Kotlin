package com.campusmeal.android.feature.restaurants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetail
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetailResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RestaurantDetailUiState {

    data object Loading : RestaurantDetailUiState

    data class Content(
        val detail: RestaurantDetail,
    ) : RestaurantDetailUiState

    data class Error(
        val message: String,
    ) : RestaurantDetailUiState

    data object Unauthorized : RestaurantDetailUiState
}

class RestaurantDetailViewModel(
    private val repository: RestaurantRepository,
    private val restaurantId: String,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<RestaurantDetailUiState>(
            RestaurantDetailUiState.Loading,
        )

    val uiState: StateFlow<RestaurantDetailUiState> =
        _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        viewModelScope.launch {

            _uiState.value =
                RestaurantDetailUiState.Loading

            when (
                val result =
                    repository.getRestaurant(
                        restaurantId,
                    )
            ) {

                is RestaurantDetailResult.Success -> {
                    _uiState.value =
                        RestaurantDetailUiState.Content(
                            detail = result.detail,
                        )
                }

                RestaurantDetailResult.Unauthorized -> {
                    _uiState.value =
                        RestaurantDetailUiState.Unauthorized
                }

                is RestaurantDetailResult.Failure -> {
                    _uiState.value =
                        RestaurantDetailUiState.Error(
                            message =
                                "Unable to load restaurant details.",
                        )
                }
            }
        }
    }

    companion object {

        fun factory(
            container: AppContainer,
            restaurantId: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {

                initializer {
                    RestaurantDetailViewModel(
                        repository =
                            container.restaurantRepository,
                        restaurantId =
                            restaurantId,
                    )
                }
            }
    }
}