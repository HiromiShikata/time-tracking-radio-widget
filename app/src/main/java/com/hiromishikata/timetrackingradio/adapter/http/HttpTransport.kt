package com.hiromishikata.timetrackingradio.adapter.http

data class HttpRequest(val method: String, val url: String, val body: String?)

data class HttpResponse(val status: Int, val location: String?, val body: String)

interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}
