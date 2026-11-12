package com.brobehave.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Date arithmetic for streaks. All "days" are local calendar days in the user's timezone. */
object StreakMath {
    /** Parses a Postgres `timestamptz` as returned by the API. */
    fun parseInstant(timestamp: String): Instant = OffsetDateTime.parse(timestamp).toInstant()

    fun zoneOf(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    fun localDate(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

    /** Whole local days since the streak started; never negative. */
    fun streakDays(streakStart: Instant, zone: ZoneId, now: Instant): Int {
        val days = ChronoUnit.DAYS.between(localDate(streakStart, zone), localDate(now, zone))
        return days.coerceAtLeast(0).toInt()
    }

    /** First day of the rolling window that ends on [today]. */
    fun windowStart(today: LocalDate): LocalDate = today.minusDays((TierConfig.WINDOW_DAYS - 1).toLong())

    /** How many of [flaggedDates] fall inside the rolling window ending on [today]. */
    fun flaggedInWindow(flaggedDates: Collection<LocalDate>, today: LocalDate): Int {
        val start = windowStart(today)
        return flaggedDates.toSet().count { !it.isBefore(start) && !it.isAfter(today) }
    }

    /** Progress from the current streak rank toward the next one, 0..1 (1 at Giga Chad). */
    fun progressToNextRank(streakDays: Int): Float {
        val current = TierCalculator.streakRank(streakDays)
        val next = current.next ?: return 1f
        val span = next.minStreakDays - current.minStreakDays
        return ((streakDays - current.minStreakDays).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** Everything the Profile page needs about the streak, computed locally for instant UI. */
data class StreakSnapshot(val streakDays: Int, val tier: TierResult, val progressToNext: Float) {
    companion object {
        fun of(profile: Profile, flaggedDates: Collection<LocalDate>, now: Instant): StreakSnapshot {
            val zone = StreakMath.zoneOf(profile.timezone)
            val days = StreakMath.streakDays(StreakMath.parseInstant(profile.streakStart), zone, now)
            val flagged = StreakMath.flaggedInWindow(flaggedDates, StreakMath.localDate(now, zone))
            return StreakSnapshot(days, TierCalculator.compute(days, flagged), StreakMath.progressToNextRank(days))
        }
    }
}
