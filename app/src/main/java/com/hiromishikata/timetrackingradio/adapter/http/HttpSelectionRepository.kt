package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.encodeSelectionRequest
import com.hiromishikata.timetrackingradio.domain.parseSelectionResponse
import java.io.IOException

class HttpSelectionRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
) : SelectionRepository {
    override fun read(): SelectionResponse =
        communicate { transport.send(HttpRequest("GET", endpointUrl, null)) }

    override fun update(request: SelectionRequest): SelectionResponse =
        communicate { transport.send(HttpRequest("POST", endpointUrl, encodeSelectionRequest(request))) }

    private fun communicate(send: () -> HttpResponse): SelectionResponse {
        val body = try {
            followRedirects(send())
        } catch (failure: IOException) {
            throw CommunicationFailedException("request to the endpoint failed", failure)
        }
        return try {
            parseSelectionResponse(body)
        } catch (failure: MalformedResponseException) {
            throw CommunicationFailedException("endpoint returned an unexpected response", failure)
        }
    }

    private fun followRedirects(initial: HttpResponse): String {
        var response = initial
        var hops = 0
        while (response.status in REDIRECT_RANGE) {
            val location = response.location
                ?: throw CommunicationFailedException("endpoint redirected without a location")
            if (hops == MAX_REDIRECTS) throw CommunicationFailedException("endpoint redirected too many times")
            response = transport.send(HttpRequest("GET", location, null))
            hops++
        }
        if (response.status !in SUCCESS_RANGE) {
            throw CommunicationFailedException("endpoint returned HTTP ${response.status}")
        }
        return response.body
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_RANGE = 300..399
        val SUCCESS_RANGE = 200..299
    }
}
