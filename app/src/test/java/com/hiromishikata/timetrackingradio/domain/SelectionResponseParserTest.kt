package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SelectionResponseParserTest {
    private val projectBWork = Selection(
        CellKey("project-b", ActivityKind.WORK),
        Instant.parse("2026-11-07T08:39:00Z"),
    )
    private val projectBSelection = """{"project":"project-b","activity":"work","since":"2026-11-07T08:39:00Z"}"""

    @Test
    fun parsesEveryDocumentedResponseShape() {
        val cases = listOf(
            """{"result":"ok","selection":$projectBSelection}""" to SelectionResponse.Accepted(projectBWork),
            """{"result":"ok","selection":null}""" to SelectionResponse.Accepted(null),
            """{"result":"ng","message":"wrong key"}""" to
                SelectionResponse.Rejected("wrong key", ReportedSelection.NotReported),
            """{"result":"ng","message":"invalid","selection":null}""" to
                SelectionResponse.Rejected("invalid", ReportedSelection.Reported(null)),
            """{"result":"ng","message":"invalid","selection":$projectBSelection}""" to
                SelectionResponse.Rejected("invalid", ReportedSelection.Reported(projectBWork)),
        )
        cases.forEach { (body, expected) ->
            assertEquals("body=$body", expected, parseSelectionResponse(body))
        }
    }

    @Test
    fun rejectsMalformedResponses() {
        val cases = listOf(
            "",
            "not json",
            """{"result":"maybe","selection":null}""",
            """{"selection":null}""",
            """{"result":"ok"}""",
            """{"result":"ok","selection":{"project":"","activity":"work","since":"2026-11-07T08:39:00Z"}}""",
            """{"result":"ok","selection":{"project":"project-b","activity":"lunch","since":"2026-11-07T08:39:00Z"}}""",
            """{"result":"ok","selection":{"project":"project-b","activity":"work","since":"yesterday"}}""",
            """{"result":"ng","selection":null}""",
        )
        cases.forEach { body ->
            val thrown = runCatching { parseSelectionResponse(body) }.exceptionOrNull()
            assertTrue("body=$body thrown=$thrown", thrown is MalformedResponseException)
        }
    }
}
