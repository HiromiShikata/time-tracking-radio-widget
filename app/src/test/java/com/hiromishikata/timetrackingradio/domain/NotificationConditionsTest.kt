package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationConditionsTest {
    @Test
    fun formatsTheConditionsAsNamedValues() {
        data class Case(val name: String, val conditions: NotificationConditions, val expected: String)
        val cases = listOf(
            Case(
                "all visible",
                NotificationConditions(true, ChannelImportance.HIGH, InterruptionFilter.ALL),
                "notifications_enabled=true, channel_importance=high, interruption_filter=all",
            ),
            Case(
                "blocked and silenced",
                NotificationConditions(false, ChannelImportance.NONE, InterruptionFilter.NONE),
                "notifications_enabled=false, channel_importance=none, interruption_filter=none",
            ),
            Case(
                "nothing readable",
                NotificationConditions(null, ChannelImportance.UNKNOWN, InterruptionFilter.UNKNOWN),
                "notifications_enabled=unknown, channel_importance=unknown, interruption_filter=unknown",
            ),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, notificationConditionsDetail(case.conditions))
        }
    }

    @Test
    fun formatsTheActiveStateAsTrueFalseOrUnknown() {
        data class Case(val active: Boolean?, val expected: String)
        val cases = listOf(
            Case(true, "notification_active=true"),
            Case(false, "notification_active=false"),
            Case(null, "notification_active=unknown"),
        )
        cases.forEach { case ->
            assertEquals(case.expected, notificationActiveDetail(case.active))
        }
    }
}
