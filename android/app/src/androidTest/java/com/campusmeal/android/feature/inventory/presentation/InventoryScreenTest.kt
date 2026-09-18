package com.campusmeal.android.feature.inventory.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.campusmeal.android.core.common.UiState
import com.campusmeal.android.core.designsystem.CampusMealTheme
import org.junit.Rule
import org.junit.Test

class InventoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun urgentItemIsDisplayedWithAccessibleUrgencyText() {
        val data = InventoryScreenData(
            today = listOf(
                InventoryItemUi(
                    id = "milk-1",
                    name = "Milk",
                    quantityLabel = "1 L",
                    expirationDate = "2026-09-17",
                    remainingDays = 0,
                    urgency = InventoryUrgency.TODAY,
                ),
            ),
            soon = emptyList(),
            later = emptyList(),
        )

        composeRule.setContent {
            CampusMealTheme {
                InventoryScreen(
                    state = UiState.Content(data),
                    onRetry = {},
                    onAddItem = {},
                    onEditItem = {},
                    onConsumeItem = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Milk")
            .assertIsDisplayed()

        composeRule
            .onAllNodesWithText("Due today")
            .assertCountEquals(2)

        composeRule
            .onNodeWithText("1 L · Expires 2026-09-17")
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Consume")
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Edit")
            .assertIsDisplayed()
    }

    @Test
    fun emptyInventoryStateIsDisplayed() {
        composeRule.setContent {
            CampusMealTheme {
                InventoryScreen(
                    state = UiState.Empty,
                    onRetry = {},
                    onAddItem = {},
                    onEditItem = {},
                    onConsumeItem = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Your inventory is empty")
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Add item")
            .assertIsDisplayed()
    }
}