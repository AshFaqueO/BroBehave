package com.brobehave.feature.auth

import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.DataError
import com.brobehave.core.data.DataException
import com.brobehave.core.data.SignUpOutcome
import com.brobehave.core.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class AuthViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val auth = mockk<AuthRepository> { every { googleWebClientId } returns null }

    private fun viewModel() = AuthViewModel(auth).apply {
        onEmailChange("rafi@example.com")
        onPasswordChange("password123")
    }

    @Test
    fun `submit is blocked until the email and password look valid`() {
        val vm = AuthViewModel(auth)
        assertFalse(vm.state.value.canSubmit)

        vm.onEmailChange("rafi@example.com")
        vm.onPasswordChange("short")
        assertFalse(vm.state.value.canSubmit)

        vm.onPasswordChange("password123")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun `wrong credentials are reported and loading stops`() = runTest {
        coEvery { auth.signIn(any(), any()) } returns Result.failure(DataException(DataError.INVALID_CREDENTIALS))
        val vm = viewModel()

        vm.submit()

        assertEquals(DataError.INVALID_CREDENTIALS, vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `sign up that needs email confirmation returns to sign in with a notice`() = runTest {
        coEvery { auth.signUp(any(), any()) } returns Result.success(SignUpOutcome.CONFIRM_EMAIL)
        val vm = viewModel()
        vm.onModeChange(AuthMode.SIGN_UP)

        vm.submit()

        assertEquals(AuthMode.SIGN_IN, vm.state.value.mode)
        assertEquals(AuthNotice.CONFIRM_EMAIL, vm.state.value.notice)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `password reset needs no password and confirms without revealing whether the email exists`() = runTest {
        coEvery { auth.sendPasswordReset("rafi@example.com") } returns Result.success(Unit)
        val vm = AuthViewModel(auth)
        vm.onModeChange(AuthMode.RESET)
        vm.onEmailChange("rafi@example.com")

        vm.submit()

        coVerify { auth.sendPasswordReset("rafi@example.com") }
        assertEquals(AuthNotice.RESET_SENT, vm.state.value.notice)
        assertNull(vm.state.value.error)
    }
}
