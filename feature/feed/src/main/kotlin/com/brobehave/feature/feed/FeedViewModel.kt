package com.brobehave.feature.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.DataError
import com.brobehave.core.data.SocialRepository
import com.brobehave.core.data.cursor
import com.brobehave.core.data.dataError
import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.FriendRank
import com.brobehave.core.model.ReactionType
import com.brobehave.core.model.Reactor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val friends: List<FriendRank> = emptyList(),
    val posts: List<FeedPost> = emptyList(),
    /** First load, before anything is on screen. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: DataError? = null,
    val reactorsOpen: Boolean = false,
    val reactors: List<Reactor>? = null,
) {
    val isEmpty: Boolean get() = !loading && posts.isEmpty() && friends.isEmpty()
}

@HiltViewModel
class FeedViewModel @Inject constructor(private val socialRepository: SocialRepository) : ViewModel() {
    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = !it.loading, error = null) }
        viewModelScope.launch {
            val friends = socialRepository.friendsRanks()
            val posts = socialRepository.feed()
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    friends = friends.getOrDefault(it.friends),
                    posts = posts.getOrDefault(it.posts),
                    endReached = posts.getOrNull()?.isEmpty() == true,
                    error = (friends.exceptionOrNull() ?: posts.exceptionOrNull())?.dataError(),
                )
            }
        }
    }

    /** Loads the next page; called when the list nears its end. */
    fun loadMore() {
        val current = _state.value
        val last = current.posts.lastOrNull() ?: return
        if (current.loadingMore || current.endReached || current.loading) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val page = socialRepository.feed(after = last.cursor)
            _state.update { state ->
                val newPosts = page.getOrDefault(emptyList()).filter { new -> state.posts.none { it.id == new.id } }
                state.copy(
                    loadingMore = false,
                    posts = state.posts + newPosts,
                    // Stop asking after an empty page or an error; pull-to-refresh starts over.
                    endReached = newPosts.isEmpty(),
                    error = page.exceptionOrNull()?.dataError(),
                )
            }
        }
    }

    /** Optimistic reaction: the UI changes at once and rolls back if the server refuses. */
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
}
