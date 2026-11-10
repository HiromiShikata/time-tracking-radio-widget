package com.hiromishikata.timetrackingradio.domain

interface UpdateAnnouncer {
    fun notificationsPermitted(): Boolean

    fun announce(version: SemanticVersion, apkDownloadUrl: String): Boolean
}
