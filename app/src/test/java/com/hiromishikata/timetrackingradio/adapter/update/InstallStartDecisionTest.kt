package com.hiromishikata.timetrackingradio.adapter.update

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStartDecisionTest {
    @Test
    fun theInstallStartFollowsThePermissionAndTheApkPresence() {
        data class Case(
            val canRequestPackageInstalls: Boolean,
            val apkPresent: Boolean,
            val expected: InstallStart,
        )
        val cases = listOf(
            Case(false, true, InstallStart.OpenUnknownAppSources),
            Case(false, false, InstallStart.OpenUnknownAppSources),
            Case(true, false, InstallStart.MissingApk),
            Case(true, true, InstallStart.Commit),
        )
        cases.forEach { case ->
            assertEquals(
                "canRequest=${case.canRequestPackageInstalls} apkPresent=${case.apkPresent}",
                case.expected,
                decideInstallStart(case.canRequestPackageInstalls, case.apkPresent),
            )
        }
    }
}
