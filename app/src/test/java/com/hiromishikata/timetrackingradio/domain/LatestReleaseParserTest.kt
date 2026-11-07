package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleaseParserTest {
    @Test
    fun parsesTagAndApkAsset() {
        val withApk = """{"tag_name":"v1.0.7","assets":[{"name":"checksums.txt","browser_download_url":"https://example.com/c.txt"},{"name":"app-release.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        val withoutApk = """{"tag_name":"v1.0.8","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n.txt"}]}"""
        val withoutAssets = """{"tag_name":"v1.0.9"}"""
        val cases = listOf(
            withApk to LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/app.apk"),
            withoutApk to LatestRelease(SemanticVersion(1, 0, 8), null),
            withoutAssets to LatestRelease(SemanticVersion(1, 0, 9), null),
        )
        cases.forEach { (body, expected) ->
            assertEquals("body=$body", expected, parseLatestRelease(body))
        }
    }

    @Test
    fun rejectsReleaseWithoutParsableTag() {
        val cases = listOf("""{"assets":[]}""", """{"tag_name":"latest"}""", "not json")
        cases.forEach { body ->
            val thrown = runCatching { parseLatestRelease(body) }.exceptionOrNull()
            assertTrue("body=$body thrown=$thrown", thrown is MalformedResponseException)
        }
    }
}
