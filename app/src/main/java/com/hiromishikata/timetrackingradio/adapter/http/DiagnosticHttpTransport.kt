package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.RequestTimer
import com.hiromishikata.timetrackingradio.domain.exceptionDetail
import com.hiromishikata.timetrackingradio.domain.failurePhaseOf
import com.hiromishikata.timetrackingradio.domain.hostOf
import com.hiromishikata.timetrackingradio.domain.httpResponseDetail
import com.hiromishikata.timetrackingradio.domain.redactEndpointKey
import com.hiromishikata.timetrackingradio.domain.requestSentDetail
import com.hiromishikata.timetrackingradio.domain.unwrapTransportFailure
import java.io.IOException

class DiagnosticHttpTransport(
    private val delegate: HttpTransport,
    private val endpointUrl: String,
    private val diagnostics: DiagnosticSink,
    private val timer: RequestTimer = RequestTimer(),
) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        timer.requestSent()
        diagnostics.record(
            DiagnosticEventType.REQUEST_SENT,
            requestSentDetail(request.diagnostics, request.method, describeTarget(request.url)),
        )
        val response = try {
            delegate.send(request)
        } catch (failure: IOException) {
            diagnostics.record(
                DiagnosticEventType.EXCEPTION,
                exceptionDetail(unwrapTransportFailure(failure), failurePhaseOf(failure), timer.millisSinceRequestSent()),
            )
            throw failure
        }
        diagnostics.record(
            DiagnosticEventType.HTTP_RESPONSE,
            httpResponseDetail(response.status, response.location, timer.millisSinceRequestSent()),
        )
        return response
    }

    private fun describeTarget(url: String): String = if (url == endpointUrl) redactEndpointKey(url) else "host=${hostOf(url)}"
}
