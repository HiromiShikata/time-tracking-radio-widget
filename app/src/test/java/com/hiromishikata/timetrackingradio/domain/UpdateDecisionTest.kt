package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDecisionTest {
    private val withApk = LatestRelease(SemanticVersion(1, 0, 2), "https://example.com/app.apk", 4096L)
    private val withoutApk = LatestRelease(SemanticVersion(1, 0, 2), null, null)

    @Test
    fun decidesWhetherToAnnounceAnUpdate() {
        val cases = listOf(
            Triple(SemanticVersion(1, 0, 1), withApk, UpdateDecision.Announce(SemanticVersion(1, 0, 2), "https://example.com/app.apk", 4096L)),
            Triple(SemanticVersion(1, 0, 2), withApk, UpdateDecision.UpToDate),
            Triple(SemanticVersion(1, 0, 3), withApk, UpdateDecision.UpToDate),
            Triple(SemanticVersion(1, 0, 1), withoutApk, UpdateDecision.NewerWithoutApk(SemanticVersion(1, 0, 2))),
            Triple(SemanticVersion(1, 0, 2), withoutApk, UpdateDecision.UpToDate),
        )
        cases.forEach { (installed, latest, expected) ->
            assertEquals("installed=$installed latest=$latest", expected, decideUpdate(installed, latest))
        }
    }

    @Test
    fun aReleaseWithoutASizeIsNotAnnounced() {
        val withoutSize = LatestRelease(SemanticVersion(1, 0, 2), "https://example.com/app.apk", null)
        assertEquals(
            UpdateDecision.NewerWithoutApk(SemanticVersion(1, 0, 2)),
            decideUpdate(SemanticVersion(1, 0, 1), withoutSize),
        )
    }
}
