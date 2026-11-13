package com.brobehave.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brobehave.core.database.BroBehaveDatabase
import com.brobehave.core.model.FlagSource
import com.brobehave.core.model.FriendRelation
import com.brobehave.core.model.OnboardingInput
import com.brobehave.core.model.Rank
import com.brobehave.core.model.ReactionType
import com.brobehave.core.model.Visibility
import com.brobehave.core.network.SupabaseModule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real repositories against a local Supabase stack with the seed data, to
 * prove the Kotlin wire format matches the database (enums, nulls, RPC parameters).
 *
 * Runs only when SUPABASE_TEST_URL and SUPABASE_TEST_ANON_KEY are set, e.g.
 *   SUPABASE_TEST_URL=http://127.0.0.1:54321 SUPABASE_TEST_ANON_KEY=<anon key> ./gradlew :core:data:test
 * after `npm run db:reset`. Otherwise every test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class SupabaseIntegrationTest {
    private val url = System.getenv("SUPABASE_TEST_URL")
    private val anonKey = System.getenv("SUPABASE_TEST_ANON_KEY")

    private lateinit var client: SupabaseClient
    private lateinit var database: BroBehaveDatabase
    private lateinit var auth: AuthRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var social: SocialRepository
    private lateinit var streak: StreakRepository

    @Before
    fun setUp() {
        assumeTrue("local Supabase not configured", !url.isNullOrBlank() && !anonKey.isNullOrBlank())
        client = createSupabaseClient(url!!, anonKey!!) {
            defaultSerializer = KotlinXSerializer(SupabaseModule.json)
            // Robolectric has no app startup, so the default (SharedPreferences) session store is unavailable.
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BroBehaveDatabase::class.java,
        ).allowMainThreadQueries().build()
        val store = LocalStore(
            database.ownProfileDao(),
            database.flaggedDayDao(),
            database.relapseDao(),
            database.heartbeatDao(),
            SupabaseModule.json,
        )
        auth = SupabaseAuthRepository(client, store)
        profiles = SupabaseProfileRepository(client, store)
        social = SupabaseSocialRepository(client)
        streak = OfflineFirstStreakRepository(
            database.flaggedDayDao(),
            database.relapseDao(),
            database.heartbeatDao(),
            store,
            client,
            profiles,
            object : SyncScheduler {
                override fun requestSync() = Unit
            },
            Clock.systemUTC(),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::client.isInitialized) runBlocking { runCatching { client.auth.signOut() } }
    }

    @Test
    fun seedUserReadsProfileSettingsFriendsAndFeed() = runBlocking {
        auth.signIn("tanim@example.com", "password123").getOrThrow()

        val profile = profiles.refresh().getOrThrow()
        assertEquals("tanim", profile.username)
        assertTrue(profile.isOnboarded)
        assertEquals(profile, profiles.profile.first())

        assertEquals(Visibility.FRIENDS, profiles.privacySettings().getOrThrow().showStreak)
        assertTrue(profiles.notificationSettings().getOrThrow().reactions)

        val ranks = social.friendsRanks().getOrThrow()
        assertEquals(setOf("rafi", "sam"), ranks.map { it.username }.toSet())
        assertEquals(Rank.CHAD, ranks.first().tier)
        assertEquals(50, ranks.first().streakDays)

        val friendships = social.friendships().getOrThrow()
        assertTrue(friendships.all { it.relation == FriendRelation.FRIEND })

        val feed = social.feed().getOrThrow()
        assertTrue(feed.isNotEmpty())
        assertTrue(feed.none { it.userId == profile.id })

        val rafi = ranks.first { it.username == "rafi" }
        assertEquals("rafi", social.profile(rafi.userId).getOrThrow()?.username)
        assertTrue(social.userPosts(rafi.userId).getOrThrow().isNotEmpty())
        assertEquals(listOf("rafi"), social.searchUsers("ra").getOrThrow().map { it.username })
        assertFalse(profiles.isUsernameAvailable("rafi").getOrThrow())
    }

    @Test
    fun reactionCanBeSetChangedAndRemoved() = runBlocking {
        auth.signIn("tanim@example.com", "password123").getOrThrow()
        fun first() = runBlocking { social.feed().getOrThrow().first() }

        // Start from a clean slate in case an earlier run left a reaction behind.
        first().let { if (it.myReaction != null) social.setReaction(it, null).getOrThrow() }

        social.setReaction(first(), ReactionType.FIRE).getOrThrow()
        assertEquals(ReactionType.FIRE, first().myReaction)
        assertEquals(1, first().reactionCounts[ReactionType.FIRE])

        social.setReaction(first(), ReactionType.RESPECT).getOrThrow()
        assertEquals(ReactionType.RESPECT, first().myReaction)
        assertNull(first().reactionCounts[ReactionType.FIRE])
        assertEquals(listOf(ReactionType.RESPECT), social.reactors(first().id).getOrThrow().map { it.type })

        social.setReaction(first(), null).getOrThrow()
        assertNull(first().myReaction)
    }

    @Test
    fun newUserOnboardsSlipsRelapsesAndSyncs() = runBlocking {
        val name = "it_" + System.currentTimeMillis().toString().takeLast(9)
        assertEquals(SignUpOutcome.SIGNED_IN, auth.signUp("$name@example.com", "password123").getOrThrow())
        assertFalse(profiles.refresh().getOrThrow().isOnboarded)

        profiles.completeOnboarding(
            OnboardingInput(
                username = name,
                displayName = null,
                timezone = "Asia/Dhaka",
                streakStart = Clock.systemUTC().instant().minusSeconds(SECONDS_PER_DAY * 20).toString(),
                selfReport = true,
                showStreak = Visibility.FRIENDS,
                showRelapses = Visibility.PRIVATE,
                showTier = Visibility.FRIENDS,
            ),
        ).getOrThrow()
        val onboarded = profiles.profile.first()
        assertNotNull(onboarded)
        assertEquals(Rank.ADVANCED, onboarded!!.tier)
        assertEquals(Visibility.PRIVATE, profiles.privacySettings().getOrThrow().showRelapses)

        // A slip: instant local rank drop, confirmed by the server after sync.
        assertTrue(streak.reportSlip(note = "private note"))
        assertFalse("one flag per day", streak.reportSlip(note = null))
        assertEquals(Rank.AVERAGE, profiles.profile.first()!!.tier)
        streak.sync().getOrThrow()
        val afterSlip = profiles.profile.first()!!
        assertEquals(Rank.AVERAGE, afterSlip.tier)
        assertEquals(Rank.ADVANCED, afterSlip.streakTier)
        assertEquals(1, streak.observeHistory().first().flaggedDates.size)

        // Automatic detection: a flag with source "auto" needs no self-report, and one
        // heartbeat alone does not earn Verified (that takes every day of the window).
        streak.recordHeartbeat()
        streak.recordHeartbeat()
        assertFalse("auto flag on an already flagged day is a no-op", streak.reportSlip(null, FlagSource.AUTO))
        streak.sync().getOrThrow()
        assertFalse(profiles.profile.first()!!.tierVerified)

        // A relapse: back to Clown locally at once, and on the server after sync.
        streak.reportRelapse(note = null)
        assertEquals(Rank.CLOWN, profiles.profile.first()!!.tier)
        streak.sync().getOrThrow()
        val afterRelapse = profiles.profile.first()!!
        assertEquals(Rank.CLOWN, afterRelapse.tier)
        assertEquals(20, afterRelapse.longestStreakDays)
        assertEquals(1, streak.observeHistory().first().relapseDates.size)

        // Own posts: streak started (onboarding), tier down (slip), relapse, streak started again.
        val posts = social.userPosts(afterRelapse.id).getOrThrow()
        assertEquals(4, posts.size)

        auth.signOut().getOrThrow()
        assertNull(profiles.profile.first())
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
