package com.hiromishikata.timetrackingradio.adapter.update

import android.app.Application
import android.content.Intent
import com.hiromishikata.timetrackingradio.TestApplication
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateInstallActivityTest {
    private val application = RuntimeEnvironment.getApplication() as Application

    @After
    fun tearDown() {
        UpdateInstallActivity.ioDispatcher = Dispatchers.IO
        UpdateInstallActivity.mainDispatcher = Dispatchers.Main
    }

    private fun useInlineDispatchers() {
        UpdateInstallActivity.ioDispatcher = Dispatchers.Unconfined
        UpdateInstallActivity.mainDispatcher = Dispatchers.Unconfined
    }

    private fun launch(apkPath: String?) = Robolectric.buildActivity(
        UpdateInstallActivity::class.java,
        Intent().apply { apkPath?.let { putExtra(UpdateInstallActivity.EXTRA_APK_PATH, it) } },
    ).create().get()

    @Test
    fun finishesWithoutInstallingWhenNoApkPathIsGiven() {
        useInlineDispatchers()
        val activity = launch(null)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
