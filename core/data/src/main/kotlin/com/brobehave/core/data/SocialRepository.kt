package com.brobehave.core.data

import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.FriendRank
import com.brobehave.core.model.FriendshipEntry
import com.brobehave.core.model.PublicProfile
import com.brobehave.core.model.ReactionType
import com.brobehave.core.model.Reactor
import com.brobehave.core.model.ReportReason
import com.brobehave.core.model.UserSummary
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keyset cursor: the last post of the previous page. */
data class PostCursor(val createdAt: String, val id: String)

val FeedPost.cursor: PostCursor get() = PostCursor(createdAt, id)

/**
 * Everything about other people. All reads go through SQL functions that apply each
 * owner's privacy settings on the server.
 */
interface SocialRepository {
    suspend fun feed(after: PostCursor? = null): Result<List<FeedPost>>

    /** Posts of one user: your own, or a friend's as far as their privacy allows. */
    suspend fun userPosts(userId: String, after: PostCursor? = null): Result<List<FeedPost>>

    suspend fun friendsRanks(): Result<List<FriendRank>>

    suspend fun friendships(): Result<List<FriendshipEntry>>

    suspend fun searchUsers(query: String): Result<List<UserSummary>>

    suspend fun profile(userId: String): Result<PublicProfile?>

    suspend fun sendFriendRequest(userId: String): Result<Unit>

    suspend fun acceptFriendRequest(friendshipId: String): Result<Unit>

    /** Declines, cancels or removes, depending on the friendship's state. */
    suspend fun removeFriendship(friendshipId: String): Result<Unit>

    suspend fun block(userId: String): Result<Unit>

    suspend fun report(userId: String?, eventId: String?, reason: ReportReason): Result<Unit>

    /**
     * Sets the viewer's reaction on [post] to [reaction] (null removes it).
     * One reaction per user per post.
     */
    suspend fun setReaction(post: FeedPost, reaction: ReactionType?): Result<Unit>

    suspend fun reactors(eventId: String): Result<List<Reactor>>
}

internal class SupabaseSocialRepository @Inject constructor(private val client: SupabaseClient) : SocialRepository {
    override suspend fun feed(after: PostCursor?): Result<List<FeedPost>> = attempt {
        client.postgrest.rpc(
            "feed",
            buildJsonObject {
                put("p_cursor_created_at", after?.createdAt)
                put("p_cursor_id", after?.id)
                put("p_limit", PAGE_SIZE)
            },
        ).decodeList<FeedPost>()
    }

    override suspend fun userPosts(userId: String, after: PostCursor?): Result<List<FeedPost>> = attempt {
        client.postgrest.rpc(
            "user_posts",
            buildJsonObject {
                put("p_user", userId)
                put("p_cursor_created_at", after?.createdAt)
                put("p_cursor_id", after?.id)
                put("p_limit", PAGE_SIZE)
            },
        ).decodeList<FeedPost>()
    }

    override suspend fun friendsRanks(): Result<List<FriendRank>> = attempt {
        client.postgrest.rpc("friends_ranks").decodeList<FriendRank>()
    }

    override suspend fun friendships(): Result<List<FriendshipEntry>> = attempt {
        client.postgrest.rpc("my_friendships").decodeList<FriendshipEntry>()
    }

    override suspend fun searchUsers(query: String): Result<List<UserSummary>> = attempt {
        client.postgrest.rpc("search_users", buildJsonObject { put("p_query", query.trim()) })
            .decodeList<UserSummary>()
    }

    override suspend fun profile(userId: String): Result<PublicProfile?> = attempt {
        client.postgrest.rpc("get_profile", buildJsonObject { put("p_user", userId) })
            .decodeList<PublicProfile>()
            .firstOrNull()
    }

    override suspend fun sendFriendRequest(userId: String): Result<Unit> = attempt {
        val me = client.requireUserId()
        client.postgrest.from("friendships").insert(
            buildJsonObject {
                put("requester_id", me)
                put("addressee_id", userId)
            },
        )
    }

    override suspend fun acceptFriendRequest(friendshipId: String): Result<Unit> = attempt {
        client.postgrest.from("friendships").update({ set("status", "accepted") }) {
            filter { eq("id", friendshipId) }
        }
    }

    override suspend fun removeFriendship(friendshipId: String): Result<Unit> = attempt {
        client.postgrest.from("friendships").delete { filter { eq("id", friendshipId) } }
    }

    override suspend fun block(userId: String): Result<Unit> = attempt {
        val me = client.requireUserId()
        client.postgrest.from("blocks").insert(
            buildJsonObject {
                put("blocker_id", me)
                put("blocked_id", userId)
            },
        )
    }

    override suspend fun report(userId: String?, eventId: String?, reason: ReportReason): Result<Unit> = attempt {
        val me = client.requireUserId()
        client.postgrest.from("reports").insert(
            buildJsonObject {
                put("reporter_id", me)
                put("target_user_id", userId)
                put("target_event_id", eventId)
                put("reason", reason.key)
            },
        )
    }

    override suspend fun setReaction(post: FeedPost, reaction: ReactionType?): Result<Unit> = attempt {
        val me = client.requireUserId()
        val reactions = client.postgrest.from("reactions")
        when {
            reaction == null -> reactions.delete {
                filter {
                    eq("event_id", post.id)
                    eq("user_id", me)
                }
            }
            // Changing an existing reaction updates the row (only `type` is updatable).
            post.myReaction != null -> reactions.update({ set("type", reaction.key) }) {
                filter {
                    eq("event_id", post.id)
                    eq("user_id", me)
                }
            }
            else -> reactions.insert(
                buildJsonObject {
                    put("event_id", post.id)
                    put("user_id", me)
                    put("type", reaction.key)
                },
            )
        }
    }

    override suspend fun reactors(eventId: String): Result<List<Reactor>> = attempt {
        client.postgrest.rpc("event_reactors", buildJsonObject { put("p_event", eventId) })
            .decodeList<Reactor>()
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
