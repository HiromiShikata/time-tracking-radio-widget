package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContextWrapper
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.resourceIdOf
import com.hiromishikata.timetrackingradio.domain.AnnounceOutcome
import com.hiromishikata.timetrackingradio.domain.ChannelImportance
import com.hiromishikata.timetrackingradio.domain.InterruptionFilter
import com.hiromishikata.timetrackingradio.domain.NotificationConditions
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.StoredUpdateAlert
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import android.content.Context
import java.time.Duration
import java.time.Instant
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertNotNull(manager.getNotificationChannel("app-update-available"))
    }

    @Test
    fun theNotificationUsesTheRadioSmallIcon() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(AnnounceOutcome.ALERTED, UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(context.resourceIdOf("drawable", "ic_notification_radio"), notification.icon)
    }

    @Test
    fun theAlertOnlyOnceFlagIsSetOnlyForARepostOfTheLastAnnouncedVersion() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        data class Case(
            val name: String,
            val earlierPosts: List<SemanticVersion>,
            val version: SemanticVersion,
            val expectedAlertOnce: Boolean,
        )
        val older = SemanticVersion(1, 0, 2)
        val newer = SemanticVersion(1, 0, 3)
        val cases = listOf(
            Case("first post for a version alerts", emptyList(), older, false),
            Case("re-post of the same version is silent", listOf(older), older, true),
            Case("newer version while the older one shows alerts", listOf(older), newer, false),
        )
        cases.forEach { case ->
            manager.cancelAll()
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
            val notifier = UpdateNotifier(context)
            case.earlierPosts.forEach { assertTrue(case.name, notifier.announce(it, apk).posted) }
            assertTrue(case.name, notifier.announce(case.version, apk).posted)
            val notification = shadowOf(manager).allNotifications.single()
            assertEquals(
                case.name,
                case.expectedAlertOnce,
                (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
            )
        }
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
            val expectedAnnounced: AnnounceOutcome,
            val expectedPosted: Int,
        )
        val cases = listOf(
            Case("permission granted", true, true, AnnounceOutcome.ALERTED, 1),
            Case("permission denied", false, false, AnnounceOutcome.NOT_PERMITTED, 0),
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
        assertEquals(AnnounceOutcome.ALERTED, UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk))
        val notification = shadowOf(manager).allNotifications.single()
        val started = shadowOf(notification.contentIntent).savedIntent
        assertEquals(UpdateInstallActivity::class.java.name, started.component!!.className)
        assertEquals(apk.absolutePath, started.getStringExtra(UpdateInstallActivity.EXTRA_APK_PATH))
    }

    @Test
    fun theUpdateChannelIsRenamedAndTheOldOneIsDeleted() {
        manager.createNotificationChannel(
            NotificationChannel("app-updates", context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(AnnounceOutcome.ALERTED, UpdateNotifier(context).announce(SemanticVersion(1, 0, 2), apk))
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel("app-update-available")?.importance,
        )
        assertNull(manager.getNotificationChannel("app-updates"))
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
            val expectedAnnounced: AnnounceOutcome,
            val expectedPosted: Int,
        )
        val cases = listOf(
            Case("no runtime permission is needed below Android 13", true, AnnounceOutcome.ALERTED, 1),
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateNotifierConditionsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val apk = File("updates/app-update.apk")

    @Test
    fun readsTheAppEnabledStateAndTheInterruptionFilterByName() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val interruptionFilter: Int,
            val expected: NotificationConditions,
        )
        val cases = listOf(
            Case(
                "everything visible",
                enabled = true,
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                expected = NotificationConditions(true, ChannelImportance.UNKNOWN, InterruptionFilter.ALL),
            ),
            Case(
                "do not disturb with priority only",
                enabled = true,
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                expected = NotificationConditions(true, ChannelImportance.UNKNOWN, InterruptionFilter.PRIORITY),
            ),
            Case(
                "app notifications blocked and total silence",
                enabled = false,
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE,
                expected = NotificationConditions(false, ChannelImportance.UNKNOWN, InterruptionFilter.NONE),
            ),
        )
        cases.forEach { case ->
            shadowOf(manager).setNotificationsEnabled(case.enabled)
            shadowOf(manager).setNotificationPolicyAccessGranted(true)
            manager.setInterruptionFilter(case.interruptionFilter)
            assertEquals(case.name, case.expected, UpdateNotifier(context).notificationConditions())
        }
    }

    @Test
    fun readsTheUpdateChannelImportanceWhenTheChannelExists() {
        manager.createNotificationChannel(NotificationChannel("app-update-available", "updates", NotificationManager.IMPORTANCE_HIGH))
        assertEquals(ChannelImportance.HIGH, UpdateNotifier(context).notificationConditions().updateChannelImportance)
    }

    @Test
    fun readsABlockedUpdateChannelAsNone() {
        manager.createNotificationChannel(NotificationChannel("app-update-available", "updates", NotificationManager.IMPORTANCE_NONE))
        assertEquals(ChannelImportance.NONE, UpdateNotifier(context).notificationConditions().updateChannelImportance)
    }

    @Test
    fun reportsWhetherTheUpdateNotificationIsActiveAfterPosting() {
        manager.cancelAll()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = UpdateNotifier(context)
        assertEquals(false, notifier.isUpdateNotificationActive())
        assertEquals(AnnounceOutcome.ALERTED, notifier.announce(SemanticVersion(1, 0, 2), apk))
        assertEquals(true, notifier.isUpdateNotificationActive())
        manager.cancelAll()
        assertEquals(false, notifier.isUpdateNotificationActive())
    }

    @Test
    fun anUnreadableNotificationServiceIsReportedAsUnknownAndNeverThrows() {
        val noServices = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = null
        }
        val notifier = UpdateNotifier(noServices)
        assertEquals(
            NotificationConditions(null, ChannelImportance.UNKNOWN, InterruptionFilter.UNKNOWN),
            notifier.notificationConditions(),
        )
        assertEquals(null, notifier.isUpdateNotificationActive())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateNotifierReminderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val apk = File("updates/app-update.apk")
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val announced = SemanticVersion(1, 0, 2)

    private fun store(): SharedPreferencesKeyValueStore =
        SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))

    private fun resetState(stored: StoredUpdateAlert?) {
        manager.cancelAll()
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        stored?.let {
            store().write(LAST_ALERT_VERSION_KEY, it.version)
            store().write(LAST_ALERT_TIME_KEY, it.alertedAt.toString())
        }
    }

    private fun showUpdateNotificationAlready() {
        manager.createNotificationChannel(
            NotificationChannel("app-update-available", "updates", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.notify(1, Notification.Builder(context, "app-update-available").setSmallIcon(android.R.drawable.ic_dialog_info).build())
    }

    @Test
    fun theAnnouncementFollowsTheStoredAlertAndTheNotificationState() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        data class Case(
            val name: String,
            val stored: StoredUpdateAlert?,
            val notificationShowing: Boolean,
            val expectedOutcome: AnnounceOutcome,
            val expectedAlertOnlyOnce: Boolean,
            val expectedAlertedAt: Instant,
        )
        val alertedSevenHoursAgo = now.minus(Duration.ofHours(7))
        val alertedTwoHoursAgo = now.minus(Duration.ofHours(2))
        val cases = listOf(
            Case(
                "new version alerts",
                StoredUpdateAlert("1.0.1", alertedTwoHoursAgo),
                false,
                AnnounceOutcome.ALERTED,
                false,
                now,
            ),
            Case(
                "same version still showing re-posts silently and keeps the alert time",
                StoredUpdateAlert("1.0.2", alertedSevenHoursAgo),
                true,
                AnnounceOutcome.SILENT_UPDATE,
                true,
                alertedSevenHoursAgo,
            ),
            Case(
                "same version dismissed under six hours ago is not posted",
                StoredUpdateAlert("1.0.2", alertedTwoHoursAgo),
                false,
                AnnounceOutcome.SKIPPED_RECENTLY_DISMISSED,
                false,
                alertedTwoHoursAgo,
            ),
            Case(
                "same version dismissed six hours ago alerts again",
                StoredUpdateAlert("1.0.2", now.minus(Duration.ofHours(6))),
                false,
                AnnounceOutcome.ALERTED,
                false,
                now,
            ),
        )
        cases.forEach { case ->
            resetState(case.stored)
            if (case.notificationShowing) showUpdateNotificationAlready()
            val outcome = UpdateNotifier(context, clock = { now }).announce(announced, apk)
            assertEquals(case.name, case.expectedOutcome, outcome)
            val posted = shadowOf(manager).allNotifications
            if (outcome.posted) {
                assertEquals(case.name, 1, posted.size)
                assertEquals(
                    case.name,
                    case.expectedAlertOnlyOnce,
                    (posted.single().flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0,
                )
            } else {
                assertTrue(case.name, posted.isEmpty())
            }
            assertEquals(case.name, "1.0.2", store().read(LAST_ALERT_VERSION_KEY))
            assertEquals(case.name, case.expectedAlertedAt.toString(), store().read(LAST_ALERT_TIME_KEY))
        }
    }

    @Test
    fun aFailedPostLeavesTheStoredAlertUnchanged() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alertedTwoHoursAgo = now.minus(Duration.ofHours(2))
        resetState(StoredUpdateAlert("1.0.1", alertedTwoHoursAgo))
        val failing = UpdateNotifier(context, clock = { now }, postOverride = { _, _ -> throw IllegalStateException("post failed") })
        val failure = try {
            failing.announce(announced, apk)
            fail("the failed post must surface")
            null
        } catch (thrown: IllegalStateException) {
            thrown
        }
        assertEquals("post failed", failure?.message)
        assertEquals("1.0.1", store().read(LAST_ALERT_VERSION_KEY))
        assertEquals(alertedTwoHoursAgo.toString(), store().read(LAST_ALERT_TIME_KEY))
    }
}
