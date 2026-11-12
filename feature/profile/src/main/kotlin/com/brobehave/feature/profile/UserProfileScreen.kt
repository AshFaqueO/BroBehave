package com.brobehave.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.DataError
import com.brobehave.core.data.SocialRepository
import com.brobehave.core.data.dataError
import com.brobehave.core.designsystem.component.Avatar
import com.brobehave.core.designsystem.component.ConfirmDialog
import com.brobehave.core.designsystem.component.EmptyState
import com.brobehave.core.designsystem.component.FeedCard
import com.brobehave.core.designsystem.component.ReactorsSheet
import com.brobehave.core.designsystem.component.SectionHeader
import com.brobehave.core.designsystem.component.TierBadge
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.PublicProfile
import com.brobehave.core.model.ReactionType
import com.brobehave.core.model.Reactor
import com.brobehave.core.model.ReportReason
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.LoadingScreen
import com.brobehave.core.ui.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UserDialog { REMOVE, BLOCK, REPORT }

data class UserProfileUiState(
    val loading: Boolean = true,
    val profile: PublicProfile? = null,
    val posts: List<FeedPost> = emptyList(),
    val dialog: UserDialog? = null,
    val reportSent: Boolean = false,
    val error: DataError? = null,
    val reactorsOpen: Boolean = false,
    val reactors: List<Reactor>? = null,
    /** True once the friend was removed or blocked: the screen should close. */
    val gone: Boolean = false,
)

@HiltViewModel
class UserProfileViewModel @Inject constructor(private val socialRepository: SocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(UserProfileUiState())
    val state: StateFlow<UserProfileUiState> = _state.asStateFlow()

    private var userId: String? = null

    fun load(userId: String) {
        if (this.userId == userId) return
        this.userId = userId
        viewModelScope.launch {
            val profile = socialRepository.profile(userId)
            val posts = socialRepository.userPosts(userId)
            _state.update {
                it.copy(
                    loading = false,
                    profile = profile.getOrNull(),
                    posts = posts.getOrDefault(emptyList()),
                    error = (profile.exceptionOrNull() ?: posts.exceptionOrNull())?.dataError(),
                )
            }
        }
    }

    /** Optimistic reaction with rollback when the server refuses. */
    fun react(post: FeedPost, reaction: ReactionType?) {
        replace(post.id) { it.withMyReaction(reaction) }
        viewModelScope.launch {
            socialRepository.setReaction(post, reaction).onFailure { failure ->
                replace(post.id) { post }
                _state.update { it.copy(error = failure.dataError()) }
            }
        }
    }

    private fun replace(postId: String, transform: (FeedPost) -> FeedPost) = _state.update { state ->
        state.copy(posts = state.posts.map { if (it.id == postId) transform(it) else it })
    }

    fun openReactors(post: FeedPost) {
        _state.update { it.copy(reactorsOpen = true, reactors = null) }
        viewModelScope.launch {
            val reactors = socialRepository.reactors(post.id).getOrDefault(emptyList())
            _state.update { if (it.reactorsOpen) it.copy(reactors = reactors) else it }
        }
    }

    fun closeReactors() = _state.update { it.copy(reactorsOpen = false, reactors = null) }

    fun setDialog(dialog: UserDialog?) = _state.update { it.copy(dialog = dialog) }

    fun removeFriend() {
        val id = userId ?: return
        setDialog(null)
        viewModelScope.launch {
            val friendship = socialRepository.friendships().getOrNull()?.firstOrNull { it.userId == id }
            val result = friendship?.let { socialRepository.removeFriendship(it.friendshipId) }
            _state.update { it.copy(gone = result?.isSuccess == true, error = result?.exceptionOrNull()?.dataError()) }
        }
    }

    fun block() {
        val id = userId ?: return
        setDialog(null)
        viewModelScope.launch {
            val result = socialRepository.block(id)
            _state.update { it.copy(gone = result.isSuccess, error = result.exceptionOrNull()?.dataError()) }
        }
    }

    fun report(reason: ReportReason) {
        val id = userId ?: return
        setDialog(null)
        viewModelScope.launch {
            val result = socialRepository.report(userId = id, eventId = null, reason = reason)
            _state.update { it.copy(reportSent = result.isSuccess, error = result.exceptionOrNull()?.dataError()) }
        }
    }
}

