package com.brobehave.core.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.functions.functions
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

sealed interface AuthState {
    /** The stored session is still being loaded. */
    data object Loading : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val userId: String) : AuthState
}

enum class SignUpOutcome {
    SIGNED_IN,

    /** The account exists but the email address must be confirmed first. */
    CONFIRM_EMAIL,
}

interface AuthRepository {
    /** Session state; the session is persisted, so this survives app restarts. */
    val state: Flow<AuthState>

    /** Web client ID for Google Sign-In, or null when it is not configured for this build. */
    val googleWebClientId: String?

    suspend fun signUp(email: String, password: String): Result<SignUpOutcome>

    suspend fun signIn(email: String, password: String): Result<Unit>

    /** Signs in with a Google ID token obtained from Credential Manager. */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String?): Result<Unit>

    suspend fun sendPasswordReset(email: String): Result<Unit>

    suspend fun signOut(): Result<Unit>

    /** Deletes the account and all its data on the server, then clears this device. */
    suspend fun deleteAccount(): Result<Unit>
}

internal class SupabaseAuthRepository @Inject constructor(
    private val client: SupabaseClient,
    private val localStore: LocalStore,
) : AuthRepository {
    override val state: Flow<AuthState> = client.auth.sessionStatus
        .map { status ->
            when (status) {
                is SessionStatus.Initializing -> AuthState.Loading
                is SessionStatus.Authenticated -> signedIn(status.session.user?.id)
                // A failed token refresh (e.g. offline) keeps the user signed in locally.
                is SessionStatus.RefreshFailure -> signedIn(client.auth.currentUserOrNull()?.id)
                is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            }
        }
        .distinctUntilChanged()

    override val googleWebClientId: String? =
        com.brobehave.core.network.NetworkConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }

    private fun signedIn(userId: String?): AuthState =
        if (userId == null) AuthState.SignedOut else AuthState.SignedIn(userId)

    override suspend fun signUp(email: String, password: String): Result<SignUpOutcome> = attempt {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        if (client.auth.currentSessionOrNull() == null) SignUpOutcome.CONFIRM_EMAIL else SignUpOutcome.SIGNED_IN
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = attempt {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String?): Result<Unit> = attempt {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            nonce = rawNonce
        }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = attempt {
        client.auth.resetPasswordForEmail(email.trim())
    }

    override suspend fun signOut(): Result<Unit> = attempt {
        client.auth.signOut()
        localStore.clear()
    }

    override suspend fun deleteAccount(): Result<Unit> = attempt {
        client.functions.invoke(DELETE_ACCOUNT_FUNCTION)
        // The server-side session is gone with the user; only local state is left to clear.
        runCatching { client.auth.signOut(SignOutScope.LOCAL) }
        localStore.clear()
    }

    private companion object {
        const val DELETE_ACCOUNT_FUNCTION = "delete-account"
    }
}
