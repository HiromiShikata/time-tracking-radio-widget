package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult
import com.hiromishikata.timetrackingradio.domain.encodeDiagnosticsUpload
import com.hiromishikata.timetrackingradio.domain.parseDiagnosticsUploadResponse
import java.io.IOException

class HttpDiagnosticsRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
) : DiagnosticsUploadRepository {
    override fun upload(entries: List<DiagnosticEntry>): DiagnosticsUploadResult {
        val body = try {
            followRedirects(transport, transport.send(HttpRequest("POST", endpointUrl, encodeDiagnosticsUpload(entries))))
        } catch (failure: IOException) {
            return DiagnosticsUploadResult.Failed("request failed: ${failure.javaClass.simpleName}")
        } catch (failure: CommunicationFailedException) {
            return DiagnosticsUploadResult.Failed(failure.message.orEmpty())
        }
        return parseDiagnosticsUploadResponse(body)
    }
}
