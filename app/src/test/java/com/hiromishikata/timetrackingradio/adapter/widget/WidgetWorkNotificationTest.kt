package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.Application
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.resourceIdOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetWorkNotificationTest {
    private val context = RuntimeEnvironment.getApplication() as Application

    @Test
    fun theOngoingWorkNotificationUsesTheRadioSmallIcon() {
        val notification = WidgetWorkNotification.foregroundInfo(context).notification
        assertEquals(context.resourceIdOf("drawable", "ic_notification_radio"), notification.icon)
    }
}
