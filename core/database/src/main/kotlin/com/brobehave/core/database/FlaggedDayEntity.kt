package com.brobehave.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One flagged local day. Only the date and its source are ever synced — never domains,
 * counts, or times. [synced] is false until the row has reached the server.
 */
@Entity(tableName = "flagged_days")
data class FlaggedDayEntity(
    /** ISO-8601 local date (yyyy-MM-dd) in the user's timezone. */
    @PrimaryKey val localDate: String,
    /** "auto" or "self" — matches the Postgres `flag_source` enum. */
    val source: String,
    val synced: Boolean = false,
    /** Optional private note. Stays on this device; never uploaded, never logged. */
    val note: String? = null,
)

/** One relapse. Queued locally and inserted into `events` when online. */
@Entity(tableName = "relapses")
data class RelapseEntity(
    /** UUID generated on the device, reused as the server event id so retries are idempotent. */
    @PrimaryKey val id: String,
    /** ISO-8601 instant in UTC. */
    val createdAt: String,
    /** ISO-8601 local date in the user's timezone. */
    val localDate: String,
    val synced: Boolean = false,
    /** Optional private note. Stays on this device; never uploaded, never logged. */
    val note: String? = null,
)

/** One day on which automatic detection was running. Synced to `detection_heartbeats`. */
@Entity(tableName = "heartbeats")
data class HeartbeatEntity(@PrimaryKey val localDate: String, val synced: Boolean = false)

/** The signed-in user's profile, cached as JSON so the app works offline. Single row. */
@Entity(tableName = "own_profile")
data class OwnProfileEntity(@PrimaryKey val slot: Int = 0, val userId: String, val json: String)
