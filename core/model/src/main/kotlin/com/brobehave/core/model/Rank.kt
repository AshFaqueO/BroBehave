package com.brobehave.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The 9-step rank ladder, lowest to highest. [minStreakDays] mirrors the `tier_config`
 * table in Postgres (see supabase/migrations); a test asserts the two stay in sync.
 *
 * [key] is the value of the Postgres `tier` enum.
 */
@Serializable
enum class Rank(val key: String, val minStreakDays: Int) {
    @SerialName("clown")
    CLOWN("clown", 0),

    @SerialName("noob")
    NOOB("noob", 1),

    @SerialName("novice")
    NOVICE("novice", 3),

    @SerialName("average")
    AVERAGE("average", 7),

    @SerialName("advanced")
    ADVANCED("advanced", 15),

    @SerialName("sigma")
    SIGMA("sigma", 30),

    @SerialName("chad")
    CHAD("chad", 45),

    @SerialName("absolute_chad")
    ABSOLUTE_CHAD("absolute_chad", 60),

    @SerialName("giga_chad")
    GIGA_CHAD("giga_chad", 120),
    ;

    /** The next rank up, or null at the top of the ladder. */
    val next: Rank? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromKey(key: String): Rank? = entries.firstOrNull { it.key == key }
    }
}
