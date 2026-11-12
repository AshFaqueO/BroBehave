package com.brobehave.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for the user's queued actions to be uploaded as soon as there is a connection. */
interface SyncScheduler {
    fun requestSync()
}

internal class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    SyncScheduler {
    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: an action taken while a sync is running still gets its own run.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "streak-sync"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Uploads queued relapses and flagged days, then refreshes local data. Retries when offline. */
@HiltWorker
internal class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val streakRepository: StreakRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val failure = streakRepository.sync().exceptionOrNull() ?: return Result.success()
        return when (failure.dataError()) {
            DataError.NETWORK -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            // Signed out or rejected: retrying will not help.
            else -> Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DataModule {
    @Binds
    @Singleton
    fun bindAuthRepository(impl: SupabaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    fun bindProfileRepository(impl: SupabaseProfileRepository): ProfileRepository

    @Binds
    @Singleton
    fun bindStreakRepository(impl: OfflineFirstStreakRepository): StreakRepository

    @Binds
    @Singleton
    fun bindSocialRepository(impl: SupabaseSocialRepository): SocialRepository

    @Binds
    fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    companion object {
        @Provides
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
