package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SelectionRequestResolverTest {
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")

    @Test
    fun tapResolvesToSelectOrClear() {
        val cases = listOf(
            Triple(null, work, SelectionRequest.Select(work)),
            Triple(Selection(work, since), meeting, SelectionRequest.Select(meeting)),
            Triple(Selection(work, since), work, SelectionRequest.Clear),
        )
        cases.forEach { (current, tapped, expected) ->
            assertEquals("current=$current tapped=$tapped", expected, resolveTapRequest(current, tapped))
        }
    }
}
