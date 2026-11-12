package com.brobehave.feature.profile

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.DataError
import com.brobehave.core.data.ProfileRepository
import com.brobehave.core.data.SocialRepository
import com.brobehave.core.data.dataError
import com.brobehave.core.designsystem.component.EmptyState
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.SectionHeader
import com.brobehave.core.designsystem.component.UserRow
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.model.FriendRelation
import com.brobehave.core.model.FriendshipEntry
import com.brobehave.core.model.UserSummary
import com.brobehave.core.ui.ErrorText
import com.brobehave.core.ui.SubScreenTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendsUiState(
    val query: String = "",
    /** Null until a search has run for the current query. */
    val results: List<UserSummary>? = null,
    val friendships: List<FriendshipEntry> = emptyList(),
    val loading: Boolean = true,
    val error: DataError? = null,
    val myUsername: String? = null,
) {
    val incoming get() = friendships.filter { it.relation == FriendRelation.INCOMING }
    val outgoing get() = friendships.filter { it.relation == FriendRelation.OUTGOING }
    val friends get() = friendships.filter { it.relation == FriendRelation.FRIEND }

    fun relationWith(userId: String): FriendRelation? = friendships.firstOrNull { it.userId == userId }?.relation
}

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val socialRepository: SocialRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FriendsUiState())
    val state: StateFlow<FriendsUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(myUsername = profileRepository.profile.first()?.username) }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val result = socialRepository.friendships()
            _state.update {
                it.copy(
                    loading = false,
                    friendships = result.getOrDefault(it.friendships),
                    error = result.exceptionOrNull()?.dataError(),
                )
            }
        }
    }

    fun onQueryChange(value: String) {
        val query = value.trim().removePrefix("@").lowercase()
        _state.update { it.copy(query = query, results = null) }
        searchJob?.cancel()
        if (query.length >= MIN_QUERY) {
            searchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val result = socialRepository.searchUsers(query)
                _state.update {
                    if (it.query == query) {
                        it.copy(
                            results = result.getOrDefault(emptyList()),
                            error = result.exceptionOrNull()?.dataError(),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun sendRequest(userId: String) = act { socialRepository.sendFriendRequest(userId) }

    fun accept(entry: FriendshipEntry) = act { socialRepository.acceptFriendRequest(entry.friendshipId) }

    /** Decline an incoming request, cancel an outgoing one, or remove a friend. */
    fun remove(entry: FriendshipEntry) = act { socialRepository.removeFriendship(entry.friendshipId) }

    private fun act(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val error = block().exceptionOrNull()?.dataError()
            _state.update { it.copy(error = error) }
            reload()
        }
    }

    private companion object {
        const val MIN_QUERY = 2
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/**
 * "My bros": search, requests, friends and invites.
 *
 * @param initialQuery pre-filled search, used when the app is opened from an invite link.
 */
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String? = null,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(initialQuery) { initialQuery?.let(viewModel::onQueryChange) }

    Scaffold(
        topBar = { SubScreenTopBar(stringResource(R.string.friends_title), onBack) },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(BroTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text(stringResource(R.string.friends_search)) },
                    prefix = { Text("@") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(state.error)
            }

            state.results?.let { results ->
                if (results.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            text = stringResource(R.string.friends_search_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = BroTheme.spacing.sm),
                        )
                    }
                }
                items(results, key = { "result-" + it.userId }) { user ->
                    val relation = state.relationWith(user.userId)
                    UserRow(displayName = user.displayName, username = user.username) {
                        when (relation) {
                            null -> TextButton(onClick = { viewModel.sendRequest(user.userId) }) {
                                Text(stringResource(R.string.friends_add))
                            }
                            FriendRelation.OUTGOING -> Text(
                                text = stringResource(R.string.friends_requested),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Unit
                        }
                    }
                }
            }

            if (state.incoming.isNotEmpty()) {
                item(key = "incoming-header") { SectionHeader(stringResource(R.string.friends_incoming)) }
                items(state.incoming, key = { it.friendshipId }) { entry ->
                    UserRow(displayName = entry.displayName, username = entry.username) {
                        TextButton(onClick = {
                            viewModel.remove(entry)
                        }) { Text(stringResource(R.string.friends_decline)) }
                        TextButton(onClick = {
                            viewModel.accept(entry)
                        }) { Text(stringResource(R.string.friends_accept)) }
                    }
                }
            }
            if (state.outgoing.isNotEmpty()) {
                item(key = "outgoing-header") { SectionHeader(stringResource(R.string.friends_outgoing)) }
                items(state.outgoing, key = { it.friendshipId }) { entry ->
                    UserRow(displayName = entry.displayName, username = entry.username) {
                        TextButton(onClick = {
                            viewModel.remove(entry)
                        }) { Text(stringResource(R.string.friends_cancel)) }
                    }
                }
            }

            item(key = "friends-header") { SectionHeader(stringResource(R.string.friends_list)) }
            if (state.friends.isEmpty() && !state.loading) {
                item(key = "friends-empty") {
                    EmptyState(
                        title = stringResource(R.string.friends_empty_title),
                        message = stringResource(R.string.friends_empty_message),
                    )
                }
            }
            items(state.friends, key = { it.friendshipId }) { entry ->
                UserRow(
                    displayName = entry.displayName,
                    username = entry.username,
                    onClick = { onOpenProfile(entry.userId) },
                )
            }

            item(key = "invite") {
                PrimaryButton(
                    text = stringResource(R.string.friends_invite),
                    onClick = { shareInvite(context, state.myUsername) },
                    enabled = state.myUsername != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = BroTheme.spacing.md),
                )
            }
        }
    }
}

/** Opens the system share sheet with the user's invite link. */
fun shareInvite(context: Context, username: String?) {
    if (username == null) return
    val link = context.getString(R.string.friends_invite_link, username)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.friends_invite_text, link))
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.friends_invite)))
}
