package com.brobehave.app.distribution

import android.app.Activity
import com.brobehave.core.data.AppPreferences
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Play flavor: updates and reviews go through Google Play's own in-app flows. */
@Singleton
class PlayDistributionServices @Inject constructor(private val preferences: AppPreferences) : DistributionServices {
    override val availableUpdate: StateFlow<AvailableUpdate?> = MutableStateFlow(null)

    override fun dismissUpdate() = Unit

    // Play services may be missing, outdated or offline; none of that may disturb the app.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun onMainShown(activity: Activity) {
        try {
            offerUpdate(activity)
            maybeAskForReview(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not installed from Play (e.g. a sideloaded test build): nothing to do.
        }
    }

    /** Flexible update: downloads in the background, installs when the download is done. */
    private suspend fun offerUpdate(activity: Activity) {
        val manager = AppUpdateManagerFactory.create(activity)
        val info = manager.requestAppUpdateInfo()
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            manager.completeUpdate()
            return
        }
        val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        if (!available) return

        lateinit var listener: InstallStateUpdatedListener
        listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                manager.unregisterListener(listener)
                manager.completeUpdate()
            }
        }
        manager.registerListener(listener)
        manager.startUpdateFlow(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE))
    }

    /**
     * Asks for a rating once, and only after a week of use. Google Play decides whether
     * the dialog actually appears (it has its own quota).
     */
    private suspend fun maybeAskForReview(activity: Activity) {
        val today = LocalDate.now().toEpochDay()
        val usedForDays = today - preferences.firstOpenDay(today)
        if (usedForDays < DAYS_BEFORE_REVIEW || preferences.reviewRequested.first()) return

        val manager = ReviewManagerFactory.create(activity)
        val reviewInfo = manager.requestReview()
        preferences.setReviewRequested()
        manager.launchReview(activity, reviewInfo)
    }

    private companion object {
        const val DAYS_BEFORE_REVIEW = 7L
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface DistributionModule {
    @Binds
    fun bindDistributionServices(impl: PlayDistributionServices): DistributionServices
}
