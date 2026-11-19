package com.hiromishikata.timetrackingradio.domain

import java.io.File

interface UpdateAnnouncer {
    fun notificationsPermitted(): Boolean

    fun announce(version: SemanticVersion, apk: File, changes: List<ReleaseChanges>): AnnounceOutcome

    fun notificationConditions(): NotificationConditions

    fun isUpdateNotificationActive(): Boolean?
}
