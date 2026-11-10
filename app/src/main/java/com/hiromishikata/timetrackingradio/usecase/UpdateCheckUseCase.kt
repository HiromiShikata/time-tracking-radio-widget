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
        return try {
            val latest = ReleaseReadUseCase(releases).execute()
            val latestText = "v${latest.version}"
            val (decisionText, notified) = when (val decision = decideUpdate(installed, latest)) {
                is UpdateDecision.Announce -> DECISION_NEWER_AVAILABLE to announcer.announce(decision.version, decision.apkDownloadUrl)
                UpdateDecision.UpToDate -> DECISION_UP_TO_DATE to false
                is UpdateDecision.NewerWithoutApk -> DECISION_NEWER_WITHOUT_APK to false
            }
            lastChecked.write(clock())
            record(installedVersionName, latestText, decisionText, null, permitted, notified)
            UpdateCheckStatus.COMPLETED
        } catch (failure: CommunicationFailedException) {
            record(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, failure.message.orEmpty(), permitted, false)
            UpdateCheckStatus.RETRY
        } catch (failure: Exception) {
            record(installedVersionName, LATEST_NONE, DECISION_CHECK_FAILED, failure.message.orEmpty(), permitted, false)
            UpdateCheckStatus.FAILED
        }
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
