package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
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

    private fun recordedOf(script: () -> HttpResponse): List<Pair<String, String>> {
        val log = FakeDiagnosticLogRepository()
        val repository = HttpSelectionRepository(
            endpoint,
            FakeHttpTransport(ArrayDeque(listOf(script))),
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
        )
        runCatching { repository.update(SelectionRequest.Select(CellKey("project-a", ActivityKind.WORK))) }
        return log.recorded.map { it.type.code to it.detail }
    }

    @Test
    fun aFailureMessageCarryingAQueryIsRecordedWithoutThatQuery() {
        val log = FakeDiagnosticLogRepository()
        val repository = HttpSelectionRepository(
            endpoint,
            FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw MalformedURLException("no protocol: /echo?token=SECRET123") }))),
            DiagnosticRecorder(log, endpointKeySecrets(endpoint)) { Instant.EPOCH },
        )
        runCatching { repository.read() }
        assertEquals(
            listOf("exception" to "MalformedURLException: no protocol: /echo?***"),
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
    fun responseOutcomesAreRecorded() {
        val cases = listOf(
            "ok" to recordedOf { HttpResponse(200, null, """{"result":"ok","selection":null}""") },
            "ng" to recordedOf { HttpResponse(200, null, """{"result":"ng","message":"bad key s3cr3t"}""") },
            "malformed" to recordedOf { HttpResponse(200, null, "not json") },
            "status" to recordedOf { HttpResponse(500, null, "") },
            "transport" to recordedOf { throw IOException("unreachable s3cr3t") },
        )
        val expected = mapOf(
            "ok" to listOf("response_parsed" to "ok"),
            "ng" to listOf("response_parsed" to "ng", "server_ng" to "bad key ***"),
            "malformed" to listOf("response_parsed" to "malformed: response is not JSON"),
            "status" to listOf("exception" to "CommunicationFailedException: endpoint returned HTTP 500"),
            "transport" to listOf("exception" to "IOException: unreachable ***"),
        )
        cases.forEach { (name, actual) ->
            assertEquals("case=$name", expected.getValue(name), actual)
        }
    }
}
