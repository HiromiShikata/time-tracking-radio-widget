package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.MalformedResponseException
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.NoDiagnostics
import com.hiromishikata.timetrackingradio.domain.ReportedSelection
import com.hiromishikata.timetrackingradio.domain.RequestDiagnostics
import com.hiromishikata.timetrackingradio.domain.RequestKind
import com.hiromishikata.timetrackingradio.domain.RequestTimer
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.UnstampedSelectionRepository
import com.hiromishikata.timetrackingradio.domain.encodeSelectionRequest
import com.hiromishikata.timetrackingradio.domain.encodeUnstampedSelectionRequest
import com.hiromishikata.timetrackingradio.domain.exceptionDetail
import com.hiromishikata.timetrackingradio.domain.parseSelectionResponse
import com.hiromishikata.timetrackingradio.domain.redactUrlQueries
import com.hiromishikata.timetrackingradio.domain.requestKindOf
import com.hiromishikata.timetrackingradio.domain.responseParsedDetail
import com.hiromishikata.timetrackingradio.domain.selectionRequestSummary
import com.hiromishikata.timetrackingradio.domain.unwrapTransportFailure
import java.io.IOException
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.UnknownServiceException
import java.time.Instant

class HttpSelectionRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
    private val diagnostics: DiagnosticSink = NoDiagnostics,
    private val timer: RequestTimer = RequestTimer(),
) : SelectionRepository, UnstampedSelectionRepository {
    override fun read(): SelectionResponse =
        communicate(HttpRequest("GET", endpointUrl, null, RequestDiagnostics(RequestKind.REFRESH)))

    override fun update(request: SelectionRequest, tappedAt: Instant): SelectionResponse = communicate(
        HttpRequest(
            "POST",
            endpointUrl,
            encodeSelectionRequest(request, tappedAt),
            RequestDiagnostics(requestKindOf(request), selectionRequestSummary(request, tappedAt)),
        ),
    )

    override fun updateUnstamped(request: SelectionRequest): SelectionResponse = communicate(
        HttpRequest(
            "POST",
            endpointUrl,
            encodeUnstampedSelectionRequest(request),
            RequestDiagnostics(requestKindOf(request), selectionRequestSummary(request, null)),
        ),
    )

    private fun communicate(initial: HttpRequest): SelectionResponse {
        timer.beginExchange()
        val body = try {
            followRedirects(transport, endpointUrl, transport.send(initial), initial.diagnostics)
        } catch (failure: IOException) {
            throw transportFailure(unwrapTransportFailure(failure))
        } catch (failure: CommunicationFailedException) {
            diagnostics.record(
                DiagnosticEventType.EXCEPTION,
                exceptionDetail(failure, FailurePhase.AFTER_RESPONSE, timer.millisSinceRequestSent()),
            )
            throw failure
        }
        return try {
            parseSelectionResponse(body).also { response -> recordOutcome(response) }
        } catch (failure: MalformedResponseException) {
            diagnostics.record(
                DiagnosticEventType.RESPONSE_PARSED,
                responseParsedDetail("malformed", null, redactUrlQueries(failure.message.orEmpty()), timer.millisSinceExchangeStart()),
            )
            throw CommunicationFailedException("endpoint returned an unexpected response", failure)
        }
    }

    private fun transportFailure(failure: IOException): CommunicationFailedException =
        if (failure is MalformedURLException || failure is UnknownServiceException || failure is ProtocolException) {
            CommunicationFailedException("request to the endpoint failed", failure)
        } else {
            NetworkUnavailableException(failure)
        }

    private fun recordOutcome(response: SelectionResponse) {
        when (response) {
            is SelectionResponse.Accepted -> diagnostics.record(
                DiagnosticEventType.RESPONSE_PARSED,
                responseParsedDetail("ok", response.selection, null, timer.millisSinceExchangeStart()),
            )
            is SelectionResponse.Rejected -> {
                val reported = (response.reported as? ReportedSelection.Reported)?.selection
                diagnostics.record(
                    DiagnosticEventType.RESPONSE_PARSED,
                    responseParsedDetail("ng", reported, redactUrlQueries(response.message), timer.millisSinceExchangeStart()),
                )
                diagnostics.record(DiagnosticEventType.SERVER_NG, redactUrlQueries(response.message))
            }
        }
    }
}
