package com.brobehave.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code runs during a cold start, so Android can compile it ahead of time
 * and the first launches after install are faster.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        val packageName = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.brobehave.app"
        rule.collect(packageName = packageName, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}
