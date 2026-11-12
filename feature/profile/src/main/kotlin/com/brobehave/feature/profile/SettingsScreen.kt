package com.brobehave.feature.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.AuthRepository
import com.brobehave.core.data.DataError
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.data.dataError
import com.brobehave.core.designsystem.component.ConfirmDialog
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.SectionHeader
import com.brobehave.core.designsystem.component.SettingsRow
import com.brobehave.core.designsystem.component.ToggleRow
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.model.AppIconSwitcher
import com.brobehave.core.model.NotificationSettings
import com.brobehave.core.model.PrivacySettings
import com.brobehave.core.model.Visibility
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.SubScreenTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val privacy: PrivacySettings? = null,
    val notifications: NotificationSettings? = null,
    val discreet: Boolean = false,
    val confirmDelete: Boolean = false,
    val busy: Boolean = false,
    val error: DataError? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val iconSwitcher: AppIconSwitcher,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(discreet = iconSwitcher.isDiscreet))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val privacy = profileRepository.privacySettings()
            val notifications = profileRepository.notificationSettings()
            _state.update {
                it.copy(
                    privacy = privacy.getOrNull(),
                    notifications = notifications.getOrNull(),
                    error = (privacy.exceptionOrNull() ?: notifications.exceptionOrNull())?.dataError(),
                )
            }
        }
    }

    /** Optimistic: the switch moves at once and snaps back if the server refuses. */
    fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) {
        val before = _state.value.privacy ?: return
        val after = transform(before)
        _state.update { it.copy(privacy = after, error = null) }
        viewModelScope.launch {
            profileRepository.updatePrivacySettings(after).onFailure { failure ->
                _state.update { it.copy(privacy = before, error = failure.dataError()) }
            }
        }
    }

    fun updateNotifications(transform: (NotificationSettings) -> NotificationSettings) {
        val before = _state.value.notifications ?: return
        val after = transform(before)
        _state.update { it.copy(notifications = after, error = null) }
        viewModelScope.launch {
            profileRepository.updateNotificationSettings(after).onFailure { failure ->
                _state.update { it.copy(notifications = before, error = failure.dataError()) }
            }
        }
    }

    fun setDiscreet(enabled: Boolean) {
        iconSwitcher.setDiscreet(enabled)
        _state.update { it.copy(discreet = enabled) }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    fun setConfirmDelete(visible: Boolean) = _state.update { it.copy(confirmDelete = visible) }

    fun deleteAccount() {
        _state.update { it.copy(confirmDelete = false, busy = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.deleteAccount()
            _state.update { it.copy(busy = false, error = result.exceptionOrNull()?.dataError()) }
        }
    }
}

private fun Boolean.toVisibility() = if (this) Visibility.FRIENDS else Visibility.PRIVATE

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { SubScreenTopBar(stringResource(R.string.settings_title), onBack) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BroTheme.spacing.md),
        ) {
            ErrorText(state.error)

            state.privacy?.let { privacy ->
                SectionHeader(stringResource(R.string.settings_privacy))
                ToggleRow(stringResource(R.string.settings_show_streak), privacy.showStreak == Visibility.FRIENDS, {
                    viewModel.updatePrivacy { p -> p.copy(showStreak = it.toVisibility()) }
                })
                ToggleRow(stringResource(R.string.settings_show_relapses), privacy.showRelapses == Visibility.FRIENDS, {
                    viewModel.updatePrivacy { p -> p.copy(showRelapses = it.toVisibility()) }
                })
                ToggleRow(stringResource(R.string.settings_show_tier), privacy.showTier == Visibility.FRIENDS, {
                    viewModel.updatePrivacy { p -> p.copy(showTier = it.toVisibility()) }
                })
                ToggleRow(
                    title = stringResource(R.string.settings_allow_haha),
                    subtitle = stringResource(R.string.settings_allow_haha_subtitle),
                    checked = privacy.allowHaha,
                    onCheckedChange = { viewModel.updatePrivacy { p -> p.copy(allowHaha = it) } },
                )
            }

            state.notifications?.let { n ->
                SectionHeader(stringResource(R.string.settings_notifications))
                ToggleRow(stringResource(R.string.settings_notify_requests), n.friendRequests, {
                    viewModel.updateNotifications { s -> s.copy(friendRequests = it) }
                })
                ToggleRow(stringResource(R.string.settings_notify_milestones), n.milestones, {
                    viewModel.updateNotifications { s -> s.copy(milestones = it) }
                })
                ToggleRow(stringResource(R.string.settings_notify_tiers), n.tierChanges, {
                    viewModel.updateNotifications { s -> s.copy(tierChanges = it) }
                })
                ToggleRow(stringResource(R.string.settings_notify_reactions), n.reactions, {
                    viewModel.updateNotifications { s -> s.copy(reactions = it) }
                })
            }

            SectionHeader(stringResource(R.string.settings_discreet))
            ToggleRow(
                title = stringResource(R.string.settings_discreet_toggle),
                subtitle = stringResource(R.string.settings_discreet_subtitle),
                checked = state.discreet,
                onCheckedChange = viewModel::setDiscreet,
            )

            SectionHeader(stringResource(R.string.settings_about))
            SettingsRow(stringResource(R.string.settings_privacy_policy), {
                openUrl(context, context.getString(R.string.url_privacy_policy))
            })
            SettingsRow(stringResource(R.string.settings_terms), {
                openUrl(context, context.getString(R.string.url_terms))
            })
            SettingsRow(stringResource(R.string.settings_delete_web), {
                openUrl(context, context.getString(R.string.url_delete_account))
            })

            SectionHeader(stringResource(R.string.settings_account))
            SettingsRow(stringResource(R.string.settings_sign_out), viewModel::signOut)
            SettingsRow(
                title = stringResource(R.string.settings_delete),
                onClick = { viewModel.setConfirmDelete(true) },
                destructive = true,
                modifier = Modifier.padding(bottom = BroTheme.spacing.xl),
            )
        }
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_title),
            message = stringResource(R.string.settings_delete_message),
            confirmLabel = stringResource(R.string.settings_delete_confirm),
            dismissLabel = stringResource(R.string.dialog_cancel),
            onConfirm = viewModel::deleteAccount,
            onDismiss = { viewModel.setConfirmDelete(false) },
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser installed; nothing useful to do.
    }
}

