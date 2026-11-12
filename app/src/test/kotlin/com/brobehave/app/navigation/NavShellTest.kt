package com.brobehave.app.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brobehave.app.R
import com.brobehave.core.designsystem.theme.BroBehaveTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The main shell has exactly two tabs — Feed and Profile — and switches between them. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NavShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun tab(id: Int) = composeRule.onNode(hasText(context.getString(id)) and isSelectable())

    @Before
    fun setUp() {
        composeRule.setContent {
            BroBehaveTheme {
                MainScaffold(feed = { Text("feed page") }, profile = { Text("profile page") })
            }
        }
    }

    @Test
    fun thereAreExactlyTwoMainPages() {
        assertEquals(listOf(MainTab.FEED, MainTab.PROFILE), MainTab.entries.toList())
    }

    @Test
    fun feedIsTheStartTab() {
        tab(R.string.tab_feed).assertIsSelected()
        composeRule.onNodeWithText("feed page").assertIsDisplayed()
    }

    @Test
    fun tappingProfileSwitchesThePage() {
        tab(R.string.tab_profile).performClick()

        tab(R.string.tab_profile).assertIsSelected()
        composeRule.onNodeWithText("profile page").assertIsDisplayed()
    }
}
