package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WidgetStatusResolverTest {
    private val asOf = Instant.parse("2026-11-07T09:00:00Z")
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val selection = Selection(work, since)

    @Test
    fun resolvesStatusLineFromSelectionSendingAndOutcome() {
        data class Case(
            val name: String,
            val selection: Selection?,
            val sending: SelectionRequest?,
            val outcome: WidgetOutcome,
            val expected: WidgetStatus,
        )
        val cases = listOf(
            Case("idle", null, null, WidgetOutcome.Settled, WidgetStatus.NotRecording),
            Case("recording", selection, null, WidgetOutcome.Settled, WidgetStatus.Recording(selection)),
            Case(
                "switching to a cell",
                selection,
                SelectionRequest.Select(meeting),
                WidgetOutcome.Settled,
                WidgetStatus.Switching(meeting),
            ),
            Case("clearing shows not recording", selection, SelectionRequest.Clear, WidgetOutcome.Settled, WidgetStatus.NotRecording),
            Case("unreachable", selection, null, WidgetOutcome.Unreachable, WidgetStatus.Unreachable(asOf)),
            Case("server message", selection, null, WidgetOutcome.Rejected("wrong"), WidgetStatus.ServerRejected("wrong")),
            Case(
                "sending wins over unreachable",
                selection,
                SelectionRequest.Select(meeting),
                WidgetOutcome.Unreachable,
                WidgetStatus.Switching(meeting),
            ),
        )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveWidgetStatus(case.selection, case.sending, case.outcome, asOf),
            )
        }
    }
}
