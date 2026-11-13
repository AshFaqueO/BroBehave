package com.brobehave.feature.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brobehave.core.designsystem.component.Avatar
import com.brobehave.core.designsystem.component.BadgesSheet
import com.brobehave.core.designsystem.component.ConfirmDialog
import com.brobehave.core.designsystem.component.FeedCard
import com.brobehave.core.designsystem.component.HistoryCalendar
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.ReactorsSheet
import com.brobehave.core.designsystem.component.SecondaryButton
import com.brobehave.core.designsystem.component.SectionHeader
import com.brobehave.core.designsystem.component.SettingsRow
import com.brobehave.core.designsystem.component.StreakRing
import com.brobehave.core.designsystem.component.TierBadge
import com.brobehave.core.designsystem.component.ToggleRow
import com.brobehave.core.designsystem.component.rememberReducedMotion
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.designsystem.theme.style
import com.brobehave.core.model.DetectionState
import com.brobehave.core.model.DetectionStatus
import com.brobehave.core.model.Profile
import com.brobehave.core.model.Rank
import com.brobehave.core.model.StreakSnapshot
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.LoadingScreen
import com.brobehave.core.ui.relativeTime

/** The user's own page: streak, rank, actions, history, slip tracking and their posts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenFriends: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onEnableDetection: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    val snapshot = state.snapshot
    if (profile == null || snapshot == null) {
        LoadingScreen(modifier)
        return
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(BroTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { Header(profile, snapshot, onEditProfile, onOpenSettings) }
            item(key = "streak") { StreakCard(profile, snapshot, onOpenBadges = { viewModel.setBadgesVisible(true) }) }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm)) {
                        if (profile.selfReportEnabled) {
                            SecondaryButton(
                                text = stringResource(R.string.profile_slipped),
                                onClick = { viewModel.openDialog(ProfileDialog.SLIP) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SecondaryButton(
                            text = stringResource(R.string.profile_relapsed),
                            onClick = { viewModel.openDialog(ProfileDialog.RELAPSE) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.slipAlreadyFlagged) {
                        Text(
                            text = stringResource(R.string.profile_slip_already),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ErrorText(state.error)
                }
            }
            item(key = "tracking") {
                SectionHeader(stringResource(R.string.tracking_title))
                ToggleRow(
                    title = stringResource(R.string.tracking_self),
                    subtitle = stringResource(R.string.tracking_self_subtitle),
                    checked = profile.selfReportEnabled,
                    onCheckedChange = viewModel::setSelfReport,
                )
                if (state.detectionAvailable) {
                    DetectionToggle(
                        state = state.detection,
                        onTurnOn = onEnableDetection,
                        onTurnOff = viewModel::turnOffDetection,
                    )
                }
                if (!profile.selfReportEnabled && state.detection.status != DetectionStatus.ACTIVE) {
                    Text(
                        text = stringResource(R.string.tracking_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "history") {
                SectionHeader(stringResource(R.string.profile_history))
                HistoryCalendar(today = state.today, statusOf = state::dayStatus)
            }
            item(key = "bros") {
                SettingsRow(
                    title = stringResource(R.string.profile_my_bros),
                    subtitle = stringResource(R.string.profile_my_bros_subtitle),
                    onClick = onOpenFriends,
                )
            }
            item(key = "posts-header") { SectionHeader(stringResource(R.string.profile_my_posts)) }
            if (state.posts.isEmpty()) {
                item(key = "posts-empty") {
                    Text(
                        text = stringResource(R.string.profile_no_posts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.posts, key = { it.id }) { post ->
                FeedCard(
                    post = post,
                    timeLabel = relativeTime(post.createdAt),
                    onReact = {},
                    onOpenReactors = { viewModel.openReactors(post) },
                    canReact = false,
                )
            }
        }
    }

    when (state.dialog) {
        ProfileDialog.RELAPSE -> NoteDialog(
            title = stringResource(R.string.relapse_title),
            message = stringResource(R.string.relapse_message),
            confirmLabel = stringResource(R.string.relapse_confirm),
            onConfirm = viewModel::confirmRelapse,
            onDismiss = viewModel::closeDialog,
        )
        ProfileDialog.SLIP -> NoteDialog(
            title = stringResource(R.string.slip_title),
            message = stringResource(R.string.slip_message),
            confirmLabel = stringResource(R.string.slip_confirm),
            onConfirm = viewModel::confirmSlip,
            onDismiss = viewModel::closeDialog,
        )
        null -> Unit
    }
    if (state.showBadges) {
        BadgesSheet(currentStreakRank = snapshot.tier.streakRank, onDismiss = { viewModel.setBadgesVisible(false) })
    }
    if (state.reactorsOpen) {
        ReactorsSheet(reactors = state.reactors, onDismiss = viewModel::closeReactors)
    }
    state.celebration?.let { rank -> CelebrationDialog(rank = rank, onDismiss = viewModel::dismissCelebration) }
}

@Composable
private fun Header(profile: Profile, snapshot: StreakSnapshot, onEditProfile: () -> Unit, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.profile_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.profile_settings)) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
        ) {
            Avatar(name = profile.displayName ?: profile.username, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.displayName ?: profile.username.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "@" + profile.username.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TierBadge(rank = snapshot.tier.effectiveRank, verified = profile.tierVerified)
            TextButton(onClick = onEditProfile, modifier = Modifier.padding(start = BroTheme.spacing.sm)) {
                Text(stringResource(R.string.profile_edit))
            }
        }
    }
}

@Composable
private fun StreakCard(profile: Profile, snapshot: StreakSnapshot, onOpenBadges: () -> Unit) {
    val tier = snapshot.tier
    val streakRankName = stringResource(tier.streakRank.style.nameRes)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(BroTheme.spacing.md),
        ) {
            StreakRing(streakDays = snapshot.streakDays, progress = snapshot.progressToNext)
            Text(
                text = tier.streakRank.next?.let { next ->
                    val days = tier.daysToNextRank ?: 0
                    pluralStringResource(R.plurals.profile_next_rank, days, days, stringResource(next.style.nameRes))
                } ?: stringResource(R.string.profile_top_rank),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            // Always show both: the badge (effective rank) and what the streak alone earned.
            Text(
                text = if (tier.ranksLost > 0) {
                    pluralStringResource(R.plurals.profile_rank_penalty, tier.ranksLost, streakRankName, tier.ranksLost)
                } else {
                    stringResource(R.string.profile_rank_by_streak, streakRankName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val longest = maxOf(profile.longestStreakDays, snapshot.streakDays)
            Text(
                text = stringResource(R.string.profile_longest) + ": " +
                    pluralStringResource(R.plurals.profile_days, longest, longest),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.profile_all_badges),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onOpenBadges)
                    .padding(BroTheme.spacing.md),
            )
        }
    }
}

/**
 * The automatic-detection switch with its status. Turning it on opens the disclosure
 * screen first; nothing starts from this switch alone.
 */
