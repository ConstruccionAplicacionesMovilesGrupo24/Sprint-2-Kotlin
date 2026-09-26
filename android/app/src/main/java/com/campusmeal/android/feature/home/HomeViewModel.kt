package com.campusmeal.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import com.campusmeal.android.feature.inventory.presentation.InventoryItemUi
import com.campusmeal.android.feature.inventory.presentation.toInventoryScreenData
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Home's "Expiring soon" block. [offline] is true when the items come from the local cache. */
sealed interface ExpiringState {
    data object Loading : ExpiringState
    data class Items(val items: List<InventoryItemUi>, val offline: Boolean) : ExpiringState
    data object Unavailable : ExpiringState
}

data class HomeUiState(
    /** Null until `/auth/me` answers, or when it could not be reached. */
    val userName: String? = null,
    val expiring: ExpiringState = ExpiringState.Loading,
)

/** What Home shows about the context the user validated last (kept in memory only). */
data class ContextSummary(val place: String, val availableMinutes: Int, val maximumBudget: Long)

fun RestaurantSearchRequestDto.toContextSummary(): ContextSummary = ContextSummary(
    place = CampusCatalog.campuses.firstOrNull { it.id == campusId }?.name ?: "Your current location",
    availableMinutes = availableMinutes,
    maximumBudget = maximumBudget,
)

class HomeViewModel(
    private val profileRepository: ProfileRepository,
    private val inventoryRepository: InventoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(expiring = ExpiringState.Loading)
            val user = async { profileRepository.currentUser() }
            val expiring = async { inventoryRepository.getExpiringInventory() }

            val name = (user.await() as? CurrentUserResult.Success)?.user?.name
            val expiringState = when (val result = expiring.await()) {
                // Backend order is kept; Home shows the first three, like the design.
                is ExpiringInventoryResult.Success -> {
                    val data = result.items.toInventoryScreenData()
                    ExpiringState.Items(
                        items = (data.today + data.soon + data.later).take(HOME_EXPIRING_LIMIT),
                        offline = result.source == InventoryDataSource.CACHE,
                    )
                }
                else -> ExpiringState.Unavailable
            }
            _uiState.value = HomeUiState(userName = name ?: _uiState.value.userName, expiring = expiringState)
        }
    }

    companion object {
        private const val HOME_EXPIRING_LIMIT = 3

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container.profileRepository, container.inventoryRepository) }
        }
    }
}