/** A friend's profile: the read-only version of the Profile page, with remove / block / report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    userId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { viewModel.load(userId) }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val name = state.profile?.displayName ?: state.profile?.username.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(
                            "‹ " + stringResource(com.brobehave.core.ui.R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.profile != null) {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text(stringResource(R.string.user_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                listOf(
                                    R.string.user_remove to UserDialog.REMOVE,
                                    R.string.user_block to UserDialog.BLOCK,
                                    R.string.user_report to UserDialog.REPORT,
                                ).forEach { (label, dialog) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setDialog(dialog)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        val profile = state.profile
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            profile == null -> EmptyState(
                title = stringResource(R.string.user_unavailable),
                message = "",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(BroTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item(key = "header") { UserHeader(profile) }
                item(key = "notices") {
                    ErrorText(state.error)
                    if (state.reportSent) {
                        Text(text = stringResource(R.string.report_sent), color = MaterialTheme.colorScheme.primary)
                    }
                }
                item(key = "posts-header") { SectionHeader(stringResource(R.string.user_posts)) }
                if (state.posts.isEmpty()) {
                    item(key = "posts-empty") {
                        Text(
                            text = stringResource(R.string.user_no_posts),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.posts, key = { it.id }) { post ->
                    FeedCard(
                        post = post,
                        timeLabel = relativeTime(post.createdAt),
                        onReact = { viewModel.react(post, it) },
                        onOpenReactors = { viewModel.openReactors(post) },
                        canReact = !profile.isSelf,
                    )
                }
            }
        }
    }

    when (state.dialog) {
        UserDialog.REMOVE -> ConfirmDialog(
            title = stringResource(R.string.user_remove_title, name),
            message = stringResource(R.string.user_remove_message),
            confirmLabel = stringResource(R.string.user_remove),
            dismissLabel = stringResource(R.string.dialog_cancel),
            onConfirm = viewModel::removeFriend,
            onDismiss = { viewModel.setDialog(null) },
        )
        UserDialog.BLOCK -> ConfirmDialog(
            title = stringResource(R.string.user_block_title, name),
            message = stringResource(R.string.user_block_message),
            confirmLabel = stringResource(R.string.user_block),
            dismissLabel = stringResource(R.string.dialog_cancel),
            onConfirm = viewModel::block,
            onDismiss = { viewModel.setDialog(null) },
        )
        UserDialog.REPORT -> ReportDialog(onReport = viewModel::report, onDismiss = { viewModel.setDialog(null) })
        null -> Unit
    }
    if (state.reactorsOpen) {
        ReactorsSheet(reactors = state.reactors, onDismiss = viewModel::closeReactors)
    }
}

@Composable
private fun UserHeader(profile: PublicProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Avatar(name = profile.displayName ?: profile.username, size = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs), modifier = Modifier.weight(1f)) {
            Text(text = "@" + profile.username.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            profile.tier?.let { TierBadge(rank = it, verified = profile.tierVerified) }
            val streak = profile.streakDays
            Text(
                text = stringResource(R.string.user_streak) + ": " + if (streak != null) {
                    pluralStringResource(R.plurals.profile_days, streak, streak)
                } else {
                    stringResource(R.string.user_private)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            profile.longestStreakDays?.let { longest ->
                Text(
                    text = stringResource(R.string.profile_longest) + ": " +
                        pluralStringResource(R.plurals.profile_days, longest, longest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val ReportReason.labelRes: Int
    get() = when (this) {
        ReportReason.HARASSMENT -> R.string.report_harassment
        ReportReason.INAPPROPRIATE -> R.string.report_inappropriate
        ReportReason.SPAM -> R.string.report_spam
        ReportReason.IMPERSONATION -> R.string.report_impersonation
        ReportReason.OTHER -> R.string.report_other
    }

@Composable
private fun ReportDialog(onReport: (ReportReason) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ReportReason.HARASSMENT) }
    ConfirmDialog(
        title = stringResource(R.string.report_title),
        message = stringResource(R.string.report_message),
        confirmLabel = stringResource(R.string.user_report),
        dismissLabel = stringResource(R.string.dialog_cancel),
        onConfirm = { onReport(selected) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            ReportReason.entries.forEach { reason ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BroTheme.spacing.touchTarget)
                        .selectable(selected = reason == selected, role = Role.RadioButton) { selected = reason },
                ) {
                    RadioButton(selected = reason == selected, onClick = null)
                    Text(stringResource(reason.labelRes))
                }
            }
        }
    }
}