@Composable
private fun DetectionToggle(state: DetectionState, onTurnOn: () -> Unit, onTurnOff: () -> Unit) {
    val context = LocalContext.current
    val active = state.status == DetectionStatus.ACTIVE
    ToggleRow(
        title = stringResource(R.string.tracking_auto),
        subtitle = stringResource(
            when (state.status) {
                DetectionStatus.ACTIVE -> R.string.tracking_auto_active
                DetectionStatus.REVOKED -> R.string.tracking_auto_revoked
                DetectionStatus.OFF -> R.string.tracking_auto_subtitle
            },
        ),
        checked = active,
        onCheckedChange = { if (it) onTurnOn() else onTurnOff() },
    )
    if (active && state.privateDnsBypass) {
        StatusNote(stringResource(R.string.tracking_private_dns), isWarning = true)
    }
    if (active && !context.isIgnoringBatteryOptimizations()) {
        StatusNote(stringResource(R.string.tracking_battery), isWarning = false)
        TextButton(onClick = { context.openBatterySettings() }) { Text(stringResource(R.string.tracking_battery_open)) }
    }
}

@Composable
private fun StatusNote(text: String, isWarning: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Opens the system list where the user can exempt the app; no special permission needed. */
private fun Context.openBatterySettings() {
    try {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Some devices have no such screen; the note above still explains what to do.
    }
}

/** Kind confirmation with an optional private note that never leaves the device. */
@Composable
private fun NoteDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        dismissLabel = stringResource(R.string.dialog_cancel),
        onConfirm = { onConfirm(note) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(NOTE_MAX) },
            label = { Text(stringResource(R.string.dialog_note)) },
            supportingText = { Text(stringResource(R.string.dialog_note_hint)) },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val NOTE_MAX = 500
private const val CELEBRATION_START_SCALE = 0.3f

@Composable
private fun CelebrationDialog(rank: Rank, onDismiss: () -> Unit) {
    // The new badge pops in with a bounce; with reduced motion it simply appears.
    val reducedMotion = rememberReducedMotion()
    val scale = remember { Animatable(if (reducedMotion) 1f else CELEBRATION_START_SCALE) }
    LaunchedEffect(rank) {
        if (!reducedMotion) {
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.celebration_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TierBadge(
                    rank = rank,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    },
                )
                Text(
                    text = stringResource(R.string.celebration_message, stringResource(rank.style.nameRes)),
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            PrimaryButton(text = stringResource(R.string.celebration_dismiss), onClick = onDismiss)
        },
    )
}
