package com.brobehave.feature.onboarding

import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.DataError
import com.brobehave.core.data.DataException
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.model.OnboardingInput
import com.brobehave.core.model.Visibility
import com.brobehave.core.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    private val profiles = mockk<ProfileRepository>()
    private val auth = mockk<AuthRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), ZoneId.of("UTC"))

    private fun viewModel() = SetupViewModel(profiles, auth, clock)

    @Test
    fun `username is cleaned and too-short names are not checked`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onUsernameChange("R@")
        advanceUntilIdle()

        assertEquals("r", vm.state.value.username)
        assertEquals(UsernameStatus.INVALID, vm.state.value.usernameStatus)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun `availability is checked after typing stops`() = runTest(dispatcher) {
        coEvery { profiles.isUsernameAvailable("rafi") } returns Result.success(false)
        coEvery { profiles.isUsernameAvailable("rafi_99") } returns Result.success(true)
        val vm = viewModel()

        vm.onUsernameChange("Rafi")
        advanceUntilIdle()
        assertEquals(UsernameStatus.TAKEN, vm.state.value.usernameStatus)

        vm.onUsernameChange("rafi_99")
        advanceUntilIdle()
        assertEquals(UsernameStatus.AVAILABLE, vm.state.value.usernameStatus)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `streak start is limited to the last 365 days`() = runTest(dispatcher) {
        val vm = viewModel()
        val today = vm.state.value.today

        vm.onStreakStartChange(today.minusDays(900))
        assertEquals(today.minusDays(365), vm.state.value.streakStart)

        vm.onStreakStartChange(today.plusDays(3))
        assertEquals(today, vm.state.value.streakStart)
    }

    @Test
    fun `submit sends the chosen settings and today means no explicit start`() = runTest(dispatcher) {
        coEvery { profiles.isUsernameAvailable(any()) } returns Result.success(true)
        val sent = slot<OnboardingInput>()
        coEvery { profiles.completeOnboarding(capture(sent)) } returns Result.success(Unit)
        val vm = viewModel()

        vm.onUsernameChange("rafi")
        vm.onShowRelapsesChange(false)
        vm.onSlipChoiceChange(SlipChoice.SELF_REPORT)
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()

        assertEquals("rafi", sent.captured.username)
        assertNull(sent.captured.streakStart)
        assertTrue(sent.captured.selfReport)
        assertEquals(Visibility.PRIVATE, sent.captured.showRelapses)
        assertEquals(Visibility.FRIENDS, sent.captured.showStreak)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a username taken at submit time is shown on the field`() = runTest(dispatcher) {
        coEvery { profiles.isUsernameAvailable(any()) } returns Result.success(true)
        coEvery { profiles.completeOnboarding(any()) } returns Result.failure(DataException(DataError.USERNAME_TAKEN))
        val vm = viewModel()

        vm.onUsernameChange("rafi")
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()

        assertEquals(UsernameStatus.TAKEN, vm.state.value.usernameStatus)
        assertFalse(vm.state.value.submitting)
    }
}
