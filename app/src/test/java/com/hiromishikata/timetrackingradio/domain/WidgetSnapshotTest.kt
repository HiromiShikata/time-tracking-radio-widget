package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WidgetSnapshotTest {
    private val now = Instant.parse("2026-11-07T09:00:00Z")
    private val earlier = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val stale = WidgetSnapshot(
        Selection(work, earlier),
        earlier,
        WidgetOutcome.Settled,
        SelectionRequest.Select(CellKey("project-a", ActivityKind.MEETING)),
    )

    @Test
    fun everyServerResponseClearsAStaleSendingState() {
        val cases = listOf(
            "accepted" to applySelectionResponse(SelectionResponse.Accepted(null), stale, now),
            "rejected without selection" to applySelectionResponse(
                SelectionResponse.Rejected("wrong key", ReportedSelection.NotReported),
                stale,
                now,
            ),
            "rejected with selection" to applySelectionResponse(
                SelectionResponse.Rejected("invalid", ReportedSelection.Reported(null)),
                stale,
                now,
            ),
        )
        cases.forEach { (name, snapshot) -> assertNull(name, snapshot.sending) }
    }

    @Test
    fun unreachableClearsAStaleSendingState() {
        val snapshot = markUnreachable(stale)
        assertNull(snapshot.sending)
        assertEquals(WidgetOutcome.Unreachable, snapshot.outcome)
    }
}
