package com.brobehave.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.brobehave.core.database.FlaggedDayDao
import com.brobehave.core.database.OwnProfileDao
import com.brobehave.core.database.OwnProfileEntity
import com.brobehave.core.database.RelapseDao
import com.brobehave.core.model.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** The cached copy of the signed-in user's profile and their offline data. */
@Singleton
internal class LocalStore @Inject constructor(
    private val profileDao: OwnProfileDao,
    private val flaggedDayDao: FlaggedDayDao,
    private val relapseDao: RelapseDao,
    private val json: Json,
) {
    fun observeProfile(): Flow<Profile?> = profileDao.observe()
        .map { it?.let(::decode) }
        .distinctUntilChanged()

    suspend fun profile(): Profile? = profileDao.get()?.let(::decode)

    suspend fun saveProfile(profile: Profile) {
        profileDao.upsert(
            OwnProfileEntity(userId = profile.id, json = json.encodeToString(Profile.serializer(), profile)),
        )
    }

    /** Wipes everything that belongs to the signed-in user (sign-out, account deletion). */
    suspend fun clear() {
        profileDao.clear()
        flaggedDayDao.clear()
        relapseDao.clear()
    }

    private fun decode(entity: OwnProfileEntity): Profile? =
        runCatching { json.decodeFromString(Profile.serializer(), entity.json) }.getOrNull()
}

private val Context.dataStore by preferencesDataStore(name = "brobehave_prefs")

/** Small device-level preferences that are not tied to an account. */
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    /** True once the intro was seen and the 18+ confirmation given on this device. */
    val introCompleted: Flow<Boolean> = context.dataStore.data.map { it[INTRO_COMPLETED] ?: false }

    /** True once the notification permission explainer was shown (ask only once). */
    val notificationPromptShown: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATION_PROMPT] ?: false }

    /** Ordinal of the highest rank already celebrated, so each rank-up is celebrated once. */
    val celebratedRank: Flow<Int> = context.dataStore.data.map { it[CELEBRATED_RANK] ?: -1 }

    suspend fun setCelebratedRank(ordinal: Int) {
        context.dataStore.edit { it[CELEBRATED_RANK] = ordinal }
    }

    suspend fun setIntroCompleted() {
        context.dataStore.edit { it[INTRO_COMPLETED] = true }
    }

    suspend fun setNotificationPromptShown() {
        context.dataStore.edit { it[NOTIFICATION_PROMPT] = true }
    }

    private companion object {
        val INTRO_COMPLETED = booleanPreferencesKey("intro_completed")
        val NOTIFICATION_PROMPT = booleanPreferencesKey("notification_prompt_shown")
        val CELEBRATED_RANK = androidx.datastore.preferences.core.intPreferencesKey("celebrated_rank")
    }
}
