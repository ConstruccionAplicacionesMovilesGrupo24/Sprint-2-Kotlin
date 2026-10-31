package com.campusmeal.android.feature.restaurants.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.restaurants.data.RestaurantRepository
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetail
import com.campusmeal.android.feature.restaurants.domain.RestaurantDetailResult
import com.campusmeal.android.feature.restaurants.domain.ResultSource
import com.campusmeal.android.feature.restaurants.domain.RouteEstimateSnapshot
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.outbox.UsageFeature
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RestaurantDetailUiState {

    data object Loading : RestaurantDetailUiState

    /**
     * Fresh restaurant, address and meals from `GET restaurants/:id`, plus the walking estimate from the
     * search the user opened it from. [route] is null when there is no matching search (direct open,
     * restored after process death, or another context): the detail endpoint itself has no origin.
     */
    data class Content(
        val detail: RestaurantDetail,
        val route: DetailRoute?,
    ) : RestaurantDetailUiState

    data class Error(
        val message: String,
    ) : RestaurantDetailUiState

    data object Unauthorized : RestaurantDetailUiState
}

/**
 * The search's estimate as shown on the detail screen. [historical] when the search was a saved copy or
 * is older than the search freshness policy: it is a past value, and a successful detail request never
 * makes it current again.
 */
data class DetailRoute(val estimate: RouteEstimateSnapshot, val historical: Boolean)

/**
 * Bound to the session [scope] it was created for; results of another scope are never shown.
 * [routeEstimate] comes from the selected search row (memory only) and is never recomputed here.
 */
class RestaurantDetailViewModel(
    private val repository: RestaurantRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val restaurantId: String,
    private val routeEstimate: RouteEstimateSnapshot?,
    private val cachePolicy: CachePolicy,
    private val request: RestaurantSearchRequestDto?,
    private val outboxRepository: OutboxRepository,
) : ViewModel() {

    private var loadJob: Job? = null

    private val _uiState =
        MutableStateFlow<RestaurantDetailUiState>(
            RestaurantDetailUiState.Loading,
        )

    val uiState: StateFlow<RestaurantDetailUiState> =
        _uiState.asStateFlow()

    init {
        recordDetailOpening()
        load()
    }

    fun retry() {
        load(forceRefresh = true)
    }

    private fun recordDetailOpening() {
        viewModelScope.launch {
            outboxRepository.enqueueUsage(
                scope = scope,
                feature = UsageFeature.RESTAURANT_DETAIL,
                zoneId = request?.campusId.toAnalyticsZone(),
                foodCategory = request?.foodCategory,
                budgetBand = request?.maximumBudget?.toBudgetBand(),
            )
            }
        }

    private fun load(
            forceRefresh: Boolean = false,
    ) {
        // A retry replaces the previous request; its late answer is dropped.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {

            _uiState.value =
                RestaurantDetailUiState.Loading

            val result =
                repository.getRestaurant(
                    scope,
                    restaurantId,
                    forceRefresh = forceRefresh,
                )

            if (!coordinator.isCurrent(scope)) return@launch

            when (result) {

                is RestaurantDetailResult.Success -> {
                    if (result.detail.summary.id != restaurantId) {
                        _uiState.value = RestaurantDetailUiState.Error(message = "Unable to load restaurant details.")
                        return@launch
                    }
                    _uiState.value =
                        RestaurantDetailUiState.Content(
                            detail = result.detail,
                            route = routeEstimate?.takeIf { it.restaurantId == restaurantId }?.let { estimate ->
                                DetailRoute(estimate, historical = estimate.isHistorical())
                            },
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

    private fun RouteEstimateSnapshot.isHistorical(): Boolean =
        source == ResultSource.CACHE ||
            Freshness.synced(searchedAtEpochMillis, cachePolicy.restaurantSearch) is Freshness.Stale

    companion object {

        fun factory(
            container: AppContainer,
            scope: AccountSessionScope,
            restaurantId: String,
            routeEstimate: RouteEstimateSnapshot?,
            request: RestaurantSearchRequestDto?,
        ): ViewModelProvider.Factory =
            viewModelFactory {

                initializer {
                    RestaurantDetailViewModel(
                        repository =
                            container.restaurantRepository,
                        coordinator =
                            container.sessionCoordinator,
                        scope = scope,
                        restaurantId =
                            restaurantId,
                        routeEstimate = routeEstimate,
                        cachePolicy = container.cachePolicy,
                        request = request,
                        outboxRepository = container.outboxRepository,
                    )
                }
            }
    }
}