package com.brobehave.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.data.DataError
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.data.SocialRepository
import com.brobehave.core.data.StreakRepository
import com.brobehave.core.data.dataError
import com.brobehave.core.designsystem.component.DayStatus
import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.Profile
import com.brobehave.core.model.Rank
import com.brobehave.core.model.Reactor
import com.brobehave.core.model.StreakHistory
import com.brobehave.core.model.StreakMath
import com.brobehave.core.model.StreakSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileDialog { RELAPSE, SLIP }

data class ProfileUiState(
    val profile: Profile? = null,
    val snapshot: StreakSnapshot? = null,
    val today: LocalDate = LocalDate.MIN,
    val streakStartDate: LocalDate = LocalDate.MIN,
    val history: StreakHistory = StreakHistory(),
    val posts: List<FeedPost> = emptyList(),
    val dialog: ProfileDialog? = null,
    /** A streak rank reached since the last celebration, to celebrate now. */
    val celebration: Rank? = null,
    val slipAlreadyFlagged: Boolean = false,
    val refreshing: Boolean = false,
    val error: DataError? = null,
    val showBadges: Boolean = false,
    /** Reactors of the post whose sheet is open: null = closed, empty while loading is [reactorsLoading]. */
    val reactors: List<Reactor>? = null,
    val reactorsOpen: Boolean = false,
) {
    fun dayStatus(date: LocalDate): DayStatus {
        val key = date.toString()
        return when {
            key in history.relapseDates -> DayStatus.RELAPSE
            key in history.flaggedDates -> DayStatus.FLAGGED
            !date.isBefore(streakStartDate) && !date.isAfter(today) -> DayStatus.CLEAN
            else -> DayStatus.NONE
        }
    }
}

private data class Transient(
    val posts: List<FeedPost> = emptyList(),
    val dialog: ProfileDialog? = null,
    val slipAlreadyFlagged: Boolean = false,
    val refreshing: Boolean = false,
    val error: DataError? = null,
    val showBadges: Boolean = false,
    val reactors: List<Reactor>? = null,
    val reactorsOpen: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val streakRepository: StreakRepository,
    private val socialRepository: SocialRepository,
    private val preferences: AppPreferences,
    private val clock: Clock,
) : ViewModel() {
    private val transient = MutableStateFlow(Transient())

    /** Re-emits every minute so the day counter rolls over at local midnight without a refresh. */
    private val ticker = flow {
        while (true) {
            emit(clock.instant())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        streakRepository.observeHistory(),
        ticker,
        preferences.celebratedRank,
        transient,
    ) { profile, history, now, celebrated, extra ->
        if (profile == null) return@combine ProfileUiState(refreshing = extra.refreshing, error = extra.error)
        val zone = StreakMath.zoneOf(profile.timezone)
        val snapshot = StreakSnapshot.of(profile, history.flaggedDates.map(LocalDate::parse), now)
        val streakRank = snapshot.tier.streakRank
        ProfileUiState(
            profile = profile,
            snapshot = snapshot,
            today = StreakMath.localDate(now, zone),
            streakStartDate = StreakMath.localDate(StreakMath.parseInstant(profile.streakStart), zone),
            history = history,
            posts = extra.posts,
            dialog = extra.dialog,
            celebration = streakRank.takeIf { celebrated in 0 until it.ordinal },
            slipAlreadyFlagged = extra.slipAlreadyFlagged,
            refreshing = extra.refreshing,
            error = extra.error,
            showBadges = extra.showBadges,
            reactors = extra.reactors,
            reactorsOpen = extra.reactorsOpen,
        ).also { syncCelebrationBaseline(celebrated, streakRank) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfileUiState())

    init {
        refresh()
    }

    /**
     * First run, or after a relapse lowered the rank: remember the current rank without
     * celebrating, so only genuine climbs from here are celebrated.
     */
    private fun syncCelebrationBaseline(celebrated: Int, streakRank: Rank) {
        if (celebrated < 0 || celebrated > streakRank.ordinal) {
            viewModelScope.launch { preferences.setCelebratedRank(streakRank.ordinal) }
        }
    }

    fun refresh() {
        transient.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val sync = streakRepository.sync()
            val userId = profileRepository.profile.first()?.id
            val posts = userId?.let { socialRepository.userPosts(it) }
            transient.update {
                it.copy(
                    refreshing = false,
                    posts = posts?.getOrNull() ?: it.posts,
                    // Being offline is normal for this page; only surface other failures.
                    error = sync.exceptionOrNull()?.dataError()?.takeIf { e -> e != DataError.NETWORK },
                )
            }
        }
    }

    fun openDialog(dialog: ProfileDialog) = transient.update { it.copy(dialog = dialog, slipAlreadyFlagged = false) }

    fun closeDialog() = transient.update { it.copy(dialog = null) }

    fun confirmRelapse(note: String) {
        closeDialog()
        viewModelScope.launch { streakRepository.reportRelapse(note) }
    }

    fun confirmSlip(note: String) {
        closeDialog()
        viewModelScope.launch {
            val added = streakRepository.reportSlip(note)
            transient.update { it.copy(slipAlreadyFlagged = !added) }
        }
    }

    fun setSelfReport(enabled: Boolean) {
        viewModelScope.launch {
            val result = profileRepository.setSelfReportEnabled(enabled)
            transient.update { it.copy(error = result.exceptionOrNull()?.dataError()) }
        }
    }

    fun dismissCelebration() {
        val rank = state.value.celebration ?: return
        viewModelScope.launch { preferences.setCelebratedRank(rank.ordinal) }
    }

    fun setBadgesVisible(visible: Boolean) = transient.update { it.copy(showBadges = visible) }

    fun openReactors(post: FeedPost) {
        transient.update { it.copy(reactorsOpen = true, reactors = null) }
        viewModelScope.launch {
            val reactors = socialRepository.reactors(post.id).getOrDefault(emptyList())
            transient.update { if (it.reactorsOpen) it.copy(reactors = reactors) else it }
        }
    }

    fun closeReactors() = transient.update { it.copy(reactorsOpen = false, reactors = null) }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
