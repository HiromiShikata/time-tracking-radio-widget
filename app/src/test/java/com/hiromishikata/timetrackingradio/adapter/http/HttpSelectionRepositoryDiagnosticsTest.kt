package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.RequestTimer
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.MalformedURLException
import java.time.Instant

class HttpSelectionRepositoryDiagnosticsTest {
    private val endpoint = "https://script.example.com/exec?key=s3cr3t"
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val request = SelectionRequest.Select(CellKey("project-a", ActivityKind.WORK))
    private val selectionSent = "project=project-a, activity=work, tapped_at=2026-11-09T02:19:03.112Z"
    private val postSent = "kind=selection, method=POST, target=https://script.example.com/exec?key=***, $selectionSent"

    private fun recordedOf(script: () -> HttpResponse): List<Pair<String, String>> {
        val log = FakeDiagnosticLogRepository()
        val recorder = DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH }
        val timer = RequestTimer { 0L }
        val repository = HttpSelectionRepository(
            endpoint,
            DiagnosticHttpTransport(FakeHttpTransport(ArrayDeque(listOf(script))), endpoint, recorder, timer),
            recorder,
            timer,
        )
        runCatching { repository.update(request, tappedAt) }
        return log.recorded.map { it.type.code to it.detail }
    }

    @Test
    fun aFailureMessageCarryingAQueryIsRecordedWithoutThatQuery() {
        val log = FakeDiagnosticLogRepository()
        val recorder = DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH }
        val timer = RequestTimer { 0L }
        val repository = HttpSelectionRepository(
            endpoint,
            DiagnosticHttpTransport(
                FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw MalformedURLException("no protocol: /echo?token=SECRET123") }))),
                endpoint,
                recorder,
                timer,
            ),
            recorder,
            timer,
        )
        runCatching { repository.read() }
        assertEquals(
            listOf(
                "request_sent" to "kind=refresh, method=GET, target=https://script.example.com/exec?key=***",
                "exception" to "MalformedURLException: no protocol: /echo?***, phase=unknown, elapsed_ms=0",
            ),
            log.recorded.map { it.type.code to it.detail },
        )
    }

    @Test
    fun anUnparsableEndpointWithARedirectFailsWithoutCrashingOrRecordingTheKey() {
        val unparsable = "https://script.example.com/exec?key=s3cr3t x"
        val log = FakeDiagnosticLogRepository()
        val recorder = DiagnosticRecorder(log, endpointKeySecrets(unparsable)) { Instant.EPOCH }
        val transport = DiagnosticHttpTransport(
            FakeHttpTransport(
                ArrayDeque<() -> HttpResponse>(
                    listOf({ HttpResponse(302, "https://script.example.com/echo?token=SECRET123", "") }),
                ),
            ),
            unparsable,
            recorder,
        )
        val thrown = runCatching { HttpSelectionRepository(unparsable, transport, recorder).read() }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is CommunicationFailedException)
        assertFalse(thrown.toString().contains("s3cr3t"))
        assertTrue(log.recorded.none { it.detail.contains("s3cr3t") || it.detail.contains("SECRET123") })
    }

    @Test
    fun aSelectionPostRecordsItsSentFieldsItsOutcomeAndItsTimes() {
        val okBody = """{"result":"ok","selection":{"project":"project-a","activity":"work","since":"2026-11-07T08:39:00Z"}}"""
        val cases: List<Triple<String, List<Pair<String, String>>, List<Pair<String, String>>>> = listOf(
            Triple(
                "ok",
                recordedOf { HttpResponse(200, null, okBody) },
                listOf(
                    "request_sent" to postSent,
                    "http_response" to "status=200, elapsed_ms=0",
                    "response_parsed" to "result=ok, selection_project=project-a, selection_activity=work, selection_since=2026-11-07T08:39:00Z, message=none, total_elapsed_ms=0",
                ),
            ),
            Triple(
                "ng",
                recordedOf { HttpResponse(200, null, """{"result":"ng","message":"bad key s3cr3t"}""") },
                listOf(
                    "request_sent" to postSent,
                    "http_response" to "status=200, elapsed_ms=0",
                    "response_parsed" to "result=ng, selection=none, message=bad key ***, total_elapsed_ms=0",
                    "server_ng" to "bad key ***",
                ),
            ),
            Triple(
                "malformed",
                recordedOf { HttpResponse(200, null, "not json") },
                listOf(
                    "request_sent" to postSent,
                    "http_response" to "status=200, elapsed_ms=0",
                    "response_parsed" to "result=malformed, selection=none, message=response is not JSON, total_elapsed_ms=0",
                ),
            ),
            Triple(
                "status",
                recordedOf { HttpResponse(500, null, "") },
                listOf(
                    "request_sent" to postSent,
                    "http_response" to "status=500, elapsed_ms=0",
                    "exception" to "CommunicationFailedException: endpoint returned HTTP 500, phase=after_response, elapsed_ms=0",
                ),
            ),
            Triple(
                "connecting",
                recordedOf { throw TransportPhaseFailure(FailurePhase.CONNECTING, IOException("unreachable s3cr3t")) },
                listOf(
                    "request_sent" to postSent,
                    "exception" to "IOException: unreachable ***, phase=connecting, elapsed_ms=0",
                ),
            ),
            Triple(
                "reading",
                recordedOf { throw TransportPhaseFailure(FailurePhase.READING, IOException("cut s3cr3t")) },
                listOf(
                    "request_sent" to postSent,
                    "exception" to "IOException: cut ***, phase=reading, elapsed_ms=0",
                ),
            ),
        )
        cases.forEach { (name, actual, expected) ->
            assertEquals("case=$name", expected, actual)
        }
    }
}
