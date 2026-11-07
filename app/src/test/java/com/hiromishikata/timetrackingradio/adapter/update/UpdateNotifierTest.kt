package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun postsTheAnnouncementWhenNotificationsAreGranted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), "https://example.com/app.apk")
        val notifications = shadowOf(manager).allNotifications
        assertEquals(1, notifications.size)
        assertEquals(
            "Version 1.0.2 is available. Tap to install.",
            notifications[0].extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertNotNull(manager.getNotificationChannel("app-updates"))
    }

    @Test
    fun skipsTheAnnouncementWhenNotificationsAreDenied() {
        UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), "https://example.com/app.apk")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
