package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import java.net.URI

private const val MAX_REDIRECTS = 5
private val REDIRECT_RANGE = 300..399
private val SUCCESS_RANGE = 200..299

/**
 * Follows a POST answered by a redirect with GET and returns the body of the final HTTP 2xx response.
 * A relative Location is resolved against the URL of the request that received it.
 */
fun followRedirects(transport: HttpTransport, requestUrl: String, initial: HttpResponse): String {
    var response = initial
    var currentUrl = requestUrl
    var hops = 0
    while (response.status in REDIRECT_RANGE) {
        val location = response.location
            ?: throw CommunicationFailedException("endpoint redirected without a location")
        if (hops == MAX_REDIRECTS) throw CommunicationFailedException("endpoint redirected too many times")
        currentUrl = resolveLocation(currentUrl, location)
        response = transport.send(HttpRequest("GET", currentUrl, null))
        hops++
    }
    if (response.status !in SUCCESS_RANGE) {
        throw CommunicationFailedException("endpoint returned HTTP ${response.status}")
    }
    return response.body
}

private fun resolveLocation(base: String, location: String): String = try {
    URI(base).resolve(location).toString()
} catch (failure: IllegalArgumentException) {
    throw CommunicationFailedException("endpoint redirected to an invalid location", failure)
}
