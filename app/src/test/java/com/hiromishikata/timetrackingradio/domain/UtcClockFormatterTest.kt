package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class UtcClockFormatterTest {
    @Test
    fun formatsUtcHourAndMinute() {
        val cases = listOf(
            "2026-11-07T08:39:00Z" to "08:39",
            "2026-11-07T23:05:59Z" to "23:05",
            "2026-11-07T00:00:00Z" to "00:00",
            "2026-11-07T08:39:59.999Z" to "08:39",
        )
        cases.forEach { (instant, expected) ->
            assertEquals("instant=$instant", expected, formatUtcHourMinute(Instant.parse(instant)))
        }
    }
}
