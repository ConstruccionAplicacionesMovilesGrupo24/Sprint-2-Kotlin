package com.campusmeal.android.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.outbox.OutboxItem
import com.campusmeal.android.core.outbox.OutboxRepository
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SynchronizationCenterState(
    val counts: Map<String, Int> = emptyMap(),
    val open: List<OutboxItem> = emptyList(),
    val sent: List<OutboxItem> = emptyList(),
    val drafts: List<InventoryDraft> = emptyList(),
    val consumes: List<PendingConsume> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = open.isEmpty() && sent.isEmpty() && drafts.isEmpty() && consumes.isEmpty()
}

/**
 * Local work of one account (S4-10), bound to one session [scope]. It only reads projections and bounded
 * lists. Events can be retried because they are idempotent; inventory writes and comparisons cannot be
 * replayed safely, so they are shown and resolved on their own screens, never sent from here.
 */
class SynchronizationCenterViewModel(
    private val outbox: OutboxRepository,
    inventory: InventoryRepository,
    coordinator: AccountSessionCoordinator,
    private val scope: AccountSessionScope,
) : ViewModel() {

    val state: StateFlow<SynchronizationCenterState> =
        combine(
            outbox.observeCounts(scope.accountId),
            outbox.observeOpen(scope.accountId),
            outbox.observeSent(scope.accountId),
            inventory.observeDrafts(scope),
            inventory.observePendingConsumes(scope),
        ) { counts, open, sent, drafts, consumes ->
            SynchronizationCenterState(counts, open, sent, drafts, consumes, loaded = true)
        }
            .filter { coordinator.isCurrent(scope) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SynchronizationCenterState())

    init {
        viewModelScope.launch { outbox.applyRetention() }
    }

    fun retry(clientEventId: String) {
        viewModelScope.launch { outbox.retry(scope, clientEventId) }
    }

    fun dismiss(clientEventId: String) {
        viewModelScope.launch { outbox.dismiss(scope, clientEventId) }
    }

    fun deliverPending() {
        viewModelScope.launch { outbox.requestDelivery(scope) }
    }

    companion object {
        fun factory(container: AppContainer, scope: AccountSessionScope): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SynchronizationCenterViewModel(
                    container.outboxRepository,
                    container.inventoryRepository,
                    container.sessionCoordinator,
                    scope,
                )
            }
        }
    }
}