data class EditProfileUiState(
    val username: String = "",
    val displayName: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: DataError? = null,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(private val profileRepository: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = profileRepository.profile.first()
            _state.update {
                it.copy(username = profile?.username.orEmpty(), displayName = profile?.displayName.orEmpty())
            }
        }
    }

    fun onUsernameChange(value: String) = _state.update {
        it.copy(
            username = value.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(USERNAME_MAX),
            error = null,
        )
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value.take(DISPLAY_NAME_MAX)) }

    fun save() {
        val current = _state.value
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val displayName = current.displayName.trim().ifEmpty { current.username }
            val result = profileRepository.updateProfile(current.username, displayName)
            _state.update {
                it.copy(saving = false, saved = result.isSuccess, error = result.exceptionOrNull()?.dataError())
            }
        }
    }

    private companion object {
        const val USERNAME_MAX = 20
        const val DISPLAY_NAME_MAX = 40
    }
}

private const val USERNAME_MIN = 3

@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var leaving by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (state.saved && !leaving) {
            leaving = true
            onBack()
        }
    }

    Scaffold(
        topBar = { SubScreenTopBar(stringResource(R.string.edit_title), onBack) },
        modifier = modifier,
    ) { padding ->
        Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(BroTheme.spacing.md),
        ) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text(stringResource(R.string.edit_display_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text(stringResource(R.string.edit_username)) },
                prefix = { Text("@") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(state.error)
            PrimaryButton(
                text = stringResource(R.string.edit_save),
                onClick = viewModel::save,
                enabled = state.username.length >= USERNAME_MIN,
                loading = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
