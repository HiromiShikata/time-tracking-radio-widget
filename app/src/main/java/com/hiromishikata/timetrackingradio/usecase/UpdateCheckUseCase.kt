package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import com.hiromishikata.timetrackingradio.domain.UpdateDecision
import com.hiromishikata.timetrackingradio.domain.decideUpdate
import com.hiromishikata.timetrackingradio.domain.parseSemanticVersion

enum class UpdateCheckStatus { COMPLETED, RETRY, FAILED }

class UpdateCheckUseCase(
    private val releases: ReleaseRepository,
    private val announcer: UpdateAnnouncer,
) {
    fun execute(installedVersionName: String): UpdateCheckStatus {
        val installed = parseSemanticVersion(installedVersionName) ?: return UpdateCheckStatus.FAILED
        val latest = try {
            ReleaseReadUseCase(releases).execute()
        } catch (failure: CommunicationFailedException) {
            return UpdateCheckStatus.RETRY
        }
        when (val decision = decideUpdate(installed, latest)) {
            is UpdateDecision.Announce -> announcer.announce(decision.version, decision.apkDownloadUrl)
            is UpdateDecision.NewerWithoutApk, UpdateDecision.UpToDate -> Unit
        }
        return UpdateCheckStatus.COMPLETED
    }
}
