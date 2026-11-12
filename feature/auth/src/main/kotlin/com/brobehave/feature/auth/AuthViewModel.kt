package com.brobehave.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.DataError
import com.brobehave.core.data.SignUpOutcome
import com.brobehave.core.data.dataError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SIGN_IN, SIGN_UP, RESET }

enum class AuthNotice { RESET_SENT, CONFIRM_EMAIL, GOOGLE_FAILED }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: DataError? = null,
    val notice: AuthNotice? = null,
    /** Null when Google Sign-In is not configured for this build. */
    val googleWebClientId: String? = null,
) {
    private val emailLooksValid: Boolean get() = email.contains('@') && email.contains('.')

    val canSubmit: Boolean
        get() = !loading && emailLooksValid && (mode == AuthMode.RESET || password.length >= MIN_PASSWORD)

    companion object {
        const val MIN_PASSWORD = 8
    }
}

/**
 * Signing in successfully needs no callback: the session state changes and the app's
 * root navigation moves on by itself.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(private val authRepository: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState(googleWebClientId = authRepository.googleWebClientId))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value.trim(), error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onModeChange(mode: AuthMode) = _state.update { it.copy(mode = mode, error = null, notice = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch {
            when (current.mode) {
                AuthMode.SIGN_IN -> finish(authRepository.signIn(current.email, current.password))
                AuthMode.SIGN_UP -> {
                    val result = authRepository.signUp(current.email, current.password)
                    val needsConfirmation = result.getOrNull() == SignUpOutcome.CONFIRM_EMAIL
                    finish(result, notice = AuthNotice.CONFIRM_EMAIL.takeIf { needsConfirmation })
                    if (needsConfirmation) _state.update { it.copy(mode = AuthMode.SIGN_IN, password = "") }
                }
                AuthMode.RESET -> finish(
                    authRepository.sendPasswordReset(current.email),
                    notice = AuthNotice.RESET_SENT,
                )
            }
        }
    }

    fun onGoogleToken(idToken: String, rawNonce: String) {
        _state.update { it.copy(loading = true, error = null, notice = null) }
        viewModelScope.launch { finish(authRepository.signInWithGoogle(idToken, rawNonce)) }
    }

    fun onGoogleFailed() = _state.update { it.copy(loading = false, notice = AuthNotice.GOOGLE_FAILED) }

    private fun finish(result: Result<*>, notice: AuthNotice? = null) {
        val error = result.exceptionOrNull()?.dataError()
        _state.update { it.copy(loading = false, error = error, notice = notice.takeIf { error == null }) }
    }
}
