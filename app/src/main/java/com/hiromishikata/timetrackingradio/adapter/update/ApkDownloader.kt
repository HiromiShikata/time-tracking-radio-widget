package com.hiromishikata.timetrackingradio.adapter.update

import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.ApkDownloadRepository
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
    override fun download(url: String, expectedSizeBytes: Long): File {
        val updates = File(cacheDirectory, UPDATES_DIRECTORY)
        val partial = File(updates, PARTIAL_FILE_NAME)
        val target = File(updates, APK_FILE_NAME)
        try {
            updates.mkdirs()
            writeResponse(url, partial)
            verifyDownloadedSize(partial.length(), expectedSizeBytes)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } catch (failure: ApkDownloadException) {
            discard(partial, target)
            throw failure
        } catch (failure: Exception) {
            discard(partial, target)
            throw ApkDownloadException("download failed: $failure")
        }
    }

    private fun writeResponse(url: String, partial: File) {
        opener.open(url).use { response ->
            if (response.status !in 200..299) throw ApkDownloadException("download returned HTTP ${response.status}")
            partial.outputStream().use { output -> response.body.copyTo(output) }
        }
    }

    private fun discard(partial: File, target: File) {
        partial.delete()
        target.delete()
    }

    private companion object {
        const val UPDATES_DIRECTORY = "updates"
        const val PARTIAL_FILE_NAME = "app-update.apk.part"
        const val APK_FILE_NAME = "app-update.apk"
    }
}
