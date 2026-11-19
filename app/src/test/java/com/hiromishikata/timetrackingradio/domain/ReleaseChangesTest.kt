package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseChangesTest {
    private val generatedNotes = """
        ## What's Changed
        * feat: add x by @user in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/12
        * fix: y by @a in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/13

        ## New Contributors
        * @a made their first contribution in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/13

        **Full Changelog**: https://github.com/HiromiShikata/time-tracking-radio-widget/compare/v1.0.20...v1.0.21
    """.trimIndent()

    @Test
    fun parsesTheChangeLinesOfAReleaseBody() {
        data class Case(val name: String, val body: String?, val expected: List<String>)
        val cases = listOf(
            Case("generated release notes", generatedNotes, listOf("feat: add x", "fix: y")),
            Case("null body", null, emptyList()),
            Case("blank body", " \n\t\n", emptyList()),
            Case(
                "heading and changelog link only",
                "## What's Changed\n\n**Full Changelog**: https://github.com/HiromiShikata/time-tracking-radio-widget/compare/v1.0.0...v1.0.1",
                emptyList(),
            ),
            Case(
                "dash bullet with an author suffix",
                "- chore: bump deps by @bot in https://github.com/HiromiShikata/time-tracking-radio-widget/pull/3",
                listOf("chore: bump deps"),
            ),
            Case("plain line without a bullet is kept", "Plain note", listOf("Plain note")),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, parseReleaseChangeLines(case.body))
        }
    }

    @Test
    fun composesEachVersionFollowedByItsChangesNewestFirst() {
        data class Case(val name: String, val releases: List<ReleaseChanges>, val expected: String)
        val cases = listOf(
            Case(
                "newest first with changes and one without",
                listOf(
                    ReleaseChanges(SemanticVersion(1, 0, 3), listOf("Fix A", "Fix B")),
                    ReleaseChanges(SemanticVersion(1, 0, 2), listOf("Add C")),
                    ReleaseChanges(SemanticVersion(1, 0, 1), emptyList()),
                ),
                "v1.0.3\n- Fix A\n- Fix B\nv1.0.2\n- Add C\nv1.0.1",
            ),
            Case("no releases gives an empty string", emptyList(), ""),
            Case(
                "one release without changes",
                listOf(ReleaseChanges(SemanticVersion(2, 0, 0), emptyList())),
                "v2.0.0",
            ),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, composeUpdateNotificationText(case.releases))
        }
    }
}
