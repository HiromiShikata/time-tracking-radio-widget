package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.NoDiagnostics
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.describeFailure
import com.hiromishikata.timetrackingradio.domain.encodeSelectionRequest
import com.hiromishikata.timetrackingradio.domain.parseSelectionResponse
import com.hiromishikata.timetrackingradio.domain.redactUrlQueries
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

class HttpSelectionRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
    private val diagnostics: DiagnosticSink = NoDiagnostics,
) : SelectionRepository {
    override fun read(): SelectionResponse =
        communicate { transport.send(HttpRequest("GET", endpointUrl, null)) }

    override fun update(request: SelectionRequest, tappedAt: Instant): SelectionResponse =
        communicate { transport.send(HttpRequest("POST", endpointUrl, encodeSelectionRequest(request, tappedAt))) }

    private fun communicate(send: () -> HttpResponse): SelectionResponse {
        val body = try {
            followRedirects(transport, endpointUrl, send())
        } catch (failure: IOException) {
            diagnostics.record(DiagnosticEventType.EXCEPTION, describeFailure(failure))
            throw if (failure.isNetworkLevel()) {
                NetworkUnavailableException(failure)
            } else {
                CommunicationFailedException("request to the endpoint failed", failure)
            }
        } catch (failure: CommunicationFailedException) {
            diagnostics.record(DiagnosticEventType.EXCEPTION, describeFailure(failure))
            throw failure
        }
        return try {
            parseSelectionResponse(body).also { response -> recordOutcome(response) }
        } catch (failure: MalformedResponseException) {
            diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "malformed: ${redactUrlQueries(failure.message.orEmpty())}")
            throw CommunicationFailedException("endpoint returned an unexpected response", failure)
        }
    }

    private fun IOException.isNetworkLevel(): Boolean =
        this is UnknownHostException ||
            this is SocketTimeoutException ||
            this is ConnectException ||
            this is SocketException ||
            this is SSLHandshakeException

    private fun recordOutcome(response: SelectionResponse) {
        when (response) {
            is SelectionResponse.Accepted -> diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "ok")
            is SelectionResponse.Rejected -> {
                diagnostics.record(DiagnosticEventType.RESPONSE_PARSED, "ng")
                diagnostics.record(DiagnosticEventType.SERVER_NG, redactUrlQueries(response.message))
            }
        }
    }
}
