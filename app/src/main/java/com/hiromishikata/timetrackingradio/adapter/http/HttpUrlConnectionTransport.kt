package com.hiromishikata.timetrackingradio.adapter.http

import java.net.HttpURLConnection
import java.net.URL

/** Connect and read timeout for the diagnostics upload, so a stalled endpoint cannot hold a tap for long. */
const val DIAGNOSTICS_UPLOAD_TIMEOUT_MILLIS = 5_000

class HttpUrlConnectionTransport(private val timeoutMillis: Int = 10_000) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(status, location, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "time-tracking-radio-widget"
    }
}
