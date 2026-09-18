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

    init {
        loadInventory()
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