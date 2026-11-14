package com.hiromishikata.timetrackingradio.adapter.update

import android.app.NotificationManager
import com.hiromishikata.timetrackingradio.domain.ChannelImportance
import com.hiromishikata.timetrackingradio.domain.InterruptionFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationConditionMappingTest {
    @Test
    fun mapsEveryPlatformImportanceToItsNameAndAnyOtherValueToUnknown() {
        data class Case(val platform: Int, val expected: ChannelImportance)
        val cases = listOf(
            Case(NotificationManager.IMPORTANCE_HIGH, ChannelImportance.HIGH),
            Case(NotificationManager.IMPORTANCE_DEFAULT, ChannelImportance.DEFAULT),
            Case(NotificationManager.IMPORTANCE_LOW, ChannelImportance.LOW),
            Case(NotificationManager.IMPORTANCE_MIN, ChannelImportance.MIN),
            Case(NotificationManager.IMPORTANCE_NONE, ChannelImportance.NONE),
            Case(-1, ChannelImportance.UNKNOWN),
            Case(99, ChannelImportance.UNKNOWN),
        )
        cases.forEach { case ->
            assertEquals("platform=${case.platform}", case.expected, channelImportanceOf(case.platform))
        }
    }

    @Test
    fun mapsEveryPlatformInterruptionFilterToItsNameAndAnyOtherValueToUnknown() {
        data class Case(val platform: Int, val expected: InterruptionFilter)
        val cases = listOf(
            Case(NotificationManager.INTERRUPTION_FILTER_ALL, InterruptionFilter.ALL),
            Case(NotificationManager.INTERRUPTION_FILTER_PRIORITY, InterruptionFilter.PRIORITY),
            Case(NotificationManager.INTERRUPTION_FILTER_NONE, InterruptionFilter.NONE),
            Case(NotificationManager.INTERRUPTION_FILTER_ALARMS, InterruptionFilter.ALARMS),
            Case(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, InterruptionFilter.UNKNOWN),
            Case(42, InterruptionFilter.UNKNOWN),
        )
        cases.forEach { case ->
            assertEquals("platform=${case.platform}", case.expected, interruptionFilterOf(case.platform))
        }
    }
}
