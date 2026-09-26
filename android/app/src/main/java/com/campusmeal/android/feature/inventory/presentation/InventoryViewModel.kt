package com.campusmeal.android.feature.inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.campusmeal.android.app.AppContainer
import com.campusmeal.android.core.common.UiState
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryItemDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryWriteResult
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InventoryViewModel(
    private val repository: InventoryRepository,
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<UiState<InventoryScreenData>>(UiState.Initial)

    val uiState: StateFlow<UiState<InventoryScreenData>> =
        _uiState.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)

    /** Result of the last add/edit/consume that needs the user's attention; null when none. */
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        loadInventory()
    }

    /** Adds an item, or edits [itemId] when it is not null, then reloads the backend list. */
    fun saveItem(itemId: String?, draft: InventoryItemDraft) {
        viewModelScope.launch {
            val result = if (itemId == null) repository.addItem(draft) else repository.updateItem(itemId, draft)
            afterWrite(result, success = if (itemId == null) "Item added" else "Item updated")
        }
    }

    fun consumeItem(itemId: String) {
        viewModelScope.launch {
            afterWrite(repository.consumeItem(itemId), success = "Item marked as consumed")
        }
    }

    fun dismissActionMessage() {
        _actionMessage.value = null
    }

    private fun afterWrite(result: InventoryWriteResult, success: String) {
        _actionMessage.value = when (result) {
            InventoryWriteResult.Success -> success
            InventoryWriteResult.Unauthorized -> "Session expired. Sign in again."
            is InventoryWriteResult.Failure -> when (val error = result.error) {
                is InventoryError.BackendUnavailable -> "No connection. The change was not saved."
                is InventoryError.Http -> if (error.code == 404) "Item not found" else "The change was rejected (HTTP ${error.code})"
                InventoryError.InvalidResponse -> "Unexpected server response"
            }
        }
        if (result == InventoryWriteResult.Success) loadInventory()
    }

    fun loadInventory() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading

            when (val result = repository.getExpiringInventory()) {

                is ExpiringInventoryResult.Success -> {
                    if (result.items.isEmpty()) {
                        _uiState.value = UiState.Empty
                        return@launch
                    }

                    val data = result.items.toInventoryScreenData()

                    _uiState.value =
                        if (result.source == InventoryDataSource.CACHE) {
                            UiState.OfflineWithCache(
                                data = data,
                                lastUpdatedEpochMillis =
                                    result.lastSyncedAtEpochMillis,
                            )
                        } else {
                            UiState.Content(data)
                        }
                }

                ExpiringInventoryResult.Unauthorized -> {
                    _uiState.value = UiState.Unauthorized
                }

                is ExpiringInventoryResult.Failure -> {
                    _uiState.value = UiState.Error(
                        message = "Unable to load inventory",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InventoryViewModel(container.inventoryRepository)
            }
        }
    }
}