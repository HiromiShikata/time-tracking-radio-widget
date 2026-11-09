package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HttpSelectionRepositoryTest {
    private val endpoint = "https://script.example.com/macros/s/key-value/exec/time-tracking-selection?key=k"
    private val redirected = "https://script.example.com/echo?user_content_key=abc"
    private val okBody = """{"result":"ok","selection":null}"""
    private val request = SelectionRequest.Select(CellKey("project-a", ActivityKind.MEETING))
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")

    private fun ok(body: String): () -> HttpResponse = { HttpResponse(200, null, body) }
    private fun redirect(location: String?): () -> HttpResponse = { HttpResponse(302, location, "") }
    private fun status(code: Int): () -> HttpResponse = { HttpResponse(code, null, "") }
    private fun unreachable(): () -> HttpResponse = { throw IOException("unreachable") }

    @Test
    fun readFollowsRedirectsWithGet() {
        val cases = listOf(
            listOf(ok(okBody)) to listOf(HttpRequest("GET", endpoint, null)),
            listOf(redirect(redirected), ok(okBody)) to
                listOf(HttpRequest("GET", endpoint, null), HttpRequest("GET", redirected, null)),
        )
        cases.forEach { (scripts, expectedRequests) ->
            val transport = FakeHttpTransport(ArrayDeque(scripts))
            val response = HttpSelectionRepository(endpoint, transport).read()
            assertEquals(SelectionResponse.Accepted(null), response)
            assertEquals(expectedRequests, transport.requests)
        }
    }

    @Test
    fun updatePostsEncodedBodyThenFollowsRedirectsWithGet() {
        val body = """{"project":"project-a","activity":"meeting","tappedAt":"2026-11-09T02:19:03.112Z"}"""
        val cases = listOf(
            listOf(ok(okBody)) to listOf(HttpRequest("POST", endpoint, body)),
            listOf(redirect(redirected), ok(okBody)) to
                listOf(
                    HttpRequest("POST", endpoint, body),
                    HttpRequest("GET", redirected, null),
                ),
            listOf(redirect(redirected), redirect("https://script.example.com/second"), ok(okBody)) to
                listOf(
                    HttpRequest("POST", endpoint, body),
                    HttpRequest("GET", redirected, null),
                    HttpRequest("GET", "https://script.example.com/second", null),
                ),
        )
        cases.forEach { (scripts, expectedRequests) ->
            val transport = FakeHttpTransport(ArrayDeque(scripts))
            val response = HttpSelectionRepository(endpoint, transport).update(request, tappedAt)
            assertEquals(SelectionResponse.Accepted(null), response)
            assertEquals(expectedRequests, transport.requests)
        }
    }

    @Test
    fun failuresBecomeCommunicationFailures() {
        val cases = listOf(
            "server error" to listOf(status(500)),
            "redirect without location" to listOf(redirect(null)),
            "redirect loop" to List(6) { redirect(redirected) },
            "transport error" to listOf(unreachable()),
            "malformed body" to listOf(ok("not json")),
        )
        cases.forEach { (name, scripts) ->
            val thrown = runCatching {
                HttpSelectionRepository(endpoint, FakeHttpTransport(ArrayDeque(scripts))).read()
            }.exceptionOrNull()
            assertTrue("case=$name thrown=$thrown", thrown is CommunicationFailedException)
        }
    }

    @Test
    fun aTransportFailureKeepsItsExceptionAsTheCause() {
        val failures = listOf<IOException>(
            UnknownHostException("unknown.example"),
            SocketTimeoutException("timeout"),
            ConnectException("Connection refused"),
            SocketException("Software caused connection abort"),
            SSLHandshakeException("handshake failed"),
        )
        failures.forEach { failure ->
            val script: () -> HttpResponse = { throw failure }
            val thrown = runCatching {
                HttpSelectionRepository(endpoint, FakeHttpTransport(ArrayDeque(listOf(script)))).read()
            }.exceptionOrNull()
            assertTrue(
                "failure=$failure thrown=$thrown",
                thrown is CommunicationFailedException && thrown.cause === failure,
            )
        }
    }
}
