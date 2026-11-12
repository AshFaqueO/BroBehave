package com.brobehave.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brobehave.core.designsystem.component.Avatar
import com.brobehave.core.designsystem.component.EmptyState
import com.brobehave.core.designsystem.component.FeedCard
import com.brobehave.core.designsystem.component.ReactorsSheet
import com.brobehave.core.designsystem.component.TierBadge
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.designsystem.theme.style
import com.brobehave.core.model.FriendRank
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.LoadingScreen
import com.brobehave.core.ui.relativeTime

private const val LOAD_MORE_THRESHOLD = 4

/** The Feed page: the "Your bros" strip on top, friends' posts below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenProfile: (userId: String) -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        LoadingScreen(modifier)
        return
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.posts.size) { if (nearEnd) viewModel.loadMore() }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = BroTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.feed_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .padding(horizontal = BroTheme.spacing.md)
                        .semantics { heading() },
                )
                ErrorText(state.error, Modifier.padding(horizontal = BroTheme.spacing.md))
            }
            if (state.friends.isNotEmpty()) {
                item(key = "bros") { BrosStrip(state.friends, onOpenProfile) }
            }
            if (state.posts.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = stringResource(R.string.feed_empty_title),
                        message = stringResource(R.string.feed_empty_message),
                        actionLabel = stringResource(R.string.feed_invite),
                        onAction = onInvite,
                    )
                }
            }
            items(state.posts, key = { it.id }) { post ->
                FeedCard(
                    post = post,
                    timeLabel = relativeTime(post.createdAt),
                    onReact = { viewModel.react(post, it) },
                    onOpenReactors = { viewModel.openReactors(post) },
                    onOpenProfile = { onOpenProfile(post.userId) },
                    modifier = Modifier.padding(horizontal = BroTheme.spacing.md),
                )
            }
            if (state.loadingMore) {
                item(key = "more") {
                    val label = stringResource(R.string.feed_loading_more)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BroTheme.spacing.md)
                            .clearAndSetSemantics { contentDescription = label },
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (state.reactorsOpen) {
        ReactorsSheet(reactors = state.reactors, onDismiss = viewModel::closeReactors)
    }
}

/** Horizontal strip of friends: avatar, rank badge (with Verified ✓) and streak days. */
@Composable
private fun BrosStrip(friends: List<FriendRank>, onOpenProfile: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm)) {
        Text(
            text = stringResource(R.string.feed_your_bros),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = BroTheme.spacing.md)
                .semantics { heading() },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = BroTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
        ) {
            items(friends, key = { it.userId }) { friend ->
                val name = friend.displayName ?: friend.username.orEmpty()
                val streak = friend.streakDays?.let { pluralStringResource(R.plurals.feed_streak_days, it, it) }
                    ?: stringResource(R.string.feed_streak_private)
                val rank = friend.tier?.let { stringResource(it.style.nameRes) }
                    ?.let { if (friend.tierVerified) it + ", " + stringResource(R.string.feed_bro_verified) else it }
                    ?: stringResource(R.string.feed_rank_private)
                val description = stringResource(R.string.feed_bro_description, name, rank, streak)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
                    modifier = Modifier
                        .widthIn(min = 96.dp)
                        .clickable(role = Role.Button) { onOpenProfile(friend.userId) }
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                            onClick {
                                onOpenProfile(friend.userId)
                                true
                            }
                        }
                        .padding(vertical = BroTheme.spacing.xs),
                ) {
                    Avatar(name = name, size = 56.dp)
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    friend.tier?.let { TierBadge(rank = it, verified = friend.tierVerified) }
                    Text(
                        text = streak,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
