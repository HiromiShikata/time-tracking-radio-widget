package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageInstaller
import java.io.File

interface InstallSessionApi {
    fun createSession(): Int

    fun writeApk(sessionId: Int, name: String, apk: File)

    fun commit(sessionId: Int, statusIntent: IntentSender)

    fun abandon(sessionId: Int)
}

fun commitApkSession(api: InstallSessionApi, apk: File, statusIntent: IntentSender): Int {
    val sessionId = api.createSession()
    try {
        api.writeApk(sessionId, apk.name, apk)
        api.commit(sessionId, statusIntent)
    } catch (failure: Exception) {
        api.abandon(sessionId)
        throw failure
    }
    return sessionId
}

sealed interface InstallStatusOutcome {
    data object ConfirmationNeeded : InstallStatusOutcome

    data object Installed : InstallStatusOutcome

    data class Failed(val message: String) : InstallStatusOutcome
}

fun interpretInstallStatus(status: Int, message: String?): InstallStatusOutcome = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallStatusOutcome.ConfirmationNeeded
    PackageInstaller.STATUS_SUCCESS -> InstallStatusOutcome.Installed
    else -> InstallStatusOutcome.Failed(message ?: "status $status")
}

class PackageInstallerSessionApi(context: Context) : InstallSessionApi {
    private val installer: PackageInstaller = context.packageManager.packageInstaller

    override fun createSession(): Int =
        installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))

    override fun writeApk(sessionId: Int, name: String, apk: File) {
        installer.openSession(sessionId).use { session ->
            session.openWrite(name, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
        }
    }

    override fun commit(sessionId: Int, statusIntent: IntentSender) {
        installer.openSession(sessionId).use { session -> session.commit(statusIntent) }
    }

    override fun abandon(sessionId: Int) {
        installer.abandonSession(sessionId)
    }
}
