package com.hiromishikata.timetrackingradio.domain

import java.time.Duration
import java.time.Instant

val UPDATE_ALERT_REMINDER_INTERVAL: Duration = Duration.ofHours(6)

enum class AnnounceOutcome(val label: String, val posted: Boolean) {
    ALERTED("alerted", true),
    SILENT_UPDATE("silent_update", true),
    SKIPPED_RECENTLY_DISMISSED("skipped_recently_dismissed", false),
    NOT_PERMITTED("not_permitted", false),
}

data class StoredUpdateAlert(val version: String, val alertedAt: Instant)

fun decideUpdateAlert(
    version: String,
    stored: StoredUpdateAlert?,
    notificationActive: Boolean?,
    now: Instant,
): AnnounceOutcome = when {
    stored == null || stored.version != version -> AnnounceOutcome.ALERTED
    notificationActive == true -> AnnounceOutcome.SILENT_UPDATE
    Duration.between(stored.alertedAt, now) < UPDATE_ALERT_REMINDER_INTERVAL -> AnnounceOutcome.SKIPPED_RECENTLY_DISMISSED
    else -> AnnounceOutcome.ALERTED
}
