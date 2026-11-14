package com.hiromishikata.timetrackingradio.adapter.update

import android.app.NotificationManager
import com.hiromishikata.timetrackingradio.domain.ChannelImportance
import com.hiromishikata.timetrackingradio.domain.InterruptionFilter

internal fun channelImportanceOf(platformImportance: Int): ChannelImportance = when (platformImportance) {
    NotificationManager.IMPORTANCE_HIGH -> ChannelImportance.HIGH
    NotificationManager.IMPORTANCE_DEFAULT -> ChannelImportance.DEFAULT
    NotificationManager.IMPORTANCE_LOW -> ChannelImportance.LOW
    NotificationManager.IMPORTANCE_MIN -> ChannelImportance.MIN
    NotificationManager.IMPORTANCE_NONE -> ChannelImportance.NONE
    else -> ChannelImportance.UNKNOWN
}

internal fun interruptionFilterOf(platformFilter: Int): InterruptionFilter = when (platformFilter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> InterruptionFilter.ALL
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> InterruptionFilter.PRIORITY
    NotificationManager.INTERRUPTION_FILTER_NONE -> InterruptionFilter.NONE
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> InterruptionFilter.ALARMS
    else -> InterruptionFilter.UNKNOWN
}
