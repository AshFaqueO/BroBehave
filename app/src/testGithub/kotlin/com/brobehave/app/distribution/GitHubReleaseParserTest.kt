package com.brobehave.app.distribution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubReleaseParserTest {
    private fun release(vararg assets: Pair<String, String>) = """
        {"tag_name": "v1.2.0", "assets": [${
        assets.joinToString(",") { (name, url) -> """{"name": "$name", "browser_download_url": "$url"}""" }
    }]}
    """.trimIndent()

    private val base = "https://github.com/AshfaqueO/BroBehave/releases/download/v1.2.0"

    @Test
    fun `a release with a higher version code is offered`() {
        val json = release(
            "BroBehave-1.2.0-12.apk" to "$base/BroBehave-1.2.0-12.apk",
            "BroBehave-1.2.0-12.apk.sha256" to "$base/BroBehave-1.2.0-12.apk.sha256",
        )

        assertEquals(
            AvailableUpdate("1.2.0", "$base/BroBehave-1.2.0-12.apk"),
            GitHubReleaseParser.newerRelease(json, currentVersionCode = 11),
        )
    }

    @Test
    fun `the same or an older version code is not offered`() {
        val json = release("BroBehave-1.2.0-12.apk" to "$base/BroBehave-1.2.0-12.apk")

        assertNull(GitHubReleaseParser.newerRelease(json, currentVersionCode = 12))
        assertNull(GitHubReleaseParser.newerRelease(json, currentVersionCode = 40))
    }

    @Test
    fun `version names with dashes still parse and the highest code wins`() {
        val json = release(
            "BroBehave-1.3.0-rc-1-20.apk" to "$base/BroBehave-1.3.0-rc-1-20.apk",
            "BroBehave-1.2.9-19.apk" to "$base/BroBehave-1.2.9-19.apk",
        )

        assertEquals("1.3.0-rc-1", GitHubReleaseParser.newerRelease(json, 5)?.versionName)
    }

    @Test
    fun `downloads that are not https links on github are never offered`() {
        assertNull(
            GitHubReleaseParser.newerRelease(
                release("BroBehave-9.9.9-999.apk" to "http://github.com/x/BroBehave-9.9.9-999.apk"),
                1,
            ),
        )
        assertNull(
            GitHubReleaseParser.newerRelease(
                release("BroBehave-9.9.9-999.apk" to "https://evil.example/BroBehave-9.9.9-999.apk"),
                1,
            ),
        )
    }

    @Test
    fun `other files, missing assets and broken JSON give no update`() {
        assertNull(GitHubReleaseParser.newerRelease(release("notes.txt" to "$base/notes.txt"), 1))
        assertNull(GitHubReleaseParser.newerRelease("""{"tag_name": "v1"}""", 1))
        assertNull(GitHubReleaseParser.newerRelease("""{"assets": "nope"}""", 1))
        assertNull(GitHubReleaseParser.newerRelease("not json", 1))
        assertNull(GitHubReleaseParser.newerRelease("", 1))
    }
}
