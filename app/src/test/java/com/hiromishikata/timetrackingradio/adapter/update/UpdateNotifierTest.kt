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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val apk = File("updates/app-update.apk")

    @Test
    fun postsTheAnnouncementWhenNotificationsAreGranted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk)
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
        UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class UpdateNotifierAndroid13Test {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val apk = File("updates/app-update.apk")

    @Test
    fun theNotifierReportsPermissionAndPostingOnEachAndroidVersion() {
        data class Case(
            val name: String,
            val granted: Boolean,
            val expectedPermitted: Boolean,
            val expectedAnnounced: Boolean,
            val expectedPosted: Int,
        )
        val cases = listOf(
            Case("permission granted", true, true, true, 1),
            Case("permission denied", false, false, false, 0),
        )
        cases.forEach { case ->
            manager.cancelAll()
            val application = context as Application
            if (case.granted) {
                shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            }
            val notifier = UpdateNotifier(context)
            assertEquals(case.name, case.expectedPermitted, notifier.notificationsPermitted())
            assertEquals(case.name, case.expectedAnnounced, notifier.announce(SemanticVersion(1, 0, 2), apk))
            assertEquals(case.name, case.expectedPosted, shadowOf(manager).allNotifications.size)
        }
    }

    @Test
    fun theNotificationCarriesTheDownloadedApkPath() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk))
        val notification = shadowOf(manager).allNotifications.single()
        val started = shadowOf(notification.contentIntent).savedIntent
        assertEquals(UpdateInstallActivity::class.java.name, started.component!!.className)
        assertEquals(apk.absolutePath, started.getStringExtra(UpdateInstallActivity.EXTRA_APK_PATH))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = TestApplication::class)
class UpdateNotifierBelowAndroid13Test {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val apk = File("updates/app-update.apk")

    @Test
    fun theNotifierReportsPermissionAndPostingOnEachAndroidVersion() {
        data class Case(
            val name: String,
            val expectedPermitted: Boolean,
            val expectedAnnounced: Boolean,
            val expectedPosted: Int,
        )
        val cases = listOf(
            Case("no runtime permission is needed below Android 13", true, true, 1),
        )
        cases.forEach { case ->
            manager.cancelAll()
            val notifier = UpdateNotifier(context)
            assertEquals(case.name, case.expectedPermitted, notifier.notificationsPermitted())
            assertEquals(case.name, case.expectedAnnounced, notifier.announce(SemanticVersion(1, 0, 2), apk))
            assertEquals(case.name, case.expectedPosted, shadowOf(manager).allNotifications.size)
        }
    }
}
