package com.brobehave.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brobehave.core.data.DataError
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.SectionHeader
import com.brobehave.core.designsystem.component.ToggleRow
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.errorMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Shown once after the first sign-in: username, streak start, privacy defaults, slip tracking. */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SetupContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onStreakStartChange = viewModel::onStreakStartChange,
        onShowStreakChange = viewModel::onShowStreakChange,
        onShowRelapsesChange = viewModel::onShowRelapsesChange,
        onShowTierChange = viewModel::onShowTierChange,
        onSlipChoiceChange = viewModel::onSlipChoiceChange,
        onSubmit = viewModel::submit,
        onSignOut = viewModel::signOut,
        modifier = modifier,
    )
}

@Composable
internal fun SetupContent(
    state: SetupUiState,
    onUsernameChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onStreakStartChange: (LocalDate) -> Unit,
    onShowStreakChange: (Boolean) -> Unit,
    onShowRelapsesChange: (Boolean) -> Unit,
    onShowTierChange: (Boolean) -> Unit,
    onSlipChoiceChange: (SlipChoice) -> Unit,
    onSubmit: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(BroTheme.spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )

        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.setup_username)) },
            prefix = { Text("@") },
            singleLine = true,
            isError = state.usernameStatus == UsernameStatus.TAKEN,
            supportingText = { Text(usernameSupportingText(state.usernameStatus)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.displayName,
            onValueChange = onDisplayNameChange,
            label = { Text(stringResource(R.string.setup_display_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(stringResource(R.string.setup_streak_title))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (state.streakStart == state.today) {
                    stringResource(R.string.setup_streak_today)
                } else {
                    state.streakStart.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showDatePicker = true }) { Text(stringResource(R.string.setup_streak_change)) }
        }
        Note(stringResource(R.string.setup_streak_note))

        SectionHeader(stringResource(R.string.setup_privacy_title))
        ToggleRow(stringResource(R.string.setup_show_streak), state.showStreak, onShowStreakChange)
        ToggleRow(stringResource(R.string.setup_show_relapses), state.showRelapses, onShowRelapsesChange)
        ToggleRow(stringResource(R.string.setup_show_tier), state.showTier, onShowTierChange)
        Note(stringResource(R.string.setup_privacy_note))

        SectionHeader(stringResource(R.string.setup_tracking_title))
        Note(stringResource(R.string.setup_tracking_note))
        Column(Modifier.selectableGroup()) {
            ChoiceRow(
                label = stringResource(R.string.setup_tracking_self),
                selected = state.slipChoice == SlipChoice.SELF_REPORT,
                onSelect = { onSlipChoiceChange(SlipChoice.SELF_REPORT) },
            )
            // Automatic detection arrives with the detection module (Phase 4).
            ChoiceRow(label = stringResource(R.string.setup_tracking_auto), selected = false, onSelect = {
            }, enabled = false)
            ChoiceRow(
                label = stringResource(R.string.setup_tracking_later),
                selected = state.slipChoice == SlipChoice.LATER,
                onSelect = { onSlipChoiceChange(SlipChoice.LATER) },
            )
        }

        Note(stringResource(R.string.setup_timezone, state.timezone))
        ErrorText(state.error?.takeIf { it != DataError.USERNAME_TAKEN })
        PrimaryButton(
            text = stringResource(R.string.setup_submit),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BroTheme.spacing.sm),
        )
        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.setup_sign_out))
        }
    }

    if (showDatePicker) {
        StreakDatePicker(
            initial = state.streakStart,
            earliest = state.earliestStreakStart,
            latest = state.today,
            onPick = {
                onStreakStartChange(it)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun usernameSupportingText(status: UsernameStatus): String = when (status) {
    UsernameStatus.AVAILABLE -> stringResource(R.string.setup_username_available)
    UsernameStatus.CHECKING -> stringResource(R.string.setup_username_checking)
    UsernameStatus.TAKEN -> errorMessage(DataError.USERNAME_TAKEN)
    else -> stringResource(R.string.setup_username_hint)
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BroTheme.spacing.touchTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreakDatePicker(
    initial: LocalDate,
    earliest: LocalDate,
    latest: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // The Material date picker works in UTC midnights.
    fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toLocalDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        yearRange = earliest.year..latest.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis.toLocalDate().let { !it.isBefore(earliest) && !it.isAfter(latest) }

            override fun isSelectableYear(year: Int): Boolean = year in earliest.year..latest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onPick(it.toLocalDate()) } ?: onDismiss() }) {
                Text(stringResource(R.string.setup_date_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_date_cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}
