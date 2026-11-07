package com.hiromishikata.timetrackingradio.domain

interface UpdateAnnouncer {
    fun announce(version: SemanticVersion, apkDownloadUrl: String)
}
