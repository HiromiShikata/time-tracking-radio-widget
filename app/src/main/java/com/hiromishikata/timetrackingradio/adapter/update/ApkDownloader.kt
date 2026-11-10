package com.hiromishikata.timetrackingradio.adapter.update

import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.verifyDownloadedSize
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
) : ApkDownloadRepository {
    override fun download(url: String, expectedSizeBytes: Long, version: SemanticVersion): File {
        val updates = File(cacheDirectory, UPDATES_DIRECTORY)
        val target = File(updates, "app-update-$version.apk")
        if (target.isFile && target.length() == expectedSizeBytes) return target
        val partial = File(updates, "${target.name}.part")
        try {
            updates.mkdirs()
            writeResponse(url, partial)
            verifyDownloadedSize(partial.length(), expectedSizeBytes)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } catch (failure: ApkDownloadException) {
            partial.delete()
            throw failure
        } catch (failure: Exception) {
            partial.delete()
            throw ApkDownloadException("download failed: $failure")
        }
    }

    private fun writeResponse(url: String, partial: File) {
        opener.open(url).use { response ->
            if (response.status !in 200..299) throw ApkDownloadException("download returned HTTP ${response.status}")
            partial.outputStream().use { output -> response.body.copyTo(output) }
        }
    }

    private companion object {
        const val UPDATES_DIRECTORY = "updates"
    }
}
