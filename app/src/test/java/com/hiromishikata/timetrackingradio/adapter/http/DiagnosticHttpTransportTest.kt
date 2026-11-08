package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
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
    fun everyHopIsLoggedWithoutTheEndpointKeyOrTheLocationToken() {
        val log = FakeDiagnosticLogRepository()
        val delegate = FakeHttpTransport(
            ArrayDeque(listOf({ HttpResponse(302, location, "") }, { HttpResponse(200, null, "{}") })),
        )
        val transport = DiagnosticHttpTransport(delegate, endpoint, DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH })
        transport.send(HttpRequest("POST", endpoint, "{}"))
        transport.send(HttpRequest("GET", location, null))
        assertEquals(
            listOf(
                "request_sent" to "POST https://script.example.com/exec?key=***",
                "http_response" to "status=302 location_host=script.googleusercontent.com",
                "request_sent" to "GET host=script.googleusercontent.com",
                "http_response" to "status=200",
            ),
            log.recorded.map { it.type.code to it.detail },
        )
        assertFalse(log.recorded.any { it.detail.contains("s3cr3t") || it.detail.contains("t0ken") })
    }

    @Test
    fun aTransportFailureIsRethrownUnchanged() {
        val log = FakeDiagnosticLogRepository()
        val failure = IOException("unreachable")
        val transport = DiagnosticHttpTransport(
            FakeHttpTransport(ArrayDeque(listOf({ throw failure }))),
            endpoint,
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
        )
        val thrown = runCatching { transport.send(HttpRequest("GET", endpoint, null)) }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(listOf("request_sent"), log.recorded.map { it.type.code })
    }
}
