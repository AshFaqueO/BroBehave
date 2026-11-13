package com.brobehave.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.DataError
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.data.dataError
import com.brobehave.core.model.FeatureFlags
import com.brobehave.core.model.OnboardingInput
import com.brobehave.core.model.Visibility
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UsernameStatus { EMPTY, INVALID, CHECKING, AVAILABLE, TAKEN, UNKNOWN }

enum class SlipChoice { SELF_REPORT, AUTO_DETECTION, LATER }

data class SetupUiState(
    val username: String = "",
    val usernameStatus: UsernameStatus = UsernameStatus.EMPTY,
    val displayName: String = "",
    val today: LocalDate,
    val streakStart: LocalDate,
    val showStreak: Boolean = true,
    val showRelapses: Boolean = true,
    val showTier: Boolean = true,
    val slipChoice: SlipChoice = SlipChoice.LATER,
    val timezone: String,
    /** False when automatic detection is switched off for this build. */
    val autoDetectionAvailable: Boolean = false,
    val submitting: Boolean = false,
    val error: DataError? = null,
) {
    val earliestStreakStart: LocalDate get() = today.minusDays(MAX_BACKDATE_DAYS)

    val canSubmit: Boolean
        get() = !submitting &&
            (usernameStatus == UsernameStatus.AVAILABLE || usernameStatus == UsernameStatus.UNKNOWN)

    companion object {
        const val MAX_BACKDATE_DAYS = 365L
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val preferences: AppPreferences,
    featureFlags: FeatureFlags,
    clock: Clock,
) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.now(clock.withZone(zone))

    private val _state = MutableStateFlow(
        SetupUiState(
            today = today,
            streakStart = today,
            timezone = zone.id,
            autoDetectionAvailable = featureFlags.autoDetection,
        ),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var usernameCheck: Job? = null

    fun onUsernameChange(value: String) {
        val cleaned = value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(USERNAME_MAX)
        usernameCheck?.cancel()
        val status = when {
            cleaned.isEmpty() -> UsernameStatus.EMPTY
            cleaned.length < USERNAME_MIN -> UsernameStatus.INVALID
            else -> UsernameStatus.CHECKING
        }
        _state.update { it.copy(username = cleaned, usernameStatus = status, error = null) }
        if (status == UsernameStatus.CHECKING) {
            usernameCheck = viewModelScope.launch {
                delay(USERNAME_DEBOUNCE_MS)
                val result = profileRepository.isUsernameAvailable(cleaned)
                _state.update { current ->
                    if (current.username != cleaned) return@update current
                    current.copy(
                        usernameStatus = result.fold(
                            onSuccess = { if (it) UsernameStatus.AVAILABLE else UsernameStatus.TAKEN },
                            // Offline: let the server decide on submit.
                            onFailure = { UsernameStatus.UNKNOWN },
                        ),
                    )
                }
            }
        }
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value.take(DISPLAY_NAME_MAX)) }

    fun onStreakStartChange(date: LocalDate) = _state.update {
        it.copy(streakStart = date.coerceIn(it.earliestStreakStart, it.today))
    }

    fun onShowStreakChange(value: Boolean) = _state.update { it.copy(showStreak = value) }

    fun onShowRelapsesChange(value: Boolean) = _state.update { it.copy(showRelapses = value) }

    fun onShowTierChange(value: Boolean) = _state.update { it.copy(showTier = value) }

    fun onSlipChoiceChange(value: SlipChoice) = _state.update { it.copy(slipChoice = value) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val input = OnboardingInput(
                username = current.username,
                displayName = current.displayName.trim().ifEmpty { null },
                timezone = current.timezone,
                // "Today" means now; an earlier date starts at that day's local midnight.
                streakStart = current.streakStart.takeIf { it != current.today }
                    ?.atStartOfDay(zone)?.toInstant()?.toString(),
                selfReport = current.slipChoice == SlipChoice.SELF_REPORT,
                showStreak = current.showStreak.toVisibility(),
                showRelapses = current.showRelapses.toVisibility(),
                showTier = current.showTier.toVisibility(),
            )
            // Choosing automatic detection only queues the disclosure screen; nothing is
            // turned on until the user accepts it there.
            if (current.slipChoice == SlipChoice.AUTO_DETECTION) preferences.setPendingDetectionSetup(true)
            val result = profileRepository.completeOnboarding(input)
            _state.update { state ->
                val error = result.exceptionOrNull()?.dataError()
                state.copy(
                    submitting = false,
                    error = error,
                    usernameStatus = if (error ==
                        DataError.USERNAME_TAKEN
                    ) {
                        UsernameStatus.TAKEN
                    } else {
                        state.usernameStatus
                    },
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private fun Boolean.toVisibility() = if (this) Visibility.FRIENDS else Visibility.PRIVATE

    private companion object {
        const val USERNAME_MIN = 3
        const val USERNAME_MAX = 20
        const val DISPLAY_NAME_MAX = 40
        const val USERNAME_DEBOUNCE_MS = 400L
    }
}
