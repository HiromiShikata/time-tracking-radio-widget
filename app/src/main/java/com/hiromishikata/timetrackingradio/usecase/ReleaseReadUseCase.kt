package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository

class ReleaseReadUseCase(private val releases: ReleaseRepository) {
    fun execute(): LatestRelease = releases.readLatest()
}
