package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WidgetSnapshotCellMarkTest {
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val tapAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val projectBWork = CellKey("project-b", ActivityKind.WORK)
    private val cells = listOf(work, meeting, projectBWork)

    private fun snapshotOf(confirmed: CellKey?, unsent: SelectionRequest?): WidgetSnapshot = WidgetSnapshot(
        selection = confirmed?.let { Selection(it, since) },
        fetchedAt = since,
        outcome = WidgetOutcome.Settled,
        sending = null,
        statusRequest = null,
        latestUnconfirmedTap = unsent?.let { UnconfirmedTap(it, tapAt) },
    )

    @Test
    fun theConfirmedCellIsFilledAndTheLatestUnconfirmedTapIsDashed() {
        val cases = listOf(
            Triple("nothing selected or pending", snapshotOf(null, null), mapOf(work to CellMark.EMPTY, meeting to CellMark.EMPTY, projectBWork to CellMark.EMPTY)),
            Triple("confirmed cell filled with nothing pending", snapshotOf(work, null), mapOf(work to CellMark.FILLED, meeting to CellMark.EMPTY, projectBWork to CellMark.EMPTY)),
            Triple("a switch keeps the confirmed cell filled and dashes the tapped one", snapshotOf(work, SelectionRequest.Select(meeting)), mapOf(work to CellMark.FILLED, meeting to CellMark.UNSENT, projectBWork to CellMark.EMPTY)),
            Triple("a first tap with nothing confirmed only dashes the tapped cell", snapshotOf(null, SelectionRequest.Select(meeting)), mapOf(work to CellMark.EMPTY, meeting to CellMark.UNSENT, projectBWork to CellMark.EMPTY)),
            Triple("an unconfirmed clear dashes the confirmed cell", snapshotOf(meeting, SelectionRequest.Clear), mapOf(work to CellMark.EMPTY, meeting to CellMark.UNSENT, projectBWork to CellMark.EMPTY)),
            Triple("an unconfirmed clear with nothing confirmed dashes nothing", snapshotOf(null, SelectionRequest.Clear), mapOf(work to CellMark.EMPTY, meeting to CellMark.EMPTY, projectBWork to CellMark.EMPTY)),
            Triple("a tap on the confirmed cell that is unsent shows it dashed", snapshotOf(meeting, SelectionRequest.Select(meeting)), mapOf(work to CellMark.EMPTY, meeting to CellMark.UNSENT, projectBWork to CellMark.EMPTY)),
        )
        cases.forEach { (name, snapshot, expected) ->
            cells.forEach { cell ->
                assertEquals("case=$name cell=$cell", expected.getValue(cell), snapshot.cellMarkOf(cell))
            }
        }
    }
}
