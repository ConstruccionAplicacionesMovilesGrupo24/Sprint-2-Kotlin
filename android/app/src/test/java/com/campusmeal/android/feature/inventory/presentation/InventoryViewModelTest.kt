package com.campusmeal.android.feature.inventory.presentation

import com.campusmeal.android.core.common.UiState
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadInventoryTransitionsFromLoadingToContent() =
        runTest(dispatcher) {

            val continueRequest = CompletableDeferred<Unit>()

            val repository = FakeInventoryRepository {
                continueRequest.await()

                ExpiringInventoryResult.Success(
                    items = listOf(
                        InventoryItem(
                            id = "milk-1",
                            name = "Milk",
                            quantity = 1.0,
                            unit = "L",
                            expirationDate = "2026-09-18",
                            remainingDays = 1,
                        ),
                    ),
                    source = InventoryDataSource.NETWORK,
                    lastSyncedAtEpochMillis = 1000L,
                )
            }

            val viewModel = InventoryViewModel(repository)

            runCurrent()

            assertEquals(
                UiState.Loading,
                viewModel.uiState.value,
            )

            continueRequest.complete(Unit)

            advanceUntilIdle()

            val state = viewModel.uiState.value

            assertTrue(state is UiState.Content)

            val content = state as UiState.Content

            assertEquals(
                "Milk",
                content.data.soon.first().name,
            )
        }

    @Test
    fun emptyResponseProducesEmptyState() =
        runTest(dispatcher) {

            val repository = FakeInventoryRepository {
                ExpiringInventoryResult.Success(
                    items = emptyList(),
                    source = InventoryDataSource.NETWORK,
                    lastSyncedAtEpochMillis = 1000L,
                )
            }

            val viewModel = InventoryViewModel(repository)

            advanceUntilIdle()

            assertEquals(
                UiState.Empty,
                viewModel.uiState.value,
            )
        }

    private class FakeInventoryRepository(
        private val result: suspend () -> ExpiringInventoryResult,
    ) : InventoryRepository {

        override suspend fun getExpiringInventory(): ExpiringInventoryResult =
            result()

        override suspend fun clearCache() = Unit
    }
}