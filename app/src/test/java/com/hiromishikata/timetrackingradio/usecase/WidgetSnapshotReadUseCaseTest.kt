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

class WidgetSnapshotReadUseCaseTest {
    private val now = Instant.parse("2026-11-07T09:00:00Z")
    private val earlier = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)

    @Test
    fun refreshKeepsTheInFlightSendingState() {
        data class Case(
            val name: String,
            val before: WidgetSnapshot,
            val response: () -> SelectionResponse,
            val expected: WidgetSnapshot,
        )
        val unreachable = { throw CommunicationFailedException("down") }
        val cases = listOf(
            Case(
                "accepted response replaces the state",
                WidgetSnapshot.EMPTY,
                { SelectionResponse.Accepted(Selection(work, earlier)) },
                WidgetSnapshot(Selection(work, earlier), now, WidgetOutcome.Settled, null, null),
            ),
            Case(
                "network failure keeps the last state and clears a stale request",
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Settled, SelectionRequest.Select(meeting), null),
                unreachable,
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Unreachable, null, null),
            ),
            Case(
                "rejection without a reported selection keeps the last state",
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Settled, null, null),
                { SelectionResponse.Rejected("wrong key", ReportedSelection.NotReported) },
                WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Rejected("wrong key"), null, null),
            ),
        )
        cases.forEach { case ->
            val selections = FakeSelectionRepository(onRead = case.response)
            val snapshots = FakeWidgetSnapshotRepository(case.before)
            val after = WidgetSnapshotReadUseCase(selections, snapshots) { now }.execute()
            assertEquals(case.name, case.expected, after)
            assertEquals(case.name, case.expected, snapshots.read())
        }
    }

    @Test
    fun anyUnexpectedFailureOnRefreshClearsTheStaleSendingState() {
        val earlierSnapshot = WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Settled, SelectionRequest.Select(meeting), null)
        val selections = FakeSelectionRepository(onRead = { throw IllegalStateException("unexpected") })
        val snapshots = FakeWidgetSnapshotRepository(earlierSnapshot)
        val thrown = runCatching { WidgetSnapshotReadUseCase(selections, snapshots) { now }.execute() }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        assertEquals(WidgetSnapshot(Selection(work, earlier), earlier, WidgetOutcome.Settled, null, null), snapshots.read())
    }
}

