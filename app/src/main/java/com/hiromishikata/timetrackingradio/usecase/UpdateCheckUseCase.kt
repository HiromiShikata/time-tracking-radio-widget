package com.hiromishikata.timetrackingradio.usecase

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

enum class UpdateCheckStatus { COMPLETED, RETRY, FAILED }

class UpdateCheckUseCase(
    private val releases: ReleaseRepository,
    private val announcer: UpdateAnnouncer,
    private val diagnostics: DiagnosticSink,
    private val lastChecked: LastUpdateCheckRepository,
    private val clock: () -> Instant,
) {
    fun execute(installedVersionName: String): UpdateCheckStatus {
        val permitted = announcer.notificationsPermitted()
        val installed = parseSemanticVersion(installedVersionName)
        if (installed == null) {
            record(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, "installed version is not x.y.z", permitted, false)
            return UpdateCheckStatus.FAILED
        }
        val latest = try {
            ReleaseReadUseCase(releases).execute()
        } catch (failure: CommunicationFailedException) {
            record(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, failure.message.orEmpty(), permitted, false)
            return UpdateCheckStatus.RETRY
        }
        val latestText = "v${latest.version}"
        when (val decision = decideUpdate(installed, latest)) {
            is UpdateDecision.Announce -> {
                val notified = announcer.announce(decision.version, decision.apkDownloadUrl)
                record(installedVersionName, latestText, DECISION_NEWER_AVAILABLE, null, permitted, notified)
            }
            UpdateDecision.UpToDate -> record(installedVersionName, latestText, DECISION_UP_TO_DATE, null, permitted, false)
            is UpdateDecision.NewerWithoutApk -> record(installedVersionName, latestText, DECISION_NEWER_WITHOUT_APK, null, permitted, false)
        }
        lastChecked.write(clock())
        return UpdateCheckStatus.COMPLETED
    }

    private fun record(
        installedName: String,
        latest: String,
        decision: String,
        reason: String?,
        permitted: Boolean,
        notified: Boolean,
    ) {
        val reasonPart = reason?.let { "reason=$it, " }.orEmpty()
        diagnostics.record(
            DiagnosticEventType.UPDATE_CHECK,
            "installed=$installedName, latest=$latest, decision=$decision, $reasonPart" +
                "notifications_permitted=$permitted, notified=$notified",
        )
    }

    private companion object {
        const val LATEST_NONE = "none"
        const val DECISION_CHECK_FAILED = "check_failed"
        const val DECISION_NEWER_AVAILABLE = "newer_available"
        const val DECISION_NEWER_WITHOUT_APK = "newer_without_apk"
        const val DECISION_UP_TO_DATE = "up_to_date"
    }
}
