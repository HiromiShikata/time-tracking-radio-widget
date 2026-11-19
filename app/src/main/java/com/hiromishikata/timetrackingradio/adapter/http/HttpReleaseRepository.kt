package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.RequestDiagnostics
import com.hiromishikata.timetrackingradio.domain.RequestKind
import com.hiromishikata.timetrackingradio.domain.parseReleaseList
import java.io.IOException

class HttpReleaseRepository(
    private val releasesUrl: String,
    private val transport: HttpTransport,
) : ReleaseRepository {
    override fun readRecent(): List<LatestRelease> {
        val response = try {
            transport.send(HttpRequest("GET", releasesUrl, null, RequestDiagnostics(RequestKind.UPDATE_CHECK)))
        } catch (failure: IOException) {
            throw CommunicationFailedException("release check request failed", failure)
        }
        if (response.status !in 200..299) {
            throw CommunicationFailedException("release check returned HTTP ${response.status}")
        }
        val releases = try {
            parseReleaseList(response.body)
        } catch (failure: MalformedResponseException) {
            throw CommunicationFailedException("release check response was not understood", failure)
        }
        if (releases.isEmpty()) {
            throw CommunicationFailedException("release check returned no published release")
        }
        return releases
    }
}
