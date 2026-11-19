package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.JsonPrimitive
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
            withApk to LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/app.apk", null),
            withoutApk to LatestRelease(SemanticVersion(1, 0, 8), null, null),
            withoutAssets to LatestRelease(SemanticVersion(1, 0, 9), null, null),
        )
        cases.forEach { (body, expected) ->
            assertEquals("body=$body", expected, parseLatestRelease(body))
        }
    }

    @Test
    fun theApkAssetSizeIsParsed() {
        val body = """{"tag_name":"v1.0.7","assets":[{"name":"app-release.apk","browser_download_url":"https://example.com/app.apk","size":12345}]}"""
        assertEquals(
            LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/app.apk", 12345L),
            parseLatestRelease(body),
        )
    }

    @Test
    fun aMissingApkSizeIsNull() {
        val body = """{"tag_name":"v1.0.7","assets":[{"name":"app-release.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        assertEquals(
            LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/app.apk", null),
            parseLatestRelease(body),
        )
    }

    @Test
    fun rejectsReleaseWithoutParsableTag() {
        val cases = listOf("""{"assets":[]}""", """{"tag_name":"latest"}""", "not json")
        cases.forEach { body ->
            val thrown = runCatching { parseLatestRelease(body) }.exceptionOrNull()
            assertTrue("body=$body thrown=$thrown", thrown is MalformedResponseException)
        }
    }

    @Test
    fun parseLatestReleaseFillsTheChangeLinesFromTheReleaseBody() {
        val body = releaseJson(
            tag = "v1.0.7",
            body = "## What's Changed\n* feat: a by @u in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/1",
        )
        assertEquals(
            LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/v1.0.7.apk", 100L, listOf("feat: a")),
            parseLatestRelease(body),
        )
    }

    @Test
    fun parseReleaseListKeepsOnlyPublishedReleasesNewestFirst() {
        data class Case(val name: String, val releases: List<String>, val expectedVersions: List<SemanticVersion>)
        val cases = listOf(
            Case(
                "drafts and prereleases are dropped",
                listOf(
                    releaseJson("v1.0.1"),
                    releaseJson("v1.0.3", draft = true),
                    releaseJson("v1.0.2", prerelease = true),
                    releaseJson("v1.0.5"),
                    releaseJson("v1.0.4"),
                ),
                listOf(SemanticVersion(1, 0, 5), SemanticVersion(1, 0, 4), SemanticVersion(1, 0, 1)),
            ),
            Case(
                "output is sorted by semantic version, not by text",
                listOf(releaseJson("v1.0.1"), releaseJson("v1.0.10"), releaseJson("v1.0.2")),
                listOf(SemanticVersion(1, 0, 10), SemanticVersion(1, 0, 2), SemanticVersion(1, 0, 1)),
            ),
            Case(
                "only drafts gives an empty list",
                listOf(releaseJson("v1.0.1", draft = true)),
                emptyList(),
            ),
            Case("empty array gives an empty list", emptyList(), emptyList()),
        )
        cases.forEach { case ->
            val body = case.releases.joinToString(separator = ",", prefix = "[", postfix = "]")
            assertEquals(case.name, case.expectedVersions, parseReleaseList(body).map { it.version })
        }
    }

    @Test
    fun parseReleaseListParsesTheChangeLinesAndTheApkOfEachRelease() {
        val body = "[" + releaseJson(
            tag = "v1.0.2",
            body = "## What's Changed\n* fix: y by @a in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/13\n\n**Full Changelog**: https://github.com/HiromiShikata/time-tracking-radio-widget/compare/v1.0.1...v1.0.2",
        ) + "]"
        assertEquals(
            listOf(LatestRelease(SemanticVersion(1, 0, 2), "https://example.com/v1.0.2.apk", 100L, listOf("fix: y"))),
            parseReleaseList(body),
        )
    }

    @Test
    fun parseReleaseListRejectsMalformedInput() {
        val validElement = releaseJson("v1.0.1")
        val cases = listOf(
            "not json",
            """{"tag_name":"v1.0.1"}""",
            "[1]",
            """["v1.0.1"]""",
            "[$validElement,5]",
        )
        cases.forEach { body ->
            val thrown = runCatching { parseReleaseList(body) }.exceptionOrNull()
            assertTrue("body=$body thrown=$thrown", thrown is MalformedResponseException)
        }
    }

    @Test
    fun parseReleaseListSkipsNonVersionTagsAndKeepsAMissingOrNonStringTagAsAnError() {
        data class Case(val name: String, val body: String, val expectedVersions: List<SemanticVersion>?)
        val cases = listOf(
            Case(
                "a non-version tag is skipped between version tags",
                "[${releaseJson("v1.0.2")},${releaseJson("nightly")},${releaseJson("v1.0.1")}]",
                listOf(SemanticVersion(1, 0, 2), SemanticVersion(1, 0, 1)),
            ),
            Case(
                "a list of only non-version tags gives an empty list",
                "[${releaseJson("latest")},${releaseJson("docs-1")}]",
                emptyList(),
            ),
            Case(
                "a release without tag_name is malformed",
                """[{"draft":false,"prerelease":false,"assets":[]}]""",
                null,
            ),
            Case(
                "a release whose tag_name is not a string is malformed",
                """[{"tag_name":5,"draft":false,"prerelease":false,"assets":[]}]""",
                null,
            ),
        )
        cases.forEach { case ->
            val outcome = runCatching { parseReleaseList(case.body).map { it.version } }
            val thrown = outcome.exceptionOrNull()
            if (case.expectedVersions == null) {
                assertTrue("${case.name} thrown=$thrown", thrown is MalformedResponseException)
            } else {
                assertEquals("${case.name} thrown=$thrown", case.expectedVersions, outcome.getOrNull())
            }
        }
    }

    private fun releaseJson(
        tag: String,
        body: String? = null,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val bodyField = body?.let { ",\"body\":${JsonPrimitive(it)}" }.orEmpty()
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease$bodyField,""" +
            """"assets":[{"name":"app-release.apk","browser_download_url":"https://example.com/$tag.apk","size":100}]}"""
    }
}
