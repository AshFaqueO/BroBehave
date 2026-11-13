package com.brobehave.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FlaggedDayDao {
    /** Inserts a flagged day; a day that is already flagged is left untouched (daily dedupe). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(day: FlaggedDayEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(days: List<FlaggedDayEntity>)

    @Query("SELECT * FROM flagged_days ORDER BY localDate DESC")
    fun observeAll(): Flow<List<FlaggedDayEntity>>

    @Query("SELECT COUNT(*) FROM flagged_days WHERE localDate >= :fromInclusive AND localDate <= :toInclusive")
    suspend fun countBetween(fromInclusive: String, toInclusive: String): Int

    @Query("SELECT localDate FROM flagged_days WHERE localDate >= :fromInclusive AND localDate <= :toInclusive")
    suspend fun datesBetween(fromInclusive: String, toInclusive: String): List<String>

    @Query("SELECT * FROM flagged_days WHERE synced = 0")
    suspend fun unsynced(): List<FlaggedDayEntity>

    @Query("UPDATE flagged_days SET synced = 1 WHERE localDate = :localDate")
    suspend fun markSynced(localDate: String)

    @Query("DELETE FROM flagged_days")
    suspend fun clear()
}

@Dao
interface RelapseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(relapse: RelapseEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(relapses: List<RelapseEntity>)

    @Query("SELECT * FROM relapses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RelapseEntity>>

    @Query("SELECT * FROM relapses WHERE synced = 0 ORDER BY createdAt ASC")
    suspend fun unsynced(): List<RelapseEntity>

    @Query("UPDATE relapses SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM relapses")
    suspend fun clear()
}

@Dao
interface HeartbeatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(heartbeat: HeartbeatEntity): Long

    @Query("SELECT * FROM heartbeats WHERE synced = 0")
    suspend fun unsynced(): List<HeartbeatEntity>

    @Query("UPDATE heartbeats SET synced = 1 WHERE localDate = :localDate")
    suspend fun markSynced(localDate: String)

    @Query("DELETE FROM heartbeats")
    suspend fun clear()
}

@Dao
interface OwnProfileDao {
    @Query("SELECT * FROM own_profile WHERE slot = 0")
    fun observe(): Flow<OwnProfileEntity?>

    @Query("SELECT * FROM own_profile WHERE slot = 0")
    suspend fun get(): OwnProfileEntity?

    @Upsert
    suspend fun upsert(profile: OwnProfileEntity)

    @Query("DELETE FROM own_profile")
    suspend fun clear()
}
