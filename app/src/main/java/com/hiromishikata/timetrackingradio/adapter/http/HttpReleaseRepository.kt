package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.LatestRelease
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.ReleaseRepository
import com.hiromishikata.timetrackingradio.domain.parseLatestRelease
import java.io.IOException

class HttpReleaseRepository(
    private val latestReleaseUrl: String,
    private val transport: HttpTransport,
) : ReleaseRepository {
    override fun readLatest(): LatestRelease {
        val response = try {
            transport.send(HttpRequest("GET", latestReleaseUrl, null))
        } catch (failure: IOException) {
            throw CommunicationFailedException("release check request failed", failure)
        }
        if (response.status !in 200..299) {
            throw CommunicationFailedException("release check returned HTTP ${response.status}")
        }
        return try {
            parseLatestRelease(response.body)
        } catch (failure: MalformedResponseException) {
            throw CommunicationFailedException("release check response was not understood", failure)
        }
    }
}
