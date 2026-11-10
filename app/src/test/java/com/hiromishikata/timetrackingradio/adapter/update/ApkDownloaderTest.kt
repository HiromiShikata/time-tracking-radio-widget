package com.hiromishikata.timetrackingradio.adapter.update

import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ApkDownloadException
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class ApkDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val payload = byteArrayOf(1, 2, 3, 4)
    private val url = "https://example.com/app.apk"
    private val version = SemanticVersion(1, 0, 2)
    private val finalFile get() = File(folder.root, "updates/app-update-1.0.2.apk")

    private fun updatesFiles(): List<String> = File(folder.root, "updates").list()?.sorted().orEmpty()

    @Test
    fun savesTheVerifiedApkAndRemovesThePartialFile() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
        val file = downloader.download(url, payload.size.toLong(), version)
        assertEquals(finalFile.canonicalPath, file.canonicalPath)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(listOf(finalFile.name), updatesFiles())
    }

    @Test
    fun aSizeMismatchRemovesBothFilesAndThrows() {
        val downloader = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
        val thrown = runCatching { downloader.download(url, payload.size.toLong() + 1, version) }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is ApkDownloadException)
        assertEquals("downloaded size 4 does not match expected 5", thrown!!.message)
        assertEquals(emptyList<String>(), updatesFiles())
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
            val thrown = runCatching { downloader.download(url, payload.size.toLong(), version) }.exceptionOrNull()
            assertTrue("${case.name}: thrown=$thrown", thrown is ApkDownloadException)
            assertEquals(case.name, emptyList<String>(), updatesFiles())
        }
    }

    @Test
    fun aFailedDownloadDeletesOnlyThePartialFile() {
        val verified = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
            .download(url, payload.size.toLong(), SemanticVersion(1, 0, 2))
        val newerVersion = SemanticVersion(1, 0, 3)
        data class Case(val name: String, val opener: StreamOpener, val expectedSizeBytes: Long)
        val cases = listOf(
            Case("HTTP 503", StreamOpener { _ -> StreamResponse(503, ByteArrayInputStream(payload)) }, payload.size.toLong()),
            Case("size mismatch", StreamOpener { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }, payload.size.toLong() + 1),
            Case("transport failure", StreamOpener { _ -> throw IOException("unreachable") }, payload.size.toLong()),
        )
        cases.forEach { case ->
            val thrown = runCatching {
                ApkDownloader(folder.root, case.opener).download(url, case.expectedSizeBytes, newerVersion)
            }.exceptionOrNull()
            assertTrue("${case.name}: thrown=$thrown", thrown is ApkDownloadException)
            assertEquals(case.name, listOf(verified.name), updatesFiles())
            assertArrayEquals(case.name, payload, verified.readBytes())
        }
    }

    @Test
    fun aVerifiedDownloadRemovesOtherVersionedApksAndKeepsTheCurrentOne() {
        val updates = File(folder.root, "updates").apply { mkdirs() }
        File(updates, "app-update-1.0.1.apk").writeBytes(byteArrayOf(5))
        File(updates, "app-update-1.0.2.apk").writeBytes(byteArrayOf(6))
        val downloaded = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
            .download(url, payload.size.toLong(), SemanticVersion(1, 0, 3))
        assertEquals("app-update-1.0.3.apk", downloaded.name)
        assertEquals(listOf("app-update-1.0.3.apk"), updatesFiles().filter { it.startsWith("app-update-") && it.endsWith(".apk") })
    }

    @Test
    fun aFailedDownloadKeepsOtherVersionedApks() {
        val updates = File(folder.root, "updates").apply { mkdirs() }
        File(updates, "app-update-1.0.1.apk").writeBytes(byteArrayOf(5))
        File(updates, "app-update-1.0.2.apk").writeBytes(byteArrayOf(6))
        val thrown = runCatching {
            ApkDownloader(folder.root) { _ -> StreamResponse(503, ByteArrayInputStream(payload)) }
                .download(url, payload.size.toLong(), SemanticVersion(1, 0, 3))
        }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is ApkDownloadException)
        assertEquals(listOf("app-update-1.0.1.apk", "app-update-1.0.2.apk"), updatesFiles())
    }

    @Test
    fun aStaleFileThatCannotBeDeletedDoesNotFailAVerifiedDownload() {
        val updates = File(folder.root, "updates").apply { mkdirs() }
        val undeletableStale = File(updates, "app-update-1.0.1.apk").apply { mkdirs() }
        File(undeletableStale, "content.bin").writeBytes(byteArrayOf(5))
        val downloaded = ApkDownloader(folder.root) { _ -> StreamResponse(200, ByteArrayInputStream(payload)) }
            .download(url, payload.size.toLong(), SemanticVersion(1, 0, 3))
        assertEquals("app-update-1.0.3.apk", downloaded.name)
        assertArrayEquals(payload, downloaded.readBytes())
    }

    @Test
    fun aVerifiedApkOfTheSameVersionAndSizeIsReusedWithoutDownloading() {
        var opened = 0
        val downloader = ApkDownloader(folder.root) { _ ->
            opened++
            StreamResponse(200, ByteArrayInputStream(payload))
        }
        val first = downloader.download(url, payload.size.toLong(), version)
        val second = downloader.download(url, payload.size.toLong(), version)
        assertEquals(1, opened)
        assertEquals(first.canonicalPath, second.canonicalPath)
        assertArrayEquals(payload, second.readBytes())
    }

    @Test
    fun aSameVersionWithADifferentSizeIsDownloadedAgain() {
        var opened = 0
        var body = payload
        val downloader = ApkDownloader(folder.root) { _ ->
            opened++
            StreamResponse(200, ByteArrayInputStream(body))
        }
        downloader.download(url, payload.size.toLong(), version)
        val replacement = byteArrayOf(9, 8, 7, 6, 5, 4)
        body = replacement
        val second = downloader.download(url, replacement.size.toLong(), version)
        assertEquals(2, opened)
        assertArrayEquals(replacement, second.readBytes())
    }
}
