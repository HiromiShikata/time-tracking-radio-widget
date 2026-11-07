package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository

class FakeReleaseRepository(
    private val onRead: () -> LatestRelease = { throw CommunicationFailedException("read not scripted") },
) : ReleaseRepository {
    var readCount = 0
        private set

    override fun readLatest(): LatestRelease {
        readCount++
        return onRead()
    }
}
