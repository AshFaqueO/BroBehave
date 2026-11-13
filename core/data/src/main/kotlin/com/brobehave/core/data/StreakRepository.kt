package com.brobehave.core.data

import com.brobehave.core.database.FlaggedDayDao
import com.brobehave.core.database.FlaggedDayEntity
import com.brobehave.core.database.HeartbeatDao
import com.brobehave.core.database.HeartbeatEntity
import com.brobehave.core.database.RelapseDao
import com.brobehave.core.database.RelapseEntity
import com.brobehave.core.model.FlagSource
import com.brobehave.core.model.Profile
import com.brobehave.core.model.Rank
import com.brobehave.core.model.StreakHistory
import com.brobehave.core.model.StreakMath
import com.brobehave.core.model.StreakSnapshot
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's own streak data. Offline-first: actions are written to Room and applied to
 * the cached profile immediately, then synced by WorkManager. The server value wins once
 * synced.
 */
interface StreakRepository {
    fun observeHistory(): Flow<StreakHistory>

    /** Resets the streak. [note] is private and never leaves the device. */
    suspend fun reportRelapse(note: String?)

    /**
     * Flags today (does not reset the streak). Returns false when today was already
     * flagged. [note] is private and never leaves the device.
     */
    suspend fun reportSlip(note: String?, source: FlagSource = FlagSource.SELF): Boolean

    /**
     * Records that automatic detection is running today (once per local day). The server
     * grants Verified only when every day in the window has one.
     */
    suspend fun recordHeartbeat()

    /** Pushes queued actions, then pulls history and the profile. */
    suspend fun sync(): Result<Unit>
}

@Serializable
private data class FlaggedDayRow(@SerialName("local_date") val localDate: String, val source: String)

@Serializable
private data class RelapseRow(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("local_date") val localDate: String,
)

internal class OfflineFirstStreakRepository @Inject constructor(
    private val flaggedDayDao: FlaggedDayDao,
    private val relapseDao: RelapseDao,
    private val heartbeatDao: HeartbeatDao,
    private val localStore: LocalStore,
    private val client: SupabaseClient,
    private val profileRepository: ProfileRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : StreakRepository {
    override fun observeHistory(): Flow<StreakHistory> =
        combine(flaggedDayDao.observeAll(), relapseDao.observeAll()) { flagged, relapses ->
            StreakHistory(
                flaggedDates = flagged.mapTo(mutableSetOf()) { it.localDate },
                relapseDates = relapses.mapTo(mutableSetOf()) { it.localDate },
            )
        }

    override suspend fun reportRelapse(note: String?) {
        val profile = localStore.profile() ?: return
        val now = clock.instant()
        val zone = StreakMath.zoneOf(profile.timezone)
        val streakDays = StreakMath.streakDays(StreakMath.parseInstant(profile.streakStart), zone, now)

        relapseDao.insertIfAbsent(
            RelapseEntity(
                id = UUID.randomUUID().toString(),
                createdAt = now.toString(),
                localDate = StreakMath.localDate(now, zone).toString(),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        // Instant UI: back to day 0 and Clown; the server recomputes the same on sync.
        localStore.saveProfile(
            profile.copy(
                streakStart = now.toString(),
                tier = Rank.CLOWN,
                streakTier = Rank.CLOWN,
                longestStreakDays = maxOf(profile.longestStreakDays, streakDays),
            ),
        )
        syncScheduler.requestSync()
    }

    override suspend fun reportSlip(note: String?, source: FlagSource): Boolean {
        val profile = localStore.profile() ?: return false
        val now = clock.instant()
        val today = StreakMath.localDate(now, StreakMath.zoneOf(profile.timezone))

        val inserted = flaggedDayDao.insertIfAbsent(
            FlaggedDayEntity(
                localDate = today.toString(),
                source = source.key,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            ),
        ) != ROW_IGNORED
        if (inserted) {
            applyLocalTier(profile)
            syncScheduler.requestSync()
        }
        return inserted
    }

    override suspend fun recordHeartbeat() {
        val profile = localStore.profile() ?: return
        val today = StreakMath.localDate(clock.instant(), StreakMath.zoneOf(profile.timezone))
        if (heartbeatDao.insertIfAbsent(HeartbeatEntity(today.toString())) != ROW_IGNORED) {
            syncScheduler.requestSync()
        }
    }

    private suspend fun applyLocalTier(profile: Profile) {
        val now = clock.instant()
        val today = StreakMath.localDate(now, StreakMath.zoneOf(profile.timezone))
        val flagged = flaggedDayDao
            .datesBetween(StreakMath.windowStart(today).toString(), today.toString())
            .map(LocalDate::parse)
        val snapshot = StreakSnapshot.of(profile, flagged, now)
        localStore.saveProfile(profile.copy(tier = snapshot.tier.effectiveRank, streakTier = snapshot.tier.streakRank))
    }

    override suspend fun sync(): Result<Unit> = attempt {
        val userId = client.requireUserId()
        pushRelapses(userId)
        pushFlaggedDays(userId)
        pushHeartbeats(userId)
        pullHistory()
        profileRepository.refresh().getOrThrow()
    }

    private suspend fun pushRelapses(userId: String) {
        relapseDao.unsynced().forEach { relapse ->
            pushOnce {
                client.postgrest.from("events").insert(
                    buildJsonObject {
                        put("id", relapse.id)
                        put("user_id", userId)
                        put("type", "relapse")
                        put("created_at", relapse.createdAt)
                        put("local_date", relapse.localDate)
                    },
                )
            }
            relapseDao.markSynced(relapse.id)
        }
    }

    private suspend fun pushFlaggedDays(userId: String) {
        flaggedDayDao.unsynced().forEach { day ->
            pushOnce {
                client.postgrest.from("flagged_days").insert(
                    buildJsonObject {
                        put("user_id", userId)
                        put("local_date", day.localDate)
                        put("source", day.source)
                    },
                )
            }
            flaggedDayDao.markSynced(day.localDate)
        }
    }

    private suspend fun pushHeartbeats(userId: String) {
        heartbeatDao.unsynced().forEach { heartbeat ->
            pushOnce {
                client.postgrest.from("detection_heartbeats").insert(
                    buildJsonObject {
                        put("user_id", userId)
                        put("local_date", heartbeat.localDate)
                    },
                )
            }
            heartbeatDao.markSynced(heartbeat.localDate)
        }
    }

    /**
     * Runs one upload. A rejection by the database (duplicate, too old, not allowed) is
     * final, so the row is treated as handled; anything else (no network) is rethrown
     * so the worker retries later.
     */
    private suspend fun pushOnce(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: PostgrestRestException) {
            // Final answer from the database; do not retry forever.
        }
    }

    private suspend fun pullHistory() {
        val flagged = client.postgrest.from("flagged_days").select().decodeList<FlaggedDayRow>()
        flaggedDayDao.insertAllIfAbsent(flagged.map { FlaggedDayEntity(it.localDate, it.source, synced = true) })

        val relapses = client.postgrest.from("events")
            .select { filter { eq("type", "relapse") } }
            .decodeList<RelapseRow>()
        relapseDao.insertAllIfAbsent(relapses.map { RelapseEntity(it.id, it.createdAt, it.localDate, synced = true) })
    }

    private companion object {
        /** Room returns -1 when an IGNORE insert hits an existing row. */
        const val ROW_IGNORED = -1L
    }
}
