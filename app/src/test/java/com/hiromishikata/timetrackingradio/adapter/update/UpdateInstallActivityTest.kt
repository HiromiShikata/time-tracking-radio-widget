package com.hiromishikata.timetrackingradio.adapter.update

import android.app.Application
import android.content.Intent
import com.hiromishikata.timetrackingradio.TestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateInstallActivityTest {
    private val application = RuntimeEnvironment.getApplication() as Application

    @After
    fun tearDown() {
        UpdateInstallActivity.fetcher = DefaultApkFetcher
        UpdateInstallActivity.ioDispatcher = Dispatchers.IO
        UpdateInstallActivity.mainDispatcher = Dispatchers.Main
    }

    private fun useInlineDispatchers() {
        UpdateInstallActivity.ioDispatcher = Dispatchers.Unconfined
        UpdateInstallActivity.mainDispatcher = Dispatchers.Unconfined
    }

    private fun launch(apkUrl: String?) = Robolectric.buildActivity(
        UpdateInstallActivity::class.java,
        Intent().apply { apkUrl?.let { putExtra(UpdateInstallActivity.EXTRA_APK_URL, it) } },
    ).create().get()

    @Test
    fun finishesWithoutInstallingWhenNoApkUrlIsGiven() {
        useInlineDispatchers()
        val activity = launch(null)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun startsThePackageInstallerWithTheDownloadedApk() {
        useInlineDispatchers()
        UpdateInstallActivity.fetcher = ApkFetcher { _, cacheDirectory ->
            File(cacheDirectory, "updates/app-update.apk").also {
                it.parentFile!!.mkdirs()
                it.writeBytes(byteArrayOf(1))
            }
        }
        val activity = launch("https://example.com/app.apk")
        assertTrue(activity.isFinishing)
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/vnd.android.package-archive", started.type)
        assertEquals("content", started.data!!.scheme)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun showsAMessageAndDoesNotInstallWhenTheDownloadFails() {
        useInlineDispatchers()
        UpdateInstallActivity.fetcher = ApkFetcher { _, _ -> throw IOException("download returned HTTP 500") }
        val activity = launch("https://example.com/app.apk")
        assertEquals("Could not download the update.", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
