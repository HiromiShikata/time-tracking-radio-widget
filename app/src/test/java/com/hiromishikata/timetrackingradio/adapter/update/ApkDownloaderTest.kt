package com.hiromishikata.timetrackingradio.adapter.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class ApkDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val payload = byteArrayOf(1, 2, 3, 4)

    @Test
    fun writesTheApkOnSuccess() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
        val file = downloader.download("https://example.com/app.apk")
        assertArrayEquals(payload, file.readBytes())
        assertTrue(file.path.startsWith(folder.root.path))
    }

    @Test
    fun failsWithoutWritingWhenTheServerRefuses() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(404, ByteArrayInputStream(payload)) }
        val thrown = runCatching { downloader.download("https://example.com/app.apk") }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IOException && thrown.message!!.contains("404"))
        assertFalse(java.io.File(folder.root, "updates/app-update.apk").exists())
    }

    @Test
    fun propagatesATransportFailure() {
        val downloader = ApkDownloader(folder.root) { _ -> throw IOException("unreachable") }
        val thrown = runCatching { downloader.download("https://example.com/app.apk") }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IOException)
        assertFalse(java.io.File(folder.root, "updates/app-update.apk").exists())
    }
}
