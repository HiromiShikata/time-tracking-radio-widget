package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException

private const val MAX_REDIRECTS = 5
private val REDIRECT_RANGE = 300..399
private val SUCCESS_RANGE = 200..299

/** Follows a POST answered by a redirect with GET and returns the body of the final HTTP 2xx response. */
fun followRedirects(transport: HttpTransport, initial: HttpResponse): String {
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
