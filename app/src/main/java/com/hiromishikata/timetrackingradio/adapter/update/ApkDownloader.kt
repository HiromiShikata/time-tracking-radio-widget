package com.hiromishikata.timetrackingradio.adapter.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApkDownloader(private val cacheDirectory: File) {
    fun download(apkUrl: String): File {
        val target = File(cacheDirectory, "updates/app-update.apk")
        target.parentFile?.mkdirs()
        val connection = URL(apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("download returned HTTP $status")
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        return target
    }
}
