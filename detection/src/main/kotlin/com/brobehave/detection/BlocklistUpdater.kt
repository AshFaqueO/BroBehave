package com.brobehave.detection

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brobehave.core.network.NetworkConfig
import com.brobehave.detection.engine.DomainMatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Describes one published blocklist. Served by the `blocklist` Edge Function. */
@Serializable
data class BlocklistManifest(
    /** Increases with every published list; older or equal versions are ignored. */
    val version: Long,
    val size: Int,
    /** Lowercase hex SHA-256 of the list file. */
    val sha256: String,
    /** Base64 ECDSA (P-256, SHA-256) signature of the list file. */
    val signature: String,
)

/**
 * Decides whether a downloaded list may replace the current one. An update is accepted
 * only when it is newer, has the stated size and hash, carries a valid signature from the
 * key built into the app, and parses as a blocklist. The hash catches corruption; the
 * signature is what proves the list really comes from the app's maintainers.
 */
object BlocklistVerifier {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun accept(manifest: BlocklistManifest, bytes: ByteArray, currentVersion: Long, publicKeyBase64: String): Boolean =
        manifest.version > currentVersion &&
            bytes.size == manifest.size &&
            bytes.size <= MAX_BYTES &&
            sha256Hex(bytes).equals(manifest.sha256, ignoreCase = true) &&
            signatureIsValid(bytes, manifest.signature, publicKeyBase64) &&
            DomainMatcher.fromBytes(bytes) != null

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun signatureIsValid(bytes: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        // Not valid base64.
        false
    }
}

enum class BlocklistUpdateResult { DISABLED, UNAVAILABLE, UP_TO_DATE, REJECTED, UPDATED }

/** Fetches, verifies and installs a newer blocklist. The bundled list stays as the fallback. */
@Singleton
class BlocklistUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BlocklistStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val versionFile get() = File(context.filesDir, "blocklist.version")

    suspend fun update(publicKey: String = BuildConfig.BLOCKLIST_PUBLIC_KEY): BlocklistUpdateResult =
        withContext(Dispatchers.IO) {
            if (publicKey.isBlank()) return@withContext BlocklistUpdateResult.DISABLED
            val manifest = fetch(MANIFEST_PATH, MANIFEST_MAX_BYTES)?.let(::parseManifest)
                ?: return@withContext BlocklistUpdateResult.UNAVAILABLE
            val current = versionFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            if (manifest.version <= current) return@withContext BlocklistUpdateResult.UP_TO_DATE
            val bytes = fetch(DOWNLOAD_PATH, BlocklistVerifier.MAX_BYTES)
                ?: return@withContext BlocklistUpdateResult.UNAVAILABLE
            if (!BlocklistVerifier.accept(manifest, bytes, current, publicKey)) {
                return@withContext BlocklistUpdateResult.REJECTED
            }
            install(bytes, manifest.version)
            BlocklistUpdateResult.UPDATED
        }

    private fun parseManifest(bytes: ByteArray): BlocklistManifest? = try {
        json.decodeFromString(BlocklistManifest.serializer(), bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    }

    /** Write to a temporary file first so a crash can never leave half a list in place. */
    private fun install(bytes: ByteArray, version: Long) {
        val temporary = File(context.filesDir, "blocklist.tmp")
        temporary.writeBytes(bytes)
        if (temporary.renameTo(store.updateFile) ||
            (store.updateFile.delete() && temporary.renameTo(store.updateFile))
        ) {
            versionFile.writeText(version.toString())
            store.invalidate()
        }
    }

    private fun fetch(path: String, maxBytes: Int): ByteArray? {
        val connection = try {
            URL(NetworkConfig.SUPABASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("apikey", NetworkConfig.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${NetworkConfig.SUPABASE_ANON_KEY}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input -> readAtMost(input, maxBytes) }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** The whole body, or null when it is larger than [maxBytes] (never a silently cut-off list). */
    private fun readAtMost(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return out.toByteArray()
            out.write(chunk, 0, read)
            if (out.size() > maxBytes) return null
        }
    }

    companion object {
        private const val CHUNK_BYTES = 16 * 1024
        private const val MANIFEST_PATH = "/functions/v1/blocklist"
        private const val DOWNLOAD_PATH = "/functions/v1/blocklist?download=1"
        private const val MANIFEST_MAX_BYTES = 16 * 1024
        private const val TIMEOUT_MILLIS = 20_000
        private const val WORK_NAME = "blocklist-update"
        private const val INTERVAL_DAYS = 7L

        /** Checks for a newer list about once a week while detection is in use. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

@HiltWorker
internal class BlocklistUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: BlocklistUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (updater.update()) {
        BlocklistUpdateResult.UNAVAILABLE -> Result.retry()
        // A rejected list is not retried: the next weekly run will look again.
        else -> Result.success()
    }
}
