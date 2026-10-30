package com.campusmeal.android.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.BogotaCalendar
import com.campusmeal.android.core.common.CachePolicy
import com.campusmeal.android.core.common.Freshness
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.decision.data.local.MealPlan
import com.campusmeal.android.feature.decision.data.local.MealPlanStore
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.InventoryRefreshResult
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import com.campusmeal.android.feature.inventory.presentation.InventoryItemUi
import com.campusmeal.android.feature.inventory.presentation.toUi
import com.campusmeal.android.feature.profile.data.CurrentUserResult
import com.campusmeal.android.feature.profile.data.ProfileRepository
import com.campusmeal.android.feature.profile.data.ProfileSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home's BQ2 "Expiring soon" block, read from Room first. [Items.syncedAt] is when the server computed
 * the list, with its fresh/stale state against the inventory TTL; `remainingDays` is valid as of that
 * moment. [Items.historical] means it was computed on an earlier Bogota date: it is shown as a past
 * answer, never relabelled as today's. [Items.refreshFailed] means the last refresh could not replace it.
 */
sealed interface ExpiringState {
    data object Loading : ExpiringState
    data class Items(
        val items: List<InventoryItemUi>,
        val syncedAt: Freshness.Synced,
        val historical: Boolean,
        val refreshFailed: Boolean,
    ) : ExpiringState

    /** No BQ2 answer was ever stored for this account and the refresh failed. */
    data object Unavailable : ExpiringState
}

/** The alternative chosen in Decide, while it matters. */
sealed interface PlanState {
    /** Still within the available lunch time. */
    data class Active(val plan: MealPlan) : PlanState

    /** A Cook plan whose time is over: ask whether it was cooked, to mark its ingredients as consumed. */
    data class AskCooked(val plan: MealPlan) : PlanState
}

data class HomeUiState(
    /** Null until `/auth/me` or the stored profile answers, or when neither is available. */
    val userName: String? = null,
    val expiring: ExpiringState = ExpiringState.Loading,
    val plan: PlanState? = null,
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
    private val mealPlans: MealPlanStore,
    private val coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
    private val cachePolicy: CachePolicy,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var profileFromNetwork = false
    private var profileJob: Job? = null
    private var expiringJob: Job? = null

    private val today = MutableStateFlow(BogotaCalendar.today())
    private val expiringRefresh = MutableStateFlow(ExpiringRefresh())
    private val now = MutableStateFlow(currentTimeMillis())

    init {
        viewModelScope.launch {
            combine(inventoryRepository.observeExpiringInventory(scope), today, expiringRefresh) { snapshot, today, refresh ->
                snapshot.toState(today, refresh)
            }.collect { state -> _uiState.update { it.copy(expiring = state) } }
        }
        viewModelScope.launch {
            combine(mealPlans.observe(scope), now) { plan, now -> plan?.let { planState(it, now) } }
                .collect { plan -> _uiState.update { it.copy(plan = plan) } }
        }
        // The plan ends while Home may be open: re-check the time every half minute.
        viewModelScope.launch {
            while (true) {
                delay(PLAN_TICK_MILLIS)
                now.value = currentTimeMillis()
            }
        }
    }

    /** A Walk/Order plan disappears once lunch time is over; a Cook plan then asks whether it was cooked. */
    private suspend fun planState(plan: MealPlan, now: Long): PlanState? = when {
        now < plan.endsAtEpochMillis -> PlanState.Active(plan)
        plan.alternative == "COOK" && plan.ingredients.isNotEmpty() -> PlanState.AskCooked(plan)
        else -> {
            mealPlans.clear(scope, plan.recommendationId)
            null
        }
    }

    /** "I cooked it": marks the plan's ingredients as consumed (offline-safe, like Inventory) and ends it. */
    fun onCooked(online: Boolean) {
        val plan = planOf(_uiState.value.plan) ?: return
        viewModelScope.launch {
            plan.ingredients.forEach { inventoryRepository.consumeItem(scope, it.itemId, it.name, online) }
            mealPlans.clear(scope, plan.recommendationId)
            refreshExpiring(force = true)
        }
    }

    /** "Not this time" or dismiss: ends the plan without touching the inventory. */
    fun onDismissPlan() {
        val plan = planOf(_uiState.value.plan) ?: return
        viewModelScope.launch { mealPlans.clear(scope, plan.recommendationId) }
    }

    private fun planOf(state: PlanState?): MealPlan? = when (state) {
        is PlanState.Active -> state.plan
        is PlanState.AskCooked -> state.plan
        null -> null
    }

    /** Home became visible: profile, plus BQ2 when it is missing, stale, invalidated or from another day. */
    fun refresh() {
        now.value = currentTimeMillis()
        refreshProfile()
        today.value = BogotaCalendar.today()
        refreshExpiring(force = false)
    }

    /** Connectivity came back: refresh the name if it came from the stored copy or was missing. */
    fun onReconnected() {
        if (!profileFromNetwork) refreshProfile()
        refreshExpiring(force = expiringRefresh.value.failed)
    }

    private fun refreshExpiring(force: Boolean) {
        if (expiringJob?.isActive == true) return
        expiringJob = viewModelScope.launch {
            expiringRefresh.update { it.copy(running = true) }
            try {
                val result = inventoryRepository.refreshExpiringInventory(scope, force)
                if (!coordinator.isCurrent(scope)) return@launch
                expiringRefresh.value = ExpiringRefresh(failed = result is InventoryRefreshResult.Failed)
            } finally {
                expiringRefresh.update { it.copy(running = false) }
            }
        }
    }

    // Backend order is kept and remainingDays is shown as the server computed it; Home shows the first three.
    private fun ExpiringInventorySnapshot?.toState(today: String, refresh: ExpiringRefresh): ExpiringState = when {
        this != null -> ExpiringState.Items(
            items = items.take(HOME_EXPIRING_LIMIT).map { it.toUi(daysLeft = it.remainingDays) },
            syncedAt = Freshness.synced(syncedAtEpochMillis, invalidatedAtEpochMillis, cachePolicy.inventory),
            historical = syncedBogotaDate != today,
            refreshFailed = refresh.failed,
        )
        refresh.failed && !refresh.running -> ExpiringState.Unavailable
        else -> ExpiringState.Loading
    }

    private data class ExpiringRefresh(val running: Boolean = false, val failed: Boolean = false)

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
        private const val PLAN_TICK_MILLIS = 30_000L

        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.profileRepository,
                    container.inventoryRepository,
                    container.mealPlanStore,
                    container.sessionCoordinator,
                    scope,
                    container.cachePolicy,
                )
            }
        }
    }
}
