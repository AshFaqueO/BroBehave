package com.brobehave.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.brobehave.app.MainActivity
import com.brobehave.app.R
import com.brobehave.core.data.ProfileRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val CHANNEL_SOCIAL = "social"

/** Sends this device's FCM token to the server. Does nothing when Firebase is not configured. */
@Singleton
class PushTokenUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
) {
    // Firebase has deprecated registration tokens in favour of Firebase Installation IDs
    // (register()/onRegistered()). The token path still works and is the documented target
    // of the FCM HTTP v1 `token` field, so it is used until the server-side send API for
    // installation IDs is confirmed (tracked for Phase 5).
    @Suppress("DEPRECATION")
    suspend fun upload() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val token = suspendCancellableCoroutine<String?> { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
        token?.let { profileRepository.registerDeviceToken(it) }
    }
}

/**
 * Receives FCM pushes sent by the `push` Edge Function. The server sends data-only
 * messages with a ready-made title and body. Nothing here may ever log message contents
 * or tokens.
 */
@AndroidEntryPoint
class BroBehaveMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The current Firebase SDK marks onNewToken as deprecated, while Android lint still
    // requires it so refreshed tokens are not lost. Keep the override until the Firebase
    // docs name a replacement.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        scope.launch { profileRepository.registerDeviceToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: return
        val body = message.data["body"].orEmpty()
        // The runtime permission only exists on Android 13+; older versions grant it at install.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_SOCIAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            // Lock screen shows only that there is a notification, not what it says.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // One notification per post/sender (the server supplies the tag) so repeats replace each other.
        NotificationManagerCompat.from(this).notify(message.data["tag"] ?: title, 0, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_SOCIAL,
            getString(R.string.channel_social_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.channel_social_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
