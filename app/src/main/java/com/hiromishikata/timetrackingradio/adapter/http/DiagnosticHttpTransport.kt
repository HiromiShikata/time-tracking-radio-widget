package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.hostOf
import com.hiromishikata.timetrackingradio.domain.redactEndpointKey

/**
 * Records every request and every response hop. The configured endpoint is logged with its key redacted;
 * any other URL (such as a redirect target, which may carry its own token) is logged as its host only.
 */
class DiagnosticHttpTransport(
    private val delegate: HttpTransport,
    private val endpointUrl: String,
    private val diagnostics: DiagnosticSink,
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        diagnostics.record(DiagnosticEventType.REQUEST_SENT, describeRequest(request))
        val response = delegate.send(request)
        diagnostics.record(DiagnosticEventType.HTTP_RESPONSE, describeResponse(response))
        return response
    }

    private fun describeRequest(request: HttpRequest): String {
        val target = if (request.url == endpointUrl) redactEndpointKey(request.url) else "host=${hostOf(request.url)}"
        return "${request.method} $target"
    }

    private fun describeResponse(response: HttpResponse): String {
        val location = response.location?.let { " location_host=${hostOf(it)}" }.orEmpty()
        return "status=${response.status}$location"
    }
}
