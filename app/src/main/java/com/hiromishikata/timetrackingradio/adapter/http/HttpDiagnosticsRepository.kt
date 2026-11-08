package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult
import com.hiromishikata.timetrackingradio.domain.encodeDiagnosticsUpload
import com.hiromishikata.timetrackingradio.domain.parseDiagnosticsUploadResponse
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Total time one upload may take, across every request and redirect hop. */
const val DIAGNOSTICS_UPLOAD_DEADLINE_MILLIS = 8_000L

class HttpDiagnosticsRepository(
    private val endpointUrl: String,
    private val transport: HttpTransport,
    private val deadlineMillis: Long = DIAGNOSTICS_UPLOAD_DEADLINE_MILLIS,
) : DiagnosticsUploadRepository {
    /**
     * The whole upload runs on its own thread and is abandoned once the deadline passes. An abandoned upload
     * is reported as failed, so its entries stay undelivered and go out with the next upload.
     */
    override fun upload(entries: List<DiagnosticEntry>): DiagnosticsUploadResult {
        val attempt = FutureTask { send(entries) }
        Thread(attempt, "diagnostics-upload").apply { isDaemon = true }.start()
        return try {
            attempt.get(deadlineMillis, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            attempt.cancel(true)
            DiagnosticsUploadResult.Failed("upload deadline of $deadlineMillis ms exceeded")
        } catch (failure: ExecutionException) {
            DiagnosticsUploadResult.Failed("request failed: ${failure.cause?.javaClass?.simpleName}")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            DiagnosticsUploadResult.Failed("upload was interrupted")
        }
    }

    private fun send(entries: List<DiagnosticEntry>): DiagnosticsUploadResult {
        val body = try {
            followRedirects(transport, endpointUrl, transport.send(HttpRequest("POST", endpointUrl, encodeDiagnosticsUpload(entries))))
        } catch (failure: IOException) {
            return DiagnosticsUploadResult.Failed("request failed: ${failure.javaClass.simpleName}")
        } catch (failure: CommunicationFailedException) {
            return DiagnosticsUploadResult.Failed(failure.message.orEmpty())
        }
        return parseDiagnosticsUploadResponse(body)
    }
}
