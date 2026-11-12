package com.brobehave.feature.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.SecondaryButton
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.ui.ErrorText
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.launch

/** Email/password sign-in, sign-up, password reset, and Google Sign-In. */
@Composable
fun AuthScreen(modifier: Modifier = Modifier, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AuthContent(
        state = state,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onModeChange = viewModel::onModeChange,
        onSubmit = viewModel::submit,
        onGoogle = { clientId ->
            scope.launch {
                when (val result = requestGoogleIdToken(context, clientId)) {
                    is GoogleResult.Token -> viewModel.onGoogleToken(result.idToken, result.rawNonce)
                    GoogleResult.Failed -> viewModel.onGoogleFailed()
                    GoogleResult.Cancelled -> Unit
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
internal fun AuthContent(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: (clientId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(BroTheme.spacing.lg),
    ) {
        Text(
            text = stringResource(
                when (state.mode) {
                    AuthMode.SIGN_IN -> R.string.auth_title_sign_in
                    AuthMode.SIGN_UP -> R.string.auth_title_sign_up
                    AuthMode.RESET -> R.string.auth_title_reset
                },
            ),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = if (state.mode == AuthMode.RESET) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.mode != AuthMode.RESET) {
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.auth_password)) },
                supportingText = if (state.mode == AuthMode.SIGN_UP) {
                    { Text(stringResource(R.string.auth_password_hint)) }
                } else {
                    null
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            stringResource(
                                if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password,
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ErrorText(state.error)
        state.notice?.let { notice ->
            Text(
                text = stringResource(
                    when (notice) {
                        AuthNotice.RESET_SENT -> R.string.auth_reset_sent
                        AuthNotice.CONFIRM_EMAIL -> R.string.auth_confirm_email
                        AuthNotice.GOOGLE_FAILED -> R.string.auth_google_failed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        PrimaryButton(
            text = stringResource(
                when (state.mode) {
                    AuthMode.SIGN_IN -> R.string.auth_sign_in
                    AuthMode.SIGN_UP -> R.string.auth_sign_up
                    AuthMode.RESET -> R.string.auth_send_reset
                },
            ),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.loading,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.mode != AuthMode.RESET && state.googleWebClientId != null) {
            Text(
                text = stringResource(R.string.auth_or),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            SecondaryButton(
                text = stringResource(R.string.auth_google),
                onClick = { onGoogle(state.googleWebClientId) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when (state.mode) {
            AuthMode.SIGN_IN -> {
                TextButton(onClick = { onModeChange(AuthMode.RESET) }) {
                    Text(stringResource(R.string.auth_forgot_password))
                }
                TextButton(onClick = { onModeChange(AuthMode.SIGN_UP) }) {
                    Text(stringResource(R.string.auth_switch_to_sign_up))
                }
            }
            AuthMode.SIGN_UP -> TextButton(onClick = { onModeChange(AuthMode.SIGN_IN) }) {
                Text(stringResource(R.string.auth_switch_to_sign_in))
            }
            AuthMode.RESET -> TextButton(onClick = { onModeChange(AuthMode.SIGN_IN) }) {
                Text(stringResource(R.string.auth_back_to_sign_in))
            }
        }
    }
}

private sealed interface GoogleResult {
    data class Token(val idToken: String, val rawNonce: String) : GoogleResult

    data object Cancelled : GoogleResult

    data object Failed : GoogleResult
}

/**
 * Asks Credential Manager for a Google ID token. Google receives the SHA-256 of a random
 * nonce; Supabase receives the raw nonce and checks that the two match.
 */
private suspend fun requestGoogleIdToken(context: Context, webClientId: String): GoogleResult {
    val rawNonce = UUID.randomUUID().toString()
    val hashedNonce = MessageDigest.getInstance("SHA-256")
        .digest(rawNonce.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val option = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(webClientId)
        .setNonce(hashedNonce)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    return try {
        val response = CredentialManager.create(context).getCredential(context, request)
        val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
        GoogleResult.Token(credential.idToken, rawNonce)
    } catch (_: GetCredentialCancellationException) {
        GoogleResult.Cancelled
    } catch (_: GetCredentialException) {
        GoogleResult.Failed
    } catch (_: GoogleIdTokenParsingException) {
        GoogleResult.Failed
    }
}
