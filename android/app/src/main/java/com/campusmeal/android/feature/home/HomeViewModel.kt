package com.campusmeal.android.feature.home

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
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import com.campusmeal.android.feature.inventory.presentation.InventoryItemUi
import com.campusmeal.android.feature.inventory.presentation.toInventoryScreenData
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileRepository
import com.campusmeal.android.feature.profile.data.ProfileSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home's "Expiring soon" block. [cached] is non-null when the items come from the local cache: the
 * time of that last successful sync, and whether it is still within the inventory TTL.
 */
sealed interface ExpiringState {
    data object Loading : ExpiringState
    data class Items(val items: List<InventoryItemUi>, val cached: Freshness.Synced?) : ExpiringState
    data object Unavailable : ExpiringState
}

data class HomeUiState(
    /** Null until `/auth/me` or the stored profile answers, or when neither is available. */
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

/**
 * Bound to the session [scope] it was created for. The profile and the expiring list load in
 * parallel and are published independently, so a profile failure never hides the inventory block.
 */
class HomeViewModel(
    private val profileRepository: ProfileRepository,
    private val inventoryRepository: InventoryRepository,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val cachePolicy: CachePolicy,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var profileFromNetwork = false
    private var profileJob: Job? = null

    fun refresh() {
        refreshProfile()
        viewModelScope.launch {
            _uiState.update { it.copy(expiring = ExpiringState.Loading) }
            val expiringState = when (val result = inventoryRepository.getExpiringInventory(scope)) {
                // Backend order is kept; Home shows the first three, like the design.
                is ExpiringInventoryResult.Success -> {
                    val data = result.items.toInventoryScreenData()
                    ExpiringState.Items(
                        items = (data.today + data.soon + data.later).take(HOME_EXPIRING_LIMIT),
                        cached = if (result.source == InventoryDataSource.CACHE) {
                            Freshness.synced(result.lastSyncedAtEpochMillis, cachePolicy.inventory)
                        } else {
                            null
                        },
                    )
                }
                else -> ExpiringState.Unavailable
            }
            if (coordinator.isCurrent(scope)) _uiState.update { it.copy(expiring = expiringState) }
        }
    }

    /** Connectivity came back: refresh the name if it came from the stored copy or was missing. */
    fun onReconnected() {
        if (!profileFromNetwork) refreshProfile()
    }

    private fun refreshProfile() {
        if (profileJob?.isActive == true) return
        profileJob = viewModelScope.launch {
            val user = profileRepository.currentUser(scope) as? CurrentUserResult.Success ?: return@launch
            if (!coordinator.isCurrent(scope)) return@launch
            profileFromNetwork = user.source == ProfileSource.NETWORK
            _uiState.update { it.copy(userName = user.user.name) }
        }
    }

    companion object {
        private const val HOME_EXPIRING_LIMIT = 3

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.profileRepository,
                    container.inventoryRepository,
                    container.sessionCoordinator,
                    scope,
                    container.cachePolicy,
                )
            }
        }
    }
}
