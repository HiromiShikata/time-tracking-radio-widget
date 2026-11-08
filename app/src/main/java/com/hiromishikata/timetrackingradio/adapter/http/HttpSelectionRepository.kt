package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.NoDiagnostics
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.describeFailure
import com.hiromishikata.timetrackingradio.domain.encodeSelectionRequest
import com.hiromishikata.timetrackingradio.domain.parseSelectionResponse
import java.io.IOException

class HttpSelectionRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
    private val diagnostics: DiagnosticSink = NoDiagnostics,
) : SelectionRepository {
    override fun read(): SelectionResponse =
        communicate { transport.send(HttpRequest("GET", endpointUrl, null)) }

    override fun update(request: SelectionRequest): SelectionResponse =
        communicate { transport.send(HttpRequest("POST", endpointUrl, encodeSelectionRequest(request))) }

    private fun communicate(send: () -> HttpResponse): SelectionResponse {
        val body = try {
            followRedirects(transport, send())
        } catch (failure: IOException) {
            diagnostics.record(DiagnosticEventType.EXCEPTION, describeFailure(failure))
            throw CommunicationFailedException("request to the endpoint failed", failure)
        } catch (failure: CommunicationFailedException) {
            diagnostics.record(DiagnosticEventType.EXCEPTION, describeFailure(failure))
            throw failure
        }
        return try {
            parseSelectionResponse(body).also { response -> recordOutcome(response) }
        } catch (failure: MalformedResponseException) {
            diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "malformed: ${failure.message}")
            throw CommunicationFailedException("endpoint returned an unexpected response", failure)
        }
    }

    private fun recordOutcome(response: SelectionResponse) {
        when (response) {
            is SelectionResponse.Accepted -> diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "ok")
            is SelectionResponse.Rejected -> {
                diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "ng")
                diagnostics.record(DiagnosticEventType.SERVER_NG, response.message)
            }
        }
    }
}
