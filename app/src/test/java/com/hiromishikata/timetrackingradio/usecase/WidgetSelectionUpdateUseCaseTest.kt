package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.ReportedSelection
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetSelectionUpdateUseCaseTest {
    private val now = Instant.parse("2026-11-07T09:00:00Z")
    private val earlier = Instant.parse("2026-11-07T08:39:00Z")
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val workSelected = WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Settled, null, null)

    @Test
    fun tapSendsTheResolvedRequestAndStoresTheOutcome() {
        data class Case(
            val name: String,
            val before: WidgetSnapshot,
            val tapped: CellKey,
            val response: () -> SelectionResponse,
            val expectedRequest: SelectionRequest,
            val expectedAfter: WidgetSnapshot,
        )
        val unreachable = { throw CommunicationFailedException("down") }
        val cases = listOf(
            Case(
                "first tap selects the cell",
                WidgetSnapshot.EMPTY,
                work,
                { SelectionResponse.Accepted(Selection(work, now)) },
                SelectionRequest.Select(work),
                WidgetSnapshot(Selection(work, now), now, WidgetOutcome.Settled, null, null),
            ),
            Case(
                "tapping the selected cell clears",
                workSelected,
                work,
                { SelectionResponse.Accepted(null) },
                SelectionRequest.Clear,
                WidgetSnapshot(null, now, WidgetOutcome.Settled, null, null),
            ),
            Case(
                "network failure keeps the shown state",
                workSelected,
                meeting,
                unreachable,
                SelectionRequest.Select(meeting),
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Unreachable, null, null),
            ),
            Case(
                "network failure without a known state",
                WidgetSnapshot.EMPTY,
                work,
                unreachable,
                SelectionRequest.Select(work),
                WidgetSnapshot(null, null, WidgetOutcome.Unreachable, null, null),
            ),
            Case(
                "rejection without a reported selection keeps the shown state",
                workSelected,
                meeting,
                { SelectionResponse.Rejected("wrong key", ReportedSelection.NotReported) },
                SelectionRequest.Select(meeting),
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Rejected("wrong key"), null, null),
            ),
            Case(
                "rejection with a reported selection adopts it",
                workSelected,
                meeting,
                {
                    SelectionResponse.Rejected(
                        "project not allowed",
                        ReportedSelection.Reported(Selection(meeting, now)),
                    )
                },
                SelectionRequest.Select(meeting),
                WidgetSnapshot(Selection(meeting, now), now, WidgetOutcome.Rejected("project not allowed"), null, null),
            ),
        )
        cases.forEach { case ->
            val selections = FakeSelectionRepository(onUpdate = case.response)
            val snapshots = FakeWidgetSnapshotRepository(case.before)
            val after = WidgetSelectionUpdateUseCase(selections, snapshots) { now }.execute(case.tapped, tappedAt)
            assertEquals(case.name, listOf(case.expectedRequest), selections.updates)
            assertEquals(case.name, case.expectedRequest, snapshots.writes.first().sending)
            assertEquals(case.name, case.expectedAfter, after)
            assertEquals(case.name, case.expectedAfter, snapshots.read())
        }
    }

    @Test
    fun anyUnexpectedFailureClearsTheSendingStateAndIsRethrown() {
        val selections = FakeSelectionRepository(onUpdate = { throw IllegalStateException("unexpected") })
        val snapshots = FakeWidgetSnapshotRepository(workSelected)
        val thrown = runCatching { WidgetSelectionUpdateUseCase(selections, snapshots) { now }.execute(meeting, tappedAt) }
            .exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        assertEquals(workSelected, snapshots.read())
    }
}

