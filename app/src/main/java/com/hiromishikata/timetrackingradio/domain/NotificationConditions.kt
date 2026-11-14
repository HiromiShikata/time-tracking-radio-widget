package com.hiromishikata.timetrackingradio.domain

enum class ChannelImportance(val label: String) {
    HIGH("high"),
    DEFAULT("default"),
    LOW("low"),
    MIN("min"),
    NONE("none"),
    UNKNOWN("unknown"),
}

enum class InterruptionFilter(val label: String) {
    ALL("all"),
    PRIORITY("priority"),
    NONE("none"),
    ALARMS("alarms"),
    UNKNOWN("unknown"),
}

data class NotificationConditions(
    val appNotificationsEnabled: Boolean?,
    val updateChannelImportance: ChannelImportance,
    val interruptionFilter: InterruptionFilter,
)

fun notificationConditionsDetail(conditions: NotificationConditions): String =
    "notifications_enabled=${triStateLabel(conditions.appNotificationsEnabled)}, " +
        "channel_importance=${conditions.updateChannelImportance.label}, " +
        "interruption_filter=${conditions.interruptionFilter.label}"

fun notificationActiveDetail(active: Boolean?): String = "notification_active=${triStateLabel(active)}"

private fun triStateLabel(value: Boolean?): String = when (value) {
    true -> "true"
    false -> "false"
    null -> "unknown"
}
