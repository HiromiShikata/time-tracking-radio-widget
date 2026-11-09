package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.endpointKeySecrets
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class HttpNetworkContractTest {
    private lateinit var server: MockWebServer
    private val transport = HttpUrlConnectionTransport()
    private val okBody = """{"result":"ok","selection":null}"""
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun endpoint(path: String = "/exec") = server.url(path).toString()

    @Test
    fun getReturnsTheStatusAndBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody))
        val response = transport.send(HttpRequest("GET", endpoint(), null))
        assertEquals(200, response.status)
        assertEquals(okBody, response.body)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun postAnsweredByARedirectIsReadWithAGetOnTheLocation() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", endpoint("/echo")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody))
        val response = HttpSelectionRepository(endpoint(), transport)
            .update(SelectionRequest.Select(CellKey("project-a", ActivityKind.MEETING)), tappedAt)
        assertEquals(SelectionResponse.Accepted(null), response)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("""{"project":"project-a","activity":"meeting","tappedAt":"2026-11-09T02:19:03.112Z"}""", post.body.readUtf8())
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/echo", get.path)
    }

    @Test
    fun redirectChainIsLimitedToFiveHops() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", endpoint("/loop"))) }
        val thrown = runCatching { HttpSelectionRepository(endpoint(), transport).read() }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is CommunicationFailedException)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun nonSuccessStatusIsReturnedByTheTransportAndRejectedByTheRepository() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val response = transport.send(HttpRequest("GET", endpoint(), null))
        assertEquals(404, response.status)
        assertEquals("missing", response.body)

        server.enqueue(MockResponse().setResponseCode(500))
        val thrown = runCatching { HttpSelectionRepository(endpoint(), transport).read() }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is CommunicationFailedException)
    }

    @Test
    fun aRelativeRedirectLocationIsFollowedAndItsTokenIsNotRecorded() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/echo?token=SECRET123"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okBody))
        val log = FakeDiagnosticLogRepository()
        val recorder = DiagnosticRecorder(log, endpointKeySecrets(endpoint())) { Instant.EPOCH }
        val response = HttpSelectionRepository(endpoint(), DiagnosticHttpTransport(transport, endpoint(), recorder), recorder).read()
        assertEquals(SelectionResponse.Accepted(null), response)
        server.takeRequest()
        assertEquals("/echo?token=SECRET123", server.takeRequest().path)
        assertTrue(log.recorded.none { it.detail.contains("SECRET123") })
    }
}
