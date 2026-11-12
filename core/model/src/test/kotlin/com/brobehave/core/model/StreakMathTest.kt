package com.brobehave.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreakMathTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `streak days count local calendar days, not 24-hour periods`() {
        // 23:30 Dhaka on the 10th -> 00:30 Dhaka on the 11th is one hour, but one local day.
        val start = Instant.parse("2026-11-10T17:30:00Z")
        val now = Instant.parse("2026-11-10T18:30:00Z")

        assertEquals(1, StreakMath.streakDays(start, dhaka, now))
        assertEquals(0, StreakMath.streakDays(start, newYork, now))
    }

    @Test
    fun `streak days are never negative`() {
        val start = Instant.parse("2026-11-12T00:00:00Z")
        assertEquals(0, StreakMath.streakDays(start, dhaka, Instant.parse("2026-11-10T00:00:00Z")))
    }

    @Test
    fun `postgres timestamps with offsets and microseconds parse`() {
        assertEquals(
            Instant.parse("2026-11-12T15:23:01.123456Z"),
            StreakMath.parseInstant("2026-11-12T15:23:01.123456+00:00"),
        )
    }

    @Test
    fun `flagged days are counted inside the 7-day window only, once per date`() {
        val today = LocalDate.of(2026, 11, 12)
        val flagged = listOf(
            today,
            today,
            today.minusDays(6),
            today.minusDays(7),
            today.plusDays(1),
        )

        assertEquals(2, StreakMath.flaggedInWindow(flagged, today))
    }

    @Test
    fun `progress to next rank moves from 0 to 1 within a rank`() {
        assertEquals(0f, StreakMath.progressToNextRank(30))
        assertEquals(0.2f, StreakMath.progressToNextRank(33), 0.001f)
        assertEquals(1f, StreakMath.progressToNextRank(500))
    }

    @Test
    fun `snapshot combines streak, flagged days and timezone`() {
        val profile = Profile(id = "u", timezone = "Asia/Dhaka", streakStart = "2026-09-23T10:00:00+00:00")
        val now = Instant.parse("2026-11-12T10:00:00Z")
        val today = LocalDate.of(2026, 11, 12)

        val snapshot = StreakSnapshot.of(profile, listOf(today, today.minusDays(1)), now)

        assertEquals(50, snapshot.streakDays)
        assertEquals(Rank.CHAD, snapshot.tier.streakRank)
        assertEquals(Rank.ADVANCED, snapshot.tier.effectiveRank)
    }

    @Test
    fun `optimistic reaction updates counts and total`() {
        val post = FeedPost(
            id = "e",
            userId = "u",
            type = EventType.MILESTONE,
            localDate = "2026-11-12",
            createdAt = "2026-11-12T00:00:00+00:00",
            reactionCounts = mapOf(ReactionType.FIRE to 2, ReactionType.HAHA to 1),
            reactionTotal = 3,
            myReaction = ReactionType.HAHA,
        )

        val changed = post.withMyReaction(ReactionType.FIRE)
        assertEquals(mapOf(ReactionType.FIRE to 3), changed.reactionCounts)
        assertEquals(3, changed.reactionTotal)
        assertEquals(ReactionType.FIRE, changed.myReaction)

        val removed = changed.withMyReaction(null)
        assertEquals(2, removed.reactionTotal)
        assertNull(removed.myReaction)
        assertEquals(listOf(ReactionType.FIRE), removed.topReactions)
    }
}
