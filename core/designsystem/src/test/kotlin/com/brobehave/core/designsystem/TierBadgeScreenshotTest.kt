package com.brobehave.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brobehave.core.designsystem.component.StreakRing
import com.brobehave.core.designsystem.component.TierBadge
import com.brobehave.core.designsystem.theme.BroBehaveTheme
import com.brobehave.core.model.Rank
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the design system (Roborazzi on Robolectric, no device needed).
 * Record goldens with `recordRoborazziDebug`, check them with `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h900dp-mdpi")
class TierBadgeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rankLadder_dark() = capture(darkTheme = true)

    @Test
    fun rankLadder_light() = capture(darkTheme = false)

    private fun capture(darkTheme: Boolean) {
        composeRule.setContent {
            BroBehaveTheme(darkTheme = darkTheme) {
                Surface {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        StreakRing(streakDays = 26, progress = 0.73f)
                        Rank.entries.forEach { TierBadge(rank = it, verified = it.ordinal % 2 == 0) }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
