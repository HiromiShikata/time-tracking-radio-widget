package com.hiromishikata.timetrackingradio.domain

import java.io.File

interface ApkDownloadRepository {
    fun download(url: String, expectedSizeBytes: Long): File
}

class ApkDownloadException(message: String) : RuntimeException(message)

fun verifyDownloadedSize(actualBytes: Long, expectedBytes: Long) {
    if (actualBytes != expectedBytes) {
        throw ApkDownloadException("downloaded size $actualBytes does not match expected $expectedBytes")
    }
}
