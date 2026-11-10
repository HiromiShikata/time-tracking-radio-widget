package com.hiromishikata.timetrackingradio.adapter.update

sealed interface InstallStart {
    data object Commit : InstallStart

    data object OpenUnknownAppSources : InstallStart

    data object MissingApk : InstallStart
}

fun decideInstallStart(canRequestPackageInstalls: Boolean, apkPresent: Boolean): InstallStart = when {
    !canRequestPackageInstalls -> InstallStart.OpenUnknownAppSources
    !apkPresent -> InstallStart.MissingApk
    else -> InstallStart.Commit
}
