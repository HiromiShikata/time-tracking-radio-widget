package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.ConnectionCheckResult
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.ReportedSelection
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WidgetConnectionCheckUseCaseTest {
    private data class Case(
        val name: String,
        val read: () -> SelectionResponse,
        val expected: ConnectionCheckResult,
        val recordedDetail: String,
    )

    private val since = Instant.parse("2026-11-08T08:39:00Z")
    private val projectAWork = CellKey("project-a", ActivityKind.WORK)

    @Test
    fun eachOutcomeIsReturnedWithTheSecretScrubbedAndRecorded() {
        val cases = listOf(
            Case(
                name = "no selection",
                read = { SelectionResponse.Accepted(null) },
                expected = ConnectionCheckResult.Connected(null),
                recordedDetail = "connected, no selection",
            ),
            Case(
                name = "a selection",
                read = { SelectionResponse.Accepted(Selection(projectAWork, since)) },
                expected = ConnectionCheckResult.Connected(Selection(projectAWork, since)),
                recordedDetail = "connected, selection project-a / work since 2026-11-08T08:39:00Z",
            ),
            Case(
                name = "server ng with the key in its message",
                read = { SelectionResponse.Rejected("bad key s3cr3t", ReportedSelection.NotReported) },
                expected = ConnectionCheckResult.ServerRejected("bad key ***"),
                recordedDetail = "server ng: bad key ***",
            ),
            Case(
                name = "unreachable",
                read = { throw CommunicationFailedException("endpoint returned HTTP 500") },
                expected = ConnectionCheckResult.Failed("endpoint returned HTTP 500"),
                recordedDetail = "failed: endpoint returned HTTP 500",
            ),
        )
        cases.forEach { case ->
            val log = FakeDiagnosticLogRepository()
            val useCase = WidgetConnectionCheckUseCase(
                FakeSelectionRepository(onRead = case.read),
                DiagnosticRecorder(log, setOf("s3cr3t")) { Instant.EPOCH },
                setOf("s3cr3t"),
            )
            assertEquals("case=${case.name}", case.expected, useCase.execute())
            assertEquals(
                "case=${case.name}",
                listOf("connection_check" to case.recordedDetail),
                log.recorded.map { it.type.code to it.detail },
            )
        }
    }
}
