package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Characterizes the current exception and http_response events; these must stay unchanged by the device state change. */
class DiagnosticHttpTransportCharacterizationTest {
    private val endpoint = "https://example.test/endpoint?key=alpha-secret"

    private fun eventsOf(type: DiagnosticEventType, delegate: HttpTransport): List<String> {
        val log = FakeDiagnosticLogRepository()
        val transport = DiagnosticHttpTransport(delegate, endpoint, DiagnosticRecorder(log, setOf("alpha-secret")) { Instant.EPOCH })
        runCatching { transport.send(HttpRequest("POST", endpoint, "{}")) }
        assertFalse(log.recorded.any { it.detail.contains("alpha-secret") })
        return log.recorded.filter { it.type == type }.map { it.detail }
    }

    @Test
    fun exceptionEventNamesTheFailureClassMessagePhaseAndElapsedTime() {
        val cases = listOf(
            IOException("unreachable") to "IOException: unreachable, phase=unknown, elapsed_ms=",
            TransportPhaseFailure(FailurePhase.CONNECTING, IOException("unreachable")) to
                "IOException: unreachable, phase=connecting, elapsed_ms=",
        )
        cases.forEach { (failure, expectedPrefix) ->
            val delegate = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw failure })))
            val details = eventsOf(DiagnosticEventType.EXCEPTION, delegate)
            assertTrue("case=$expectedPrefix, details=$details", details.size == 1)
            assertTrue("case=$expectedPrefix, detail=${details.single()}", Regex(Regex.escape(expectedPrefix) + """\d+""").matches(details.single()))
        }
    }

    @Test
    fun httpResponseEventNamesStatusAndElapsedTime() {
        val delegate = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, "{}") })))
        val details = eventsOf(DiagnosticEventType.HTTP_RESPONSE, delegate)
        assertTrue("details=$details", details.size == 1)
        assertTrue("detail=${details.single()}", Regex("""status=200, elapsed_ms=\d+""").matches(details.single()))
    }
}
