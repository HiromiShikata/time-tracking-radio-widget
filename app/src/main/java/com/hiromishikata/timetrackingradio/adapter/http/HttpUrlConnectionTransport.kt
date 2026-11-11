package com.hiromishikata.timetrackingradio.adapter.http

import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Connect and read timeout for the diagnostics upload, so a stalled endpoint cannot hold a tap for long. */
const val DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS = 5_000

class HttpUrlConnectionTransport(private val timeoutMillis: Int = 10_000) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = connecting { URL(request.url).openConnection() as HttpURLConnection }
        try {
            connecting {
                connection.requestMethod = request.method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val body = request.body
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                }
                connection.connect()
                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val (status, location) = reading {
                connection.responseCode to connection.getHeaderField("Location")
            }
            val body = reading {
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            }
            return HttpResponse(status, location, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "time-tracking-radio-widget"
    }
}

private inline fun <T> connecting(block: () -> T): T = inPhase(FailurePhase.CONNECTING, block)

private inline fun <T> reading(block: () -> T): T = inPhase(FailurePhase.READING, block)

private inline fun <T> inPhase(phase: FailurePhase, block: () -> T): T = try {
    block()
} catch (failure: IOException) {
    throw TransportPhaseFailure(phase, failure)
}
