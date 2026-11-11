package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.RequestDiagnostics

data class HttpRequest(
    val method: String,
    val url: String,
    val body: String?,
    val diagnostics: RequestDiagnostics? = null,
)

data class HttpResponse(val status: Int, val location: String?, val body: String)

interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}
