package com.brobehave.app.distribution

import android.app.Activity
import com.brobehave.app.BuildConfig
import com.brobehave.core.data.AppPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads GitHub's "latest release" JSON. Release APKs are named
 * `BroBehave-<versionName>-<versionCode>.apk`; the version code in the file name is what
 * gets compared, so a release is only offered when it is genuinely newer.
 */
object GitHubReleaseParser {
    private val apkName = Regex("""^BroBehave-(.+)-(\d+)\.apk$""")

    fun newerRelease(json: String, currentVersionCode: Int): AvailableUpdate? = try {
        Json.parseToJsonElement(json).jsonObject["assets"]?.jsonArray.orEmpty()
            .mapNotNull { asset ->
                val name = asset.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                val url = asset.jsonObject["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                val match = apkName.matchEntire(name)
                val versionCode = match?.groupValues?.get(2)?.toIntOrNull()
                // Only ever offer a download from GitHub itself, over https.
                if (match == null || versionCode == null || !url.startsWith("https://github.com/")) {
                    null
                } else {
                    versionCode to AvailableUpdate(match.groupValues[1], url)
                }
            }
            .filter { (versionCode, _) -> versionCode > currentVersionCode }
            .maxByOrNull { (versionCode, _) -> versionCode }
            ?.second
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        // Valid JSON, but not shaped like a release.
        null
    }
}

/** GitHub flavor: checks the Releases page at most once a day and offers the new APK. */
@Singleton
class GitHubDistributionServices @Inject constructor(private val preferences: AppPreferences) : DistributionServices {
    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    override val availableUpdate: StateFlow<AvailableUpdate?> = _availableUpdate.asStateFlow()

    override fun dismissUpdate() {
        _availableUpdate.value = null
    }

    override suspend fun onMainShown(activity: Activity) {
        val today = LocalDate.now().toEpochDay()
        if (BuildConfig.GITHUB_REPO.isBlank() || preferences.lastUpdateCheckDay.first() >= today) return
        val json = fetchLatestRelease() ?: return
        preferences.setLastUpdateCheckDay(today)
        _availableUpdate.value = GitHubReleaseParser.newerRelease(json, BuildConfig.VERSION_CODE)
    }

    /** No account, token or device details are sent: just an anonymous request for public release data. */
    private suspend fun fetchLatestRelease(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "android-app-update-check")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface DistributionModule {
    @Binds
    fun bindDistributionServices(impl: GitHubDistributionServices): DistributionServices
}
