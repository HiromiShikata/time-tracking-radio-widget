package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SelectionRequestBodyTest {
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")

    @Test
    fun encodesRequestsAsJsonBodiesCarryingTheOriginalTapTime() {
        val cases = listOf(
            SelectionRequest.Select(CellKey("project-a", ActivityKind.WORK)) to
                """{"project":"project-a","activity":"work","tappedAt":"2026-11-09T02:19:03.112Z"}""",
            SelectionRequest.Select(CellKey("a\"b\\c", ActivityKind.MEETING)) to
                """{"project":"a\"b\\c","activity":"meeting","tappedAt":"2026-11-09T02:19:03.112Z"}""",
            SelectionRequest.Select(CellKey("tab\there", ActivityKind.WORK)) to
                """{"project":"tab\there","activity":"work","tappedAt":"2026-11-09T02:19:03.112Z"}""",
            SelectionRequest.Clear to """{"project":null,"activity":null,"tappedAt":"2026-11-09T02:19:03.112Z"}""",
        )
        cases.forEach { (request, expected) ->
            assertEquals("request=$request", expected, encodeSelectionRequest(request, tappedAt))
        }
    }

    @Test
    fun tappedAtIsWrittenAsUtcWithMillisecondPrecision() {
        val cases = listOf(
            Instant.parse("2026-11-09T02:19:03Z") to "2026-11-09T02:19:03.000Z",
            Instant.parse("2026-11-09T02:19:03.112Z") to "2026-11-09T02:19:03.112Z",
        )
        cases.forEach { (instant, expectedTappedAt) ->
            val body = encodeSelectionRequest(SelectionRequest.Clear, instant)
            assertEquals("tappedAt=$instant", """{"project":null,"activity":null,"tappedAt":"$expectedTappedAt"}""", body)
        }
    }
}
