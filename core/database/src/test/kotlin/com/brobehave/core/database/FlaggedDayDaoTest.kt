package com.brobehave.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlaggedDayDaoTest {
    private lateinit var database: BroBehaveDatabase
    private lateinit var dao: FlaggedDayDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BroBehaveDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.flaggedDayDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun flaggingTheSameDayTwiceKeepsOneRowAndTheFirstSource() = runTest {
        dao.insertIfAbsent(FlaggedDayEntity("2026-11-12", "auto"))
        dao.insertIfAbsent(FlaggedDayEntity("2026-11-12", "self"))

        val days = dao.observeAll().first()
        assertEquals(1, days.size)
        assertEquals("auto", days.single().source)
    }

    @Test
    fun countBetweenIsInclusiveOnBothEnds() = runTest {
        listOf("2026-11-05", "2026-11-06", "2026-11-12", "2026-11-13").forEach {
            dao.insertIfAbsent(FlaggedDayEntity(it, "self"))
        }

        assertEquals(2, dao.countBetween("2026-11-06", "2026-11-12"))
    }

    @Test
    fun markSyncedRemovesTheDayFromTheUnsyncedQueue() = runTest {
        dao.insertIfAbsent(FlaggedDayEntity("2026-11-12", "self"))
        assertEquals(1, dao.unsynced().size)

        dao.markSynced("2026-11-12")

        assertTrue(dao.unsynced().isEmpty())
    }
}
