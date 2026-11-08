package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult
import com.hiromishikata.timetrackingradio.domain.encodeDiagnosticsUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class HttpDiagnosticsRepositoryTest {
    private val endpoint = "https://script.example.com/exec?key=k"
    private val redirected = "https://script.example.com/echo?user_content_key=abc"
    private val entries = listOf(
        DiagnosticEntry(1, Instant.parse("2026-11-08T00:01:00Z"), DiagnosticEventType.TAP_RECEIVED, "project=a, activity=work", false),
    )
    private val body = encodeDiagnosticsUpload(entries)

    private fun ok(text: String): () -> HttpResponse = { HttpResponse(200, null, text) }
    private fun redirect(location: String): () -> HttpResponse = { HttpResponse(302, location, "") }

    @Test
    fun uploadPostsTheDiagnosticsBodyAndReadsARedirectWithGet() {
        val cases = listOf(
            Triple(listOf(ok("""{"result":"ok","accepted":1}""")), listOf(HttpRequest("POST", endpoint, body)), true),
            Triple(
                listOf(redirect(redirected), ok("""{"result":"ok","accepted":1}""")),
                listOf(HttpRequest("POST", endpoint, body), HttpRequest("GET", redirected, null)),
                true,
            ),
            Triple(listOf(ok("""{"result":"ng","message":"unsupported request body"}""")), listOf(HttpRequest("POST", endpoint, body)), false),
            Triple(listOf(ok("<html>")), listOf(HttpRequest("POST", endpoint, body)), false),
            Triple(listOf({ throw IOException("unreachable") }), listOf(HttpRequest("POST", endpoint, body)), false),
        )
        cases.forEach { (scripts, expectedRequests, delivered) ->
            val transport = FakeHttpTransport(ArrayDeque(scripts))
            val result = HttpDiagnosticsRepository(endpoint, transport).upload(entries)
            assertEquals("requests", expectedRequests, transport.requests)
            assertEquals("delivered", delivered, result is DiagnosticsUploadResult.Delivered)
            if (!delivered) assertTrue(result is DiagnosticsUploadResult.Failed)
        }
    }
}
