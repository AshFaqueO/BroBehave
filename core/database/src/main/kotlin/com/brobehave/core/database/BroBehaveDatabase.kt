package com.brobehave.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Source of truth for the user's own data (offline-first). */
@Database(
    entities = [FlaggedDayEntity::class, RelapseEntity::class, OwnProfileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BroBehaveDatabase : RoomDatabase() {
    abstract fun flaggedDayDao(): FlaggedDayDao

    abstract fun relapseDao(): RelapseDao

    abstract fun ownProfileDao(): OwnProfileDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BroBehaveDatabase =
        Room.databaseBuilder(context, BroBehaveDatabase::class.java, "brobehave.db").build()

    @Provides
    fun provideFlaggedDayDao(database: BroBehaveDatabase): FlaggedDayDao = database.flaggedDayDao()

    @Provides
    fun provideRelapseDao(database: BroBehaveDatabase): RelapseDao = database.relapseDao()

    @Provides
    fun provideOwnProfileDao(database: BroBehaveDatabase): OwnProfileDao = database.ownProfileDao()
}
