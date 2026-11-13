package com.brobehave.core.model

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The rank ladder lives in two places: the `tier_config` / `tier_settings` tables in
 * Postgres and [Rank] / [TierConfig] in Kotlin. This test reads the SQL migrations and
 * fails if the two ever disagree.
 */
class TierConfigSyncTest {
    /** Unit tests run with the module directory (core/model) as the working directory. */
    private val migrations = File("../../supabase/migrations").listFiles { f -> f.extension == "sql" }
        .orEmpty().sortedBy { it.name }

    @Test
    fun `the SQL tier_config rows match the Kotlin Rank enum exactly`() {
        assertTrue(migrations.isNotEmpty(), "migrations not found from ${File(".").absolutePath}")
        val inserts = migrations.map { it.readText() }.filter { "insert into public.tier_config" in it }
        assertEquals(1, inserts.size, "tier_config must be filled in exactly one migration")

        val block = inserts.single().substringAfter("insert into public.tier_config").substringBefore(";")
        val rows = Regex("""\('([a-z_]+)',\s*(\d+),\s*(\d+)\)""").findAll(block)
            .map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            .toList()

        assertEquals(Rank.entries.map { Triple(it.key, it.ordinal, it.minStreakDays) }, rows)
    }

    @Test
    fun `the SQL tier_settings defaults match TierConfig`() {
        val sql = migrations.joinToString("\n") { it.readText() }
        val penalty = Regex("""penalty_per_flagged_day integer not null default (\d+)""").find(sql)
        val window = Regex("""window_days integer not null default (\d+)""").find(sql)

        assertEquals(TierConfig.PENALTY_PER_FLAGGED_DAY, penalty?.groupValues?.get(1)?.toInt())
        assertEquals(TierConfig.WINDOW_DAYS, window?.groupValues?.get(1)?.toInt())
    }

    @Test
    fun `the Postgres tier enum lists the ranks in ladder order`() {
        val sql = migrations.first().readText()
        val enumBody = sql.substringAfter("create type public.tier as enum (").substringBefore(");")
        val values = Regex("'([a-z_]+)'").findAll(enumBody).map { it.groupValues[1] }.toList()

        assertEquals(Rank.entries.map { it.key }, values)
    }
}

/** The same boundary cases as supabase/tests/database/tiers.test.sql, computed locally. */
class LocalTierBoundaryTest {
    private fun ranks(timezone: String, streakStart: String, now: String, vararg flagged: String): String {
        val profile = Profile(id = "u", timezone = timezone, streakStart = streakStart)
        val snapshot = StreakSnapshot.of(profile, flagged.map(LocalDate::parse), Instant.parse(now))
        return "${snapshot.tier.streakRank.key}/${snapshot.tier.effectiveRank.key}"
    }

    @ParameterizedTest(name = "{0}: {1} -> {2} = {3}")
    @CsvSource(
        // UTC+6: 23:30 -> 00:30 local is one hour but a new day.
        "Asia/Dhaka, 2026-06-14T17:30:00Z, 2026-06-14T18:30:00Z, noob/noob",
        "America/New_York, 2026-06-14T17:30:00Z, 2026-06-14T18:30:00Z, clown/clown",
        "UTC, 2026-06-14T17:30:00Z, 2026-06-14T18:30:00Z, clown/clown",
        // UTC-4: local midnight is 04:00 UTC.
        "America/New_York, 2026-06-15T03:30:00Z, 2026-06-15T04:30:00Z, noob/noob",
        "UTC, 2026-06-15T03:30:00Z, 2026-06-15T04:30:00Z, clown/clown",
        // A whole local day minus a minute is still day 0.
        "Asia/Dhaka, 2026-06-14T18:00:00Z, 2026-06-15T17:59:00Z, clown/clown",
        "Asia/Dhaka, 2026-06-14T18:00:00Z, 2026-06-15T18:00:00Z, noob/noob",
        // UTC+5:45.
        "Asia/Kathmandu, 2026-06-14T18:10:00Z, 2026-06-14T18:20:00Z, noob/noob",
        // Day 29 -> 30 flips at local midnight.
        "Asia/Dhaka, 2026-05-16T06:00:00Z, 2026-06-14T17:59:00Z, advanced/advanced",
        "Asia/Dhaka, 2026-05-16T06:00:00Z, 2026-06-14T18:00:00Z, sigma/sigma",
    )
    fun `local midnight decides the day in every timezone`(zone: String, start: String, now: String, expected: String) {
        assertEquals(expected, ranks(zone, start, now))
    }

    @Test
    fun `the rolling window's edges match the server`() {
        val start = "2026-04-26T12:00:00Z"
        assertEquals("chad/advanced", ranks("UTC", start, "2026-06-15T12:00:00Z", "2026-06-15", "2026-06-14"))
        assertEquals("chad/sigma", ranks("UTC", start, "2026-06-15T12:00:00Z", "2026-06-09"), "6 days ago is in")
        assertEquals("chad/chad", ranks("UTC", start, "2026-06-15T12:00:00Z", "2026-06-08"), "7 days ago is out")
        assertEquals("chad/sigma", ranks("UTC", start, "2026-06-15T23:59:00Z", "2026-06-09"))
        assertEquals("chad/chad", ranks("UTC", start, "2026-06-16T00:00:00Z", "2026-06-09"), "ages out at midnight")
        assertEquals("chad/chad", ranks("Asia/Dhaka", start, "2026-06-15T18:30:00Z", "2026-06-09"), "already the 16th")
        assertEquals(
            "chad/sigma",
            ranks("America/New_York", start, "2026-06-16T03:00:00Z", "2026-06-09"),
            "still the 15th",
        )
        assertEquals("chad/chad", ranks("UTC", start, "2026-06-15T12:00:00Z", "2026-06-16"), "future flag ignored")
    }

    @Test
    fun `the penalty floors at Clown`() {
        val week = (9..15).map { "2026-06-%02d".format(it) }.toTypedArray()

        assertEquals("novice/clown", ranks("UTC", "2026-06-12T12:00:00Z", "2026-06-15T12:00:00Z", *week))
        assertEquals("giga_chad/noob", ranks("UTC", "2026-02-15T12:00:00Z", "2026-06-15T12:00:00Z", *week))
    }

    @Test
    fun `an unknown timezone falls back to UTC instead of crashing`() {
        assertEquals(ZoneId.of("UTC"), StreakMath.zoneOf("Mars/Olympus"))
    }
}
