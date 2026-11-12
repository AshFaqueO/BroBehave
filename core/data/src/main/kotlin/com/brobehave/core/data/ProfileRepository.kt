package com.brobehave.core.data

import com.brobehave.core.model.NotificationSettings
import com.brobehave.core.model.OnboardingInput
import com.brobehave.core.model.PrivacySettings
import com.brobehave.core.model.Profile
import com.brobehave.core.model.Visibility
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface ProfileRepository {
    /** The signed-in user's profile from the local cache (null until first loaded). */
    val profile: Flow<Profile?>

    /** Fetches the profile from the server into the cache. The server value always wins. */
    suspend fun refresh(): Result<Profile>

    suspend fun completeOnboarding(input: OnboardingInput): Result<Unit>

    suspend fun isUsernameAvailable(username: String): Result<Boolean>

    suspend fun updateProfile(username: String, displayName: String): Result<Unit>

    suspend fun setSelfReportEnabled(enabled: Boolean): Result<Unit>

    suspend fun privacySettings(): Result<PrivacySettings>

    suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit>

    suspend fun notificationSettings(): Result<NotificationSettings>

    suspend fun updateNotificationSettings(settings: NotificationSettings): Result<Unit>

    /** Stores this device's FCM token so the server can push to it. */
    suspend fun registerDeviceToken(token: String): Result<Unit>
}

internal fun SupabaseClient.requireUserId(): String =
    auth.currentUserOrNull()?.id ?: throw DataException(DataError.NOT_SIGNED_IN)

private fun Visibility.key(): String = if (this == Visibility.FRIENDS) "friends" else "private"

internal class SupabaseProfileRepository @Inject constructor(
    private val client: SupabaseClient,
    private val localStore: LocalStore,
) : ProfileRepository {
    override val profile: Flow<Profile?> = localStore.observeProfile()

    override suspend fun refresh(): Result<Profile> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingle<Profile>()
            .also { localStore.saveProfile(it) }
    }

    override suspend fun completeOnboarding(input: OnboardingInput): Result<Unit> = attempt {
        client.postgrest.rpc(
            "complete_onboarding",
            buildJsonObject {
                put("p_username", input.username)
                put("p_timezone", input.timezone)
                put("p_age_confirmed", true)
                put("p_streak_start", input.streakStart)
                put("p_display_name", input.displayName)
                put("p_self_report", input.selfReport)
                put("p_show_streak", input.showStreak.key())
                put("p_show_relapses", input.showRelapses.key())
                put("p_show_tier", input.showTier.key())
            },
        )
        refresh().getOrThrow()
    }

    override suspend fun isUsernameAvailable(username: String): Result<Boolean> = attempt {
        client.postgrest.rpc("username_available", buildJsonObject { put("p_username", username) })
            .decodeAs<Boolean>()
    }

    override suspend fun updateProfile(username: String, displayName: String): Result<Unit> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("profiles").update(
            {
                set("username", username.lowercase())
                set("display_name", displayName.trim())
            },
        ) { filter { eq("id", userId) } }
        refresh().getOrThrow()
    }

    override suspend fun setSelfReportEnabled(enabled: Boolean): Result<Unit> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("profiles").update({ set("self_report_enabled", enabled) }) {
            filter { eq("id", userId) }
        }
        refresh().getOrThrow()
    }

    override suspend fun privacySettings(): Result<PrivacySettings> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("privacy_settings")
            .select { filter { eq("user_id", userId) } }
            .decodeSingle<PrivacySettings>()
    }

    override suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("privacy_settings").update(settings) { filter { eq("user_id", userId) } }
    }

    override suspend fun notificationSettings(): Result<NotificationSettings> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("notification_settings")
            .select { filter { eq("user_id", userId) } }
            .decodeSingle<NotificationSettings>()
    }

    override suspend fun updateNotificationSettings(settings: NotificationSettings): Result<Unit> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("notification_settings").update(settings) { filter { eq("user_id", userId) } }
    }

    override suspend fun registerDeviceToken(token: String): Result<Unit> = attempt {
        val userId = client.requireUserId()
        client.postgrest.from("device_tokens").upsert(
            buildJsonObject {
                put("user_id", userId)
                put("fcm_token", token)
            },
        ) { onConflict = "user_id,fcm_token" }
    }
}
