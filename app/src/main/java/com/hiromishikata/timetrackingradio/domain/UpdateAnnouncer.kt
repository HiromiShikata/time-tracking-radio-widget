package com.hiromishikata.timetrackingradio.domain

import java.io.File

interface UpdateAnnouncer {
    fun notificationsPermitted(): Boolean

    fun announce(version: SemanticVersion, apk: File): AnnounceOutcome

    fun notificationConditions(): NotificationConditions

    fun isUpdateNotificationActive(): Boolean?
}
