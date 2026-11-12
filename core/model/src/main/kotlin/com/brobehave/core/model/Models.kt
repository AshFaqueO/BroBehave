package com.brobehave.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val TOP_REACTIONS = 3

// Note: no private companion objects on @Serializable classes here — they hide the generated
// serializer() from the reflective lookup the Supabase client uses.
// These types are both the domain model and the wire format of Supabase rows / RPC results.

@Serializable
enum class Visibility {
    @SerialName("friends")
    FRIENDS,

    @SerialName("private")
    PRIVATE,
}

@Serializable
enum class EventType {
    @SerialName("streak_started")
    STREAK_STARTED,

    @SerialName("relapse")
    RELAPSE,

    @SerialName("milestone")
    MILESTONE,

    @SerialName("tier_up")
    TIER_UP,

    @SerialName("tier_down")
    TIER_DOWN,
}

/** The five reactions, in picker order. [key] is the Postgres `reaction_type` value. */
@Serializable
enum class ReactionType(val key: String, val emoji: String) {
    @SerialName("haha")
    HAHA("haha", "😂"),

    @SerialName("impressive")
    IMPRESSIVE("impressive", "🤯"),

    @SerialName("fire")
    FIRE("fire", "🔥"),

    @SerialName("respect")
    RESPECT("respect", "👊"),

    @SerialName("strong")
    STRONG("strong", "💪"),
}

@Serializable
enum class FriendRelation {
    @SerialName("friend")
    FRIEND,

    @SerialName("incoming")
    INCOMING,

    @SerialName("outgoing")
    OUTGOING,
}

/** The signed-in user's own profile row. Timestamps are ISO-8601 strings in UTC. */
@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val timezone: String = "UTC",
    @SerialName("streak_start") val streakStart: String,
    @SerialName("longest_streak_days") val longestStreakDays: Int = 0,
    val tier: Rank = Rank.CLOWN,
    @SerialName("streak_tier") val streakTier: Rank = Rank.CLOWN,
    @SerialName("tier_verified") val tierVerified: Boolean = false,
    @SerialName("self_report_enabled") val selfReportEnabled: Boolean = false,
    @SerialName("onboarded_at") val onboardedAt: String? = null,
) {
    val isOnboarded: Boolean get() = onboardedAt != null
}

@Serializable
data class PrivacySettings(
    @SerialName("show_streak") val showStreak: Visibility = Visibility.FRIENDS,
    @SerialName("show_relapses") val showRelapses: Visibility = Visibility.FRIENDS,
    @SerialName("show_tier") val showTier: Visibility = Visibility.FRIENDS,
    @SerialName("allow_haha") val allowHaha: Boolean = true,
)

@Serializable
data class NotificationSettings(
    @SerialName("friend_requests") val friendRequests: Boolean = true,
    val milestones: Boolean = true,
    @SerialName("tier_changes") val tierChanges: Boolean = true,
    val reactions: Boolean = true,
)

/** One post, as returned by the `feed` and `user_posts` RPCs. */
@Serializable
data class FeedPost(
    val id: String,
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val type: EventType,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("local_date") val localDate: String,
    @SerialName("created_at") val createdAt: String,
    val tier: Rank? = null,
    @SerialName("tier_verified") val tierVerified: Boolean = false,
    @SerialName("reaction_counts") val reactionCounts: Map<ReactionType, Int> = emptyMap(),
    @SerialName("reaction_total") val reactionTotal: Int = 0,
    @SerialName("my_reaction") val myReaction: ReactionType? = null,
    @SerialName("allow_haha") val allowHaha: Boolean = true,
) {
    /** Up to three reaction types with the highest counts, for the summary row. */
    val topReactions: List<ReactionType>
        get() = reactionCounts.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<ReactionType, Int>> { it.value }.thenBy { it.key.ordinal })
            .take(TOP_REACTIONS)
            .map { it.key }

    /**
     * The post as it looks after the viewer sets their reaction to [reaction]
     * (null removes it). Used for optimistic UI.
     */
    fun withMyReaction(reaction: ReactionType?): FeedPost {
        if (reaction == myReaction) return this
        val counts = reactionCounts.toMutableMap()
        myReaction?.let { old -> counts[old] = ((counts[old] ?: 1) - 1).coerceAtLeast(0) }
        reaction?.let { new -> counts[new] = (counts[new] ?: 0) + 1 }
        val cleaned = counts.filterValues { it > 0 }
        return copy(myReaction = reaction, reactionCounts = cleaned, reactionTotal = cleaned.values.sum())
    }
}

/** A friend in the "Your bros" strip. Fields the friend keeps private are null. */
@Serializable
data class FriendRank(
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val tier: Rank? = null,
    @SerialName("tier_verified") val tierVerified: Boolean = false,
    @SerialName("streak_days") val streakDays: Int? = null,
)

@Serializable
data class FriendshipEntry(
    @SerialName("friendship_id") val friendshipId: String,
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val relation: FriendRelation,
)

@Serializable
data class UserSummary(
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Someone's profile as the viewer is allowed to see it (`get_profile`). */
@Serializable
data class PublicProfile(
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val tier: Rank? = null,
    @SerialName("streak_tier") val streakTier: Rank? = null,
    @SerialName("tier_verified") val tierVerified: Boolean = false,
    @SerialName("streak_days") val streakDays: Int? = null,
    @SerialName("longest_streak_days") val longestStreakDays: Int? = null,
    @SerialName("is_self") val isSelf: Boolean = false,
)

@Serializable
data class Reactor(
    @SerialName("user_id") val userId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val type: ReactionType,
)

/** What the onboarding flow collects, sent in one `complete_onboarding` call. */
data class OnboardingInput(
    val username: String,
    val displayName: String?,
    val timezone: String,
    /** ISO-8601 instant, or null for "today". */
    val streakStart: String?,
    val selfReport: Boolean,
    val showStreak: Visibility,
    val showRelapses: Visibility,
    val showTier: Visibility,
)

/** The user's own history, for the calendar. Dates are ISO local dates (yyyy-MM-dd). */
data class StreakHistory(val flaggedDates: Set<String> = emptySet(), val relapseDates: Set<String> = emptySet())

enum class ReportReason(val key: String) {
    HARASSMENT("harassment"),
    INAPPROPRIATE("inappropriate"),
    SPAM("spam"),
    IMPERSONATION("impersonation"),
    OTHER("other"),
}
