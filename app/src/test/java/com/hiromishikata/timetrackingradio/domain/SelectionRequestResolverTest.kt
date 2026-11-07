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

    @Test
    fun cellVisualShowsSendingOnTheTappedCell() {
        data class Case(
            val cell: CellKey,
            val current: Selection?,
            val sending: SelectionRequest?,
            val expected: CellVisual,
        )
        val cases = listOf(
            Case(work, null, null, CellVisual.UNSELECTED),
            Case(work, Selection(work, since), null, CellVisual.SELECTED),
            Case(meeting, Selection(work, since), SelectionRequest.Select(meeting), CellVisual.SENDING),
            Case(work, Selection(work, since), SelectionRequest.Select(meeting), CellVisual.SELECTED),
            Case(work, Selection(work, since), SelectionRequest.Clear, CellVisual.SENDING),
            Case(meeting, null, SelectionRequest.Clear, CellVisual.UNSELECTED),
        )
        cases.forEach { case ->
            assertEquals(
                "cell=${case.cell} current=${case.current} sending=${case.sending}",
                case.expected,
                resolveCellVisual(case.cell, case.current, case.sending),
            )
        }
    }
}
