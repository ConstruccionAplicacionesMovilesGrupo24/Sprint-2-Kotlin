package com.campusmeal.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campusmeal.android.app.CampusMealApp
import com.campusmeal.android.core.designsystem.CampusMealTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusMealAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appShellShowsFoundationPlaceholder() {
        val expected = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.foundation_ready)

        composeRule.setContent {
            CampusMealTheme {
                CampusMealApp()
            }
        }

        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
