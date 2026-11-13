package com.brobehave.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source of truth for the user's own data (offline-first). */
@Database(
    entities = [
        FlaggedDayEntity::class,
        RelapseEntity::class,
        OwnProfileEntity::class,
        HeartbeatEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class BroBehaveDatabase : RoomDatabase() {
    abstract fun flaggedDayDao(): FlaggedDayDao

    abstract fun relapseDao(): RelapseDao

    abstract fun ownProfileDao(): OwnProfileDao

    abstract fun heartbeatDao(): HeartbeatDao

    companion object {
        /** v2 adds the heartbeats table (automatic detection). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `heartbeats` " +
                        "(`localDate` TEXT NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`localDate`))",
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BroBehaveDatabase =
        Room.databaseBuilder(context, BroBehaveDatabase::class.java, "brobehave.db")
            .addMigrations(BroBehaveDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideFlaggedDayDao(database: BroBehaveDatabase): FlaggedDayDao = database.flaggedDayDao()

    @Provides
    fun provideRelapseDao(database: BroBehaveDatabase): RelapseDao = database.relapseDao()

    @Provides
    fun provideHeartbeatDao(database: BroBehaveDatabase): HeartbeatDao = database.heartbeatDao()

    @Provides
    fun provideOwnProfileDao(database: BroBehaveDatabase): OwnProfileDao = database.ownProfileDao()
}
