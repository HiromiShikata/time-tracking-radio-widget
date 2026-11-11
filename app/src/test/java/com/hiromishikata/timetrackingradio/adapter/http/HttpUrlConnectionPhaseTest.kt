package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun aConnectionCutWhileTheBodyIsReadIsTaggedAsReading() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "1000")
                .setBody("partial")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.start()
        try {
            val thrown = runCatching { transport.send(HttpRequest("GET", server.url("/exec").toString(), null)) }.exceptionOrNull()
            assertTrue("thrown=$thrown", thrown is TransportPhaseFailure)
            assertEquals(FailurePhase.READING, (thrown as TransportPhaseFailure).phase)
        } finally {
            server.shutdown()
        }
    }
}
