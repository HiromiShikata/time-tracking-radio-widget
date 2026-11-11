package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpUrlConnectionPhaseTest {
    private val transport = HttpUrlConnectionTransport()

    @Test
    fun aFailureBeforeAnyResponseIsTaggedAsConnecting() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/exec").toString()
        server.shutdown()
        val thrown = runCatching { transport.send(HttpRequest("GET", url, null)) }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is TransportPhaseFailure)
        assertEquals(FailurePhase.CONNECTING, (thrown as TransportPhaseFailure).phase)
    }

    @Test
    fun aServerThatNeverSendsTheStatusLineIsTaggedAsReading() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val thrown = runCatching {
                HttpUrlConnectionTransport(timeoutMillis = 200).send(HttpRequest("POST", server.url("/exec").toString(), "{}"))
            }.exceptionOrNull()
            assertTrue("thrown=$thrown", thrown is TransportPhaseFailure)
            assertEquals(FailurePhase.READING, (thrown as TransportPhaseFailure).phase)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aBodyThatStallsAfterTheHeadersIsTaggedAsReading() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("late")
                .setBodyDelay(10, TimeUnit.SECONDS),
        )
        server.start()
        try {
            val thrown = runCatching {
                HttpUrlConnectionTransport(timeoutMillis = 200).send(HttpRequest("GET", server.url("/exec").toString(), null))
            }.exceptionOrNull()
            assertTrue("thrown=$thrown", thrown is TransportPhaseFailure)
            assertEquals(FailurePhase.READING, (thrown as TransportPhaseFailure).phase)
        } finally {
            server.shutdown()
        }
    }
}
