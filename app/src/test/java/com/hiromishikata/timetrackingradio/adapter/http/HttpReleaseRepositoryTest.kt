package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import com.hiromishikata.timetrackingradio.domain.RequestDiagnostics
import com.hiromishikata.timetrackingradio.domain.RequestKind

class HttpReleaseRepositoryTest {
    private val url = "https://api.example.com/repos/example/app/releases"
    private val validBody = """[{"tag_name":"v1.0.7","assets":[{"name":"app.apk","browser_download_url":"https://example.com/app.apk"}]}]"""

    @Test
    fun readsTheRecentReleasesWithAGetRequest() {
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, validBody) })))
        val releases = HttpReleaseRepository(url, transport).readRecent()
        assertEquals(listOf(LatestRelease(SemanticVersion(1, 0, 7), "https://example.com/app.apk", null)), releases)
        assertEquals(listOf(HttpRequest("GET", url, null, RequestDiagnostics(RequestKind.UPDATE_CHECK))), transport.requests)
    }

    @Test
    fun everyFailureBecomesACommunicationFailure() {
        val cases = listOf(
            "transport error" to { throw IOException("unreachable") },
            "server error" to { HttpResponse(503, null, "") },
            "not found" to { HttpResponse(404, null, "") },
            "body is not JSON" to { HttpResponse(200, null, "not json") },
            "tag is not a version" to { HttpResponse(200, null, """{"tag_name":"latest"}""") },
        )
        cases.forEach { (name, response) ->
            val transport = FakeHttpTransport(ArrayDeque(listOf(response)))
            val thrown = runCatching { HttpReleaseRepository(url, transport).readRecent() }.exceptionOrNull()
            assertTrue("case=$name thrown=$thrown", thrown is CommunicationFailedException)
        }
    }

    @Test
    fun anEmptyOrNonArrayReleaseListBecomesACommunicationFailure() {
        val cases = listOf(
            "empty array" to "[]",
            "single object instead of an array" to """{"tag_name":"v1.0.7"}""",
        )
        cases.forEach { (name, body) ->
            val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
            val thrown = runCatching { HttpReleaseRepository(url, transport).readRecent() }.exceptionOrNull()
            assertTrue("case=$name thrown=$thrown", thrown is CommunicationFailedException)
        }
    }
}
