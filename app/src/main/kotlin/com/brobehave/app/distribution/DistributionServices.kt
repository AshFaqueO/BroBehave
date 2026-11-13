package com.brobehave.app.distribution

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** A newer version the user can install themselves (GitHub builds only). */
data class AvailableUpdate(val versionName: String, val downloadUrl: String)

/**
 * What differs between the two distribution channels. Each product flavor provides its
 * own implementation (src/play and src/github):
 *  - play:   Google Play In-App Updates and In-App Review.
 *  - github: a once-a-day check of the GitHub Releases page.
 */
interface DistributionServices {
    /** Non-null when the app should offer a download. Play handles its own update UI, so it stays null there. */
    val availableUpdate: StateFlow<AvailableUpdate?>

    /** Called each time the main screen is shown. Must never throw or block the UI. */
    suspend fun onMainShown(activity: Activity)

    fun dismissUpdate()
}
