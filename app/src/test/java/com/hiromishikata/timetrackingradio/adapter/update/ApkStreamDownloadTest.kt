package com.hiromishikata.timetrackingradio.adapter.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ApkStreamDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val payload = ByteArray(2048) { (it % 251).toByte() }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun streamsTheApkBytesToTheCacheDirectory() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val file = ApkDownloader(folder.root, HttpUrlStreamOpener).download(server.url("/app.apk").toString())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun refusedDownloadWritesNothing() {
        server.enqueue(MockResponse().setResponseCode(404))
        val thrown = runCatching {
            ApkDownloader(folder.root, HttpUrlStreamOpener).download(server.url("/app.apk").toString())
        }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IOException && thrown.message!!.contains("404"))
        assertFalse(File(folder.root, "updates/app-update.apk").exists())
    }
}
