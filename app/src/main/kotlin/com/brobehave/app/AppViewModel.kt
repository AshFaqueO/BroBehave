package com.brobehave.app

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.app.distribution.AvailableUpdate
import com.brobehave.app.distribution.DistributionServices
import com.brobehave.app.push.PushTokenUploader
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.AuthState
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.data.StreakRepository
import com.brobehave.core.model.DetectionGateway
import com.brobehave.core.model.DetectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which top-level part of the app is showing. Derived from session + profile state. */
enum class RootState {
    LOADING,

    /** First run: intro and the 18+ confirmation. */
    INTRO,
    AUTH,

    /** Signed in, profile not set up yet. */
    SETUP,
    MAIN,

    /** Signed in, but the profile has never been loaded and the server can't be reached. */
    UNREACHABLE,
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val streakRepository: StreakRepository,
    private val preferences: AppPreferences,
    private val pushTokenUploader: PushTokenUploader,
    private val detectionGateway: DetectionGateway,
    private val distribution: DistributionServices,
) : ViewModel() {
    private val loadFailed = MutableStateFlow(false)

    val state: StateFlow<RootState> = combine(
        preferences.introCompleted,
        authRepository.state,
        profileRepository.profile,
        loadFailed,
    ) { introCompleted, auth, profile, failed ->
        when (auth) {
            AuthState.Loading -> RootState.LOADING
            AuthState.SignedOut -> if (introCompleted) RootState.AUTH else RootState.INTRO
            is AuthState.SignedIn -> when {
                profile == null || profile.id != auth.userId ->
                    if (failed) RootState.UNREACHABLE else RootState.LOADING
                !profile.isOnboarded -> RootState.SETUP
                else -> RootState.MAIN
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, RootState.LOADING)

    /** True when the Android 13+ notification explainer has not been shown yet. */
    val shouldAskForNotifications: StateFlow<Boolean> = combine(
        preferences.notificationPromptShown,
        state,
    ) { shown, root -> !shown && root == RootState.MAIN }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when onboarding chose automatic detection and its disclosure has not been shown yet. */
    val openDetectionDisclosure: StateFlow<Boolean> = combine(
        preferences.pendingDetectionSetup,
        state,
    ) { pending, root -> pending && root == RootState.MAIN && detectionGateway.available }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A newer version to offer (GitHub builds); Play shows its own update UI. */
    val availableUpdate: StateFlow<AvailableUpdate?> = distribution.availableUpdate

    fun onMainShown(activity: Activity) {
        viewModelScope.launch { distribution.onMainShown(activity) }
    }

    fun dismissUpdate() = distribution.dismissUpdate()

    init {
        viewModelScope.launch {
            authRepository.state.distinctUntilChanged().collectLatest { auth ->
                // Detection belongs to an account: signing out turns it off.
                if (auth is AuthState.SignedOut && detectionGateway.state.value.status == DetectionStatus.ACTIVE) {
                    detectionGateway.stop()
                }
                if (auth is AuthState.SignedIn) loadSignedInUser()
            }
        }
    }

    /** Uploads queued actions and pulls the profile; works from the cache when offline. */
    private suspend fun loadSignedInUser() {
        loadFailed.value = false
        val result = streakRepository.sync()
        if (result.isFailure && profileRepository.profile.first() == null) {
            loadFailed.value = true
        }
        if (result.isSuccess) pushTokenUploader.upload()
    }

    fun retry() {
        viewModelScope.launch { loadSignedInUser() }
    }

    fun completeIntro() {
        viewModelScope.launch { preferences.setIntroCompleted() }
    }

    fun onNotificationPromptHandled() {
        viewModelScope.launch {
            preferences.setNotificationPromptShown()
            pushTokenUploader.upload()
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}
