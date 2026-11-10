package com.hiromishikata.timetrackingradio.domain

sealed interface UpdateDecision {
    data class Announce(val version: SemanticVersion, val apkDownloadUrl: String, val apkSizeBytes: Long) : UpdateDecision

    data object UpToDate : UpdateDecision

    data class NewerWithoutApk(val version: SemanticVersion) : UpdateDecision
}

fun decideUpdate(installed: SemanticVersion, latest: LatestRelease): UpdateDecision {
    if (latest.version <= installed) return UpdateDecision.UpToDate
    val apkDownloadUrl = latest.apkDownloadUrl
    val apkSizeBytes = latest.apkSizeBytes
    if (apkDownloadUrl == null || apkSizeBytes == null) return UpdateDecision.NewerWithoutApk(latest.version)
    return UpdateDecision.Announce(latest.version, apkDownloadUrl, apkSizeBytes)
}
