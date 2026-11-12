package com.brobehave.core.model

/** Mirrors the single-row `tier_settings` table in Postgres. */
object TierConfig {
    /** Ranks lost per flagged day inside the rolling window. */
    const val PENALTY_PER_FLAGGED_DAY = 1

    /** Length of the rolling window, in local days, ending today. */
    const val WINDOW_DAYS = 7
}

data class TierResult(
    /** Rank earned by the streak alone. */
    val streakRank: Rank,
    /** Rank shown on the badge: streak rank minus the flagged-day penalty, floored at Clown. */
    val effectiveRank: Rank,
    /** Ranks actually lost to flagged days (0 when none). */
    val ranksLost: Int,
    /** Days until the streak earns the next rank, or null at Giga Chad. */
    val daysToNextRank: Int?,
)

/**
 * Local tier computation for instant UI. The server computes the same thing in SQL
 * and its value always wins once synced.
 */
object TierCalculator {
    fun streakRank(streakDays: Int): Rank {
        require(streakDays >= 0) { "streakDays must not be negative" }
        return Rank.entries.last { streakDays >= it.minStreakDays }
    }

    fun compute(streakDays: Int, flaggedDaysInWindow: Int): TierResult {
        require(flaggedDaysInWindow in 0..TierConfig.WINDOW_DAYS) {
            "flaggedDaysInWindow must be within the window"
        }
        val streakRank = streakRank(streakDays)
        val penalty = flaggedDaysInWindow * TierConfig.PENALTY_PER_FLAGGED_DAY
        val effectiveOrdinal = (streakRank.ordinal - penalty).coerceAtLeast(0)
        return TierResult(
            streakRank = streakRank,
            effectiveRank = Rank.entries[effectiveOrdinal],
            ranksLost = streakRank.ordinal - effectiveOrdinal,
            daysToNextRank = streakRank.next?.let { it.minStreakDays - streakDays },
        )
    }
}
