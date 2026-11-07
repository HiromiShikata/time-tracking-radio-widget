package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionRequestBodyTest {
    @Test
    fun encodesRequestsAsJsonBodies() {
        val cases = listOf(
            SelectionRequest.Select(CellKey("project-a", ActivityKind.WORK)) to
                """{"project":"project-a","activity":"work"}""",
            SelectionRequest.Select(CellKey("a\"b\\c", ActivityKind.MEETING)) to
                """{"project":"a\"b\\c","activity":"meeting"}""",
            SelectionRequest.Select(CellKey("tab\there", ActivityKind.WORK)) to
                """{"project":"tab\there","activity":"work"}""",
            SelectionRequest.Clear to """{"project":null,"activity":null}""",
        )
        cases.forEach { (request, expected) ->
            assertEquals("request=$request", expected, encodeSelectionRequest(request))
        }
    }
}
