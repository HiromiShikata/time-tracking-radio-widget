package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.RequestDiagnostics
import com.hiromishikata.timetrackingradio.domain.RequestKind
import com.hiromishikata.timetrackingradio.domain.RequestTimer
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.time.Instant

class DiagnosticHttpTransportTest {
    private val endpoint = "https://script.example.com/exec?key=s3cr3t"
    private val location = "https://script.googleusercontent.com/echo?user_content_key=t0ken"

    @Test
    fun everyHopIsLoggedWithItsKindElapsedTimeAndWithoutTheKeyOrTheLocationToken() {
        val log = FakeDiagnosticLogRepository()
        var now = 0L
        val delegate = FakeHttpTransport(
            ArrayDeque(
                listOf(
                    { now += 4_000_000L; HttpResponse(302, location, "") },
                    { now += 6_000_000L; HttpResponse(200, null, "{}") },
                ),
            ),
        )
        val transport = DiagnosticHttpTransport(
            delegate,
            endpoint,
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
            RequestTimer { now },
        )
        transport.send(
            HttpRequest("POST", endpoint, "{}", RequestDiagnostics(RequestKind.SELECTION, "project=alpha, activity=work, tapped_at=none")),
        )
        transport.send(HttpRequest("GET", location, null, RequestDiagnostics(RequestKind.REFRESH)))
        assertEquals(
            listOf(
                "request_sent" to "kind=selection, method=POST, target=https://script.example.com/exec?key=***, project=alpha, activity=work, tapped_at=none",
                "http_response" to "status=302, elapsed_ms=4, location_host=script.googleusercontent.com",
                "request_sent" to "kind=refresh, method=GET, target=host=script.googleusercontent.com",
                "http_response" to "status=200, elapsed_ms=6",
            ),
            log.recorded.map { it.type.code to it.detail },
        )
        assertFalse(log.recorded.any { it.detail.contains("s3cr3t") || it.detail.contains("t0ken") })
    }

    @Test
    fun aTransportFailureIsLoggedWithItsPhaseAndElapsedTimeAndRethrownUnchanged() {
        val log = FakeDiagnosticLogRepository()
        var now = 0L
        val failure = TransportPhaseFailure(FailurePhase.CONNECTING, IOException("unreachable"))
        val transport = DiagnosticHttpTransport(
            FakeHttpTransport(ArrayDeque(listOf({ now += 3_000_000L; throw failure }))),
            endpoint,
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
            RequestTimer { now },
        )
        val thrown = runCatching { transport.send(HttpRequest("GET", endpoint, null)) }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(
            listOf(
                "request_sent" to "kind=unspecified, method=GET, target=https://script.example.com/exec?key=***",
                "exception" to "IOException: unreachable, phase=connecting, elapsed_ms=3",
            ),
            log.recorded.map { it.type.code to it.detail },
        )
    }

    @Test
    fun aRawTransportFailureWithoutAPhaseIsLoggedAsUnknownPhase() {
        val log = FakeDiagnosticLogRepository()
        val transport = DiagnosticHttpTransport(
            FakeHttpTransport(ArrayDeque(listOf({ throw IOException("unreachable") }))),
            endpoint,
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
            RequestTimer { 0L },
        )
        runCatching { transport.send(HttpRequest("GET", endpoint, null)) }
        assertEquals(
            "IOException: unreachable, phase=unknown, elapsed_ms=0",
            log.recorded.last { it.type.code == "exception" }.detail,
        )
    }
}
