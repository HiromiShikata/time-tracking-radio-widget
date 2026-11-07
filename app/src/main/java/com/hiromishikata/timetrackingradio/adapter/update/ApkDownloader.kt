package com.hiromishikata.timetrackingradio.adapter.update

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

fun interface StreamOpener {
    fun open(url: String): StreamResponse
}

class StreamResponse(
    val status: Int,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        body.close()
        onClose()
    }
}

object HttpUrlStreamOpener : StreamOpener {
    override fun open(url: String): StreamResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        val status = connection.responseCode
        val body = (if (status >= 400) connection.errorStream else connection.inputStream) ?: ByteArrayInputStream(ByteArray(0))
        return StreamResponse(status, body) { connection.disconnect() }
    }
}

class ApkDownloader(
    private val cacheDirectory: File,
    private val opener: StreamOpener = HttpUrlStreamOpener,
) {
    fun download(apkUrl: String): File {
        val target = File(cacheDirectory, "updates/app-update.apk")
        opener.open(apkUrl).use { response ->
            if (response.status !in 200..299) throw IOException("download returned HTTP ${response.status}")
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> response.body.copyTo(output) }
        }
        return target
    }
}

fun interface ApkFetcher {
    fun fetch(apkUrl: String, cacheDirectory: File): File
}

object DefaultApkFetcher : ApkFetcher {
    override fun fetch(apkUrl: String, cacheDirectory: File): File = ApkDownloader(cacheDirectory).download(apkUrl)
}
