package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.LastUpdateCheckRepository
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import com.hiromishikata.timetrackingradio.domain.UpdateDecision
import com.hiromishikata.timetrackingradio.domain.decideUpdate
import com.hiromishikata.timetrackingradio.domain.parseSemanticVersion
import java.time.Instant

enum class UpdateCheckStatus { COMPLETED, RETRY, RETRY_DOWNLOAD, FAILED }

class UpdateCheckUseCase(
    private val releases: ReleaseRepository,
    private val downloads: ApkDownloadRepository,
    private val announcer: UpdateAnnouncer,
    private val diagnostics: DiagnosticSink,
    private val lastChecked: LastUpdateCheckRepository,
    private val clock: () -> Instant,
) {
    fun execute(installedVersionName: String): UpdateCheckStatus {
        val permitted = announcer.notificationsPermitted()
        val installed = parseSemanticVersion(installedVersionName)
        if (installed == null) {
            recordCheck(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, DOWNLOAD_NONE, "installed version is not x.y.z", permitted, false)
            return UpdateCheckStatus.FAILED
        }
        return try {
            val latest = ReleaseReadUseCase(releases).execute()
            val latestText = "v${latest.version}"
            when (val decision = decideUpdate(installed, latest)) {
                is UpdateDecision.Announce -> announceNewer(installedVersionName, latestText, decision, permitted)
                UpdateDecision.UpToDate -> complete(installedVersionName, latestText, DECISION_UP_TO_DATE, DOWNLOAD_NONE, permitted, false)
                is UpdateDecision.NewerWithoutApk -> complete(installedVersionName, latestText, DECISION_NEWER_WITHOUT_APK, DOWNLOAD_NONE, permitted, false)
            }
        } catch (failure: CommunicationFailedException) {
            recordCheck(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, DOWNLOAD_NONE, failure.message.orEmpty(), permitted, false)
            UpdateCheckStatus.RETRY
        } catch (failure: Exception) {
            recordCheck(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, DOWNLOAD_NONE, failure.message.orEmpty(), permitted, false)
            UpdateCheckStatus.FAILED
        }
    }

    private fun announceNewer(
        installedName: String,
        latestText: String,
        decision: UpdateDecision.Announce,
        permitted: Boolean,
    ): UpdateCheckStatus {
        if (!permitted) {
            return complete(installedName, latestText, DECISION_NEWER_AVAILABLE, DOWNLOAD_SKIPPED, permitted, false)
        }
        val apk = try {
            downloads.download(decision.apkDownloadUrl, decision.apkSizeBytes, decision.version)
        } catch (failure: ApkDownloadException) {
            recordCheck(installedName, latestText, DECISION_NEWER_AVAILABLE, DOWNLOAD_FAILED, failure.message.orEmpty(), permitted, false)
            return if (failure.networkReason) UpdateCheckStatus.RETRY_DOWNLOAD else UpdateCheckStatus.RETRY
        }
        val notified = try {
            announcer.announce(decision.version, apk)
        } catch (failure: Exception) {
            recordCheck(installedName, latestText, DECISION_NEWER_AVAILABLE, DOWNLOAD_OK, failure.message.orEmpty(), permitted, false)
            return UpdateCheckStatus.FAILED
        }
        return complete(installedName, latestText, DECISION_NEWER_AVAILABLE, DOWNLOAD_OK, permitted, notified)
    }

    private fun complete(
        installedName: String,
        latestText: String,
        decision: String,
        download: String,
        permitted: Boolean,
        notified: Boolean,
    ): UpdateCheckStatus {
        lastChecked.write(clock())
        recordCheck(installedName, latestText, decision, download, null, permitted, notified)
        return UpdateCheckStatus.COMPLETED
    }

    private fun recordCheck(
        installedName: String,
        latest: String,
        decision: String,
        download: String,
        reason: String?,
        permitted: Boolean,
        notified: Boolean,
    ) {
        val reasonPart = reason?.let { "reason=$it, " }.orEmpty()
        diagnostics.record(
            DiagnosticEventType.UPDATE_CHECK,
            "installed=$installedName, latest=$latest, decision=$decision, download=$download, $reasonPart" +
                "notifications_permitted=$permitted, notified=$notified",
        )
    }

    private companion object {
        const val LATEST_NONE = "none"
        const val DECISION_CHECK_FAILED = "check_failed"
        const val DECISION_NEWER_AVAILABLE = "newer_available"
        const val DECISION_NEWER_WITHOUT_APK = "newer_without_apk"
        const val DECISION_UP_TO_DATE = "up_to_date"
        const val DOWNLOAD_NONE = "none"
        const val DOWNLOAD_SKIPPED = "skipped"
        const val DOWNLOAD_OK = "ok"
        const val DOWNLOAD_FAILED = "failed"
    }
}
