package com.hiromishikata.timetrackingradio.adapter.update

import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class ApkDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val payload = byteArrayOf(1, 2, 3, 4)
    private val url = "https://example.com/app.apk"
    private val finalFile get() = File(folder.root, "updates/app-update.apk")
    private val partialFile get() = File(folder.root, "updates/app-update.apk.part")

    @Test
    fun savesTheVerifiedApkAndRemovesThePartialFile() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
        val file = downloader.download(url, payload.size.toLong())
        assertEquals(finalFile.canonicalPath, file.canonicalPath)
        assertArrayEquals(payload, file.readBytes())
        assertFalse(partialFile.exists())
    }

    @Test
    fun aSizeMismatchRemovesBothFilesAndThrows() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
        val thrown = runCatching { downloader.download(url, payload.size.toLong() + 1) }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is ApkDownloadException)
        assertEquals("downloaded size 4 does not match expected 5", thrown!!.message)
        assertFalse(finalFile.exists())
        assertFalse(partialFile.exists())
    }

    @Test
    fun aFailedTransferThrowsAndRemovesBothFiles() {
        data class Case(val name: String, val opener: StreamOpener)
        val cases = listOf(
            Case("HTTP 503", StreamOpener { _ -> StreamResponse(503, ByteArrayInputStream(payload)) }),
            Case("transport failure", StreamOpener { _ -> throw IOException("unreachable") }),
        )
        cases.forEach { case ->
            val downloader = ApkDownloader(folder.root, case.opener)
            val thrown = runCatching { downloader.download(url, payload.size.toLong()) }.exceptionOrNull()
            assertTrue("${case.name}: thrown=$thrown", thrown is ApkDownloadException)
            assertFalse(case.name, finalFile.exists())
            assertFalse(case.name, partialFile.exists())
        }
    }
}
