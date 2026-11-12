package com.brobehave.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TierCalculatorTest {
    @ParameterizedTest(name = "{0} streak days -> {1}")
    @CsvSource(
        "0, CLOWN",
        "1, NOOB",
        "2, NOOB",
        "3, NOVICE",
        "6, NOVICE",
        "7, AVERAGE",
        "14, AVERAGE",
        "15, ADVANCED",
        "29, ADVANCED",
        "30, SIGMA",
        "44, SIGMA",
        "45, CHAD",
        "59, CHAD",
        "60, ABSOLUTE_CHAD",
        "119, ABSOLUTE_CHAD",
        "120, GIGA_CHAD",
        "5000, GIGA_CHAD",
    )
    fun `streak rank at every threshold boundary`(days: Int, expected: Rank) {
        assertEquals(expected, TierCalculator.streakRank(days))
    }

    @Test
    fun `flagged days pull the effective rank down without changing the streak rank`() {
        val result = TierCalculator.compute(streakDays = 50, flaggedDaysInWindow = 2)

        assertEquals(Rank.CHAD, result.streakRank)
        assertEquals(Rank.ADVANCED, result.effectiveRank)
        assertEquals(2, result.ranksLost)
    }

    @Test
    fun `effective rank never drops below Clown`() {
        val result = TierCalculator.compute(streakDays = 3, flaggedDaysInWindow = 7)

        assertEquals(Rank.NOVICE, result.streakRank)
        assertEquals(Rank.CLOWN, result.effectiveRank)
        assertEquals(2, result.ranksLost)
    }

    @Test
    fun `a fresh relapse is Clown with one day to Noob`() {
        val result = TierCalculator.compute(streakDays = 0, flaggedDaysInWindow = 0)

        assertEquals(Rank.CLOWN, result.effectiveRank)
        assertEquals(1, result.daysToNextRank)
    }

    @Test
    fun `days to next rank counts down and is null at the top`() {
        assertEquals(4, TierCalculator.compute(26, 0).daysToNextRank)
        assertNull(TierCalculator.compute(120, 0).daysToNextRank)
    }

    @Test
    fun `negative streaks are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TierCalculator.streakRank(-1) }
    }

    @Test
    fun `rank keys are unique and thresholds strictly increase`() {
        assertEquals(Rank.entries.size, Rank.entries.map { it.key }.toSet().size)
        Rank.entries.zipWithNext { lower, higher ->
            check(lower.minStreakDays < higher.minStreakDays) { "$lower !< $higher" }
        }
        assertEquals(Rank.SIGMA, Rank.fromKey("sigma"))
    }
}
