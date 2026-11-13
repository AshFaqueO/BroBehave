package com.brobehave.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.model.DetectionGateway
import com.brobehave.core.model.DetectionState
import com.brobehave.core.model.FeatureFlags
import com.brobehave.detection.engine.DomainMatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The single place that knows whether detection is running, and that starts and stops it. */
@Singleton
class DetectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    featureFlags: FeatureFlags,
) : DetectionGateway {
    override val available: Boolean = featureFlags.autoDetection

    private val _state = MutableStateFlow(DetectionState())
    override val state: StateFlow<DetectionState> = _state.asStateFlow()

    internal fun update(transform: (DetectionState) -> DetectionState) = _state.update(transform)

    /**
     * Starts the service. Only call after the user accepted the disclosure and Android's
     * VPN dialog (i.e. `VpnService.prepare(context) == null`).
     */
    fun start() {
        if (!available) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, DetectionVpnService::class.java).setAction(DetectionVpnService.ACTION_START),
        )
    }

    override fun stop() {
        context.startService(
            Intent(context, DetectionVpnService::class.java).setAction(DetectionVpnService.ACTION_STOP),
        )
    }
}

/** Loads the adult-domain list: a verified downloaded update if there is one, else the bundled list. */
@Singleton
class BlocklistStore @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile
    private var cached: DomainMatcher? = null

    /** Where a verified update is stored. */
    val updateFile: File get() = File(context.filesDir, FILE_NAME)

    suspend fun matcher(): DomainMatcher = cached ?: withContext(Dispatchers.IO) {
        val updated = updateFile.takeIf { it.exists() }?.let { DomainMatcher.fromBytes(it.readBytes(), EXTRA) }
        val matcher = updated
            ?: context.assets.open(FILE_NAME).use { DomainMatcher.fromBytes(it.readBytes(), EXTRA) }
            ?: DomainMatcher(LongArray(0), EXTRA)
        matcher.also { cached = it }
    }

    /** Forget the loaded list so the next [matcher] call picks up a new update. */
    fun invalidate() {
        cached = null
    }

    private companion object {
        const val FILE_NAME = "blocklist.bin"

        /**
         * DEBUG BUILDS ONLY: a made-up name that counts as listed, so detection can be tested
         * end to end without looking up a real adult site. Never present in release builds.
         */
        val EXTRA: List<String> = if (BuildConfig.DEBUG) listOf("detection-test.brobehave.invalid") else emptyList()
    }
}

/** After a reboot or an app update, turn detection back on — only if the user had it on and consent still stands. */
@AndroidEntryPoint
class DetectionBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var controller: DetectionController

    @Inject
    lateinit var preferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!relevant) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // prepare() == null means the user's VPN consent for this app is still in place.
                if (preferences.detectionEnabled.first() && VpnService.prepare(context) == null) controller.start()
            } finally {
                pending.finish()
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DetectionModule {
    @Binds
    fun bindDetectionGateway(impl: DetectionController): DetectionGateway
}
