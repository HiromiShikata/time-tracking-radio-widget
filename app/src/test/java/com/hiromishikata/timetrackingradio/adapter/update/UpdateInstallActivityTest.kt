package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.provider.Settings
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdateInstallActivityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val application = RuntimeEnvironment.getApplication() as Application
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val payload = byteArrayOf(1, 2, 3, 4)
    private val confirmation = Intent("com.hiromishikata.timetrackingradio.test.CONFIRM_INSTALL")
    private val apkFile by lazy { File(folder.root, "app-update-1.0.2.apk") }
    private var api = RecordingSessionApi()

    private class RecordingSessionApi(private val writeFailure: RuntimeException? = null) : InstallSessionApi {
        val calls = mutableListOf<String>()
        var committedStatus: IntentSender? = null

        override fun createSession(): Int {
            calls += "createSession"
            return SESSION_ID
        }

        override fun writeApk(sessionId: Int, name: String, apk: File) {
            calls += "writeApk"
            writeFailure?.let { throw it }
        }

        override fun commit(sessionId: Int, statusIntent: IntentSender) {
            calls += "commit"
            committedStatus = statusIntent
        }

        override fun abandon(sessionId: Int) {
            calls += "abandon"
        }

        private companion object {
            const val SESSION_ID = 42
        }
    }

    @Before
    fun useFakesAndInlineDispatchers() {
        UpdateInstallActivity.ioDispatcher = Dispatchers.Unconfined
        UpdateInstallActivity.mainDispatcher = Dispatchers.Unconfined
        UpdateInstallActivity.sessionApiFactory = { api }
        UpdateInstallActivity.installAllowed = { true }
    }

    @After
    fun tearDown() {
        UpdateInstallActivity.ioDispatcher = Dispatchers.IO
        UpdateInstallActivity.mainDispatcher = Dispatchers.Main
        UpdateInstallActivity.sessionApiFactory = { PackageInstallerSessionApi(it) }
        UpdateInstallActivity.installAllowed = { context -> context.packageManager.canRequestPackageInstalls() }
    }

    private fun intentFor(apkPath: String?): Intent = Intent().apply {
        apkPath?.let { putExtra(UpdateInstallActivity.EXTRA_APK_PATH, it) }
    }

    private fun launch(apkPath: String?) = Robolectric.buildActivity(
        UpdateInstallActivity::class.java,
        intentFor(apkPath),
    ).create().get()

    private fun launchWithApk(): ActivityController<UpdateInstallActivity> {
        apkFile.writeBytes(payload)
        return Robolectric.buildActivity(UpdateInstallActivity::class.java, intentFor(apkFile.absolutePath)).setup()
    }

    private fun statusIntent(status: Int, message: String? = null): Intent = Intent(application, UpdateInstallActivity::class.java)
        .setAction(UpdateInstallActivity.ACTION_INSTALL_STATUS)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(Intent.EXTRA_INTENT, confirmation)
        .apply { message?.let { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, it) } }

    private fun installDetails(): List<String> = KeyValueDiagnosticLogRepository(
        SharedPreferencesKeyValueStore(application.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)),
    ).readAll().filter { it.type == DiagnosticEventType.UPDATE_INSTALL }.map { it.detail }

    private fun assertRecorded(vararg parts: String) {
        val details = installDetails()
        assertTrue(
            "expected an update_install record containing $parts, got $details",
            details.any { detail -> parts.all { detail.contains(it) } },
        )
    }

    @Test
    fun finishesWithoutInstallingWhenNoApkPathIsGiven() {
        val activity = launch(null)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun aCommitRoutesThroughTheSessionApiAndWaitsForTheStatus() {
        val controller = launchWithApk()
        assertEquals(listOf("createSession", "writeApk", "commit"), api.calls)
        assertNotNull(api.committedStatus)
        assertFalse(controller.get().isFinishing)
        assertRecorded("outcome=session_committed")
    }

    @Test
    fun aCommitFailureRecordsSessionFailedAndShowsAToast() {
        api = RecordingSessionApi(IllegalStateException("disk full"))
        val controller = launchWithApk()
        assertTrue(api.calls.contains("abandon"))
        assertRecorded("outcome=session_failed", "reason=", "disk full")
        assertEquals(application.getString(R.string.update_install_failed), ShadowToast.getTextOfLatestToast())
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aBlockedInstallOpensTheUnknownSourcesSettings() {
        UpdateInstallActivity.installAllowed = { false }
        val controller = launchWithApk()
        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals("package:${application.packageName}", started.data.toString())
        assertEquals(emptyList<String>(), api.calls)
        assertRecorded("outcome=blocked_unknown_sources")
    }

    @Test
    fun aMissingApkRecordsMissingApkAndFinishes() {
        val missing = File(folder.root, "absent.apk").absolutePath
        val controller = Robolectric.buildActivity(UpdateInstallActivity::class.java, intentFor(missing)).setup()
        assertEquals(emptyList<String>(), api.calls)
        assertTrue(controller.get().isFinishing)
        assertRecorded("outcome=missing_apk")
    }

    @Test
    fun aMissingPathRecordsMissingApk() {
        launch(null)
        assertRecorded("outcome=missing_apk")
    }

    @Test
    fun aPendingStatusWhileResumedStartsTheConfirmationInsideTheActivity() {
        val controller = launchWithApk()
        controller.newIntent(statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION))
        val started = shadowOf(controller.get()).nextStartedActivity
        assertTrue(started.filterEquals(confirmation))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertRecorded("outcome=pending_user_action", "delivery=activity")
    }

    @Test
    fun aConfirmationWithNoActivityToHandleItIsRecordedAndDoesNotCrash() {
        val unresolvable = Intent("com.example.NO_SUCH_ACTIVITY")
        val controller = launchWithApk()
        val status = Intent(application, UpdateInstallActivity::class.java)
            .setAction(UpdateInstallActivity.ACTION_INSTALL_STATUS)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, unresolvable)
        controller.newIntent(status)
        assertRecorded("outcome=failed", "reason=", "no activity")
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aPendingStatusWhileInTheBackgroundPostsTheConfirmationNotification() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = launchWithApk().pause().stop()
        controller.newIntent(statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertNotNull(manager.getNotificationChannel("app-update-install"))
        val notification = shadowOf(manager).getNotification(null, UpdateInstallActivity.INSTALL_CONFIRMATION_NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals("app-update-install", notification.channelId)
        assertTrue(shadowOf(notification.contentIntent).savedIntent.filterEquals(confirmation))
        assertRecorded("outcome=pending_user_action", "delivery=notification")
    }

    @Test
    fun aPendingStatusWhileInTheBackgroundWithoutNotificationPermissionPostsNothingAndRecordsIt() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = launchWithApk().pause().stop()
        controller.newIntent(statusIntent(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertNull(shadowOf(manager).getNotification(null, UpdateInstallActivity.INSTALL_CONFIRMATION_NOTIFICATION_ID))
        assertRecorded("outcome=failed", "reason=notification permission missing")
    }

    @Test
    fun aSuccessStatusRecordsInstalledAndFinishes() {
        val controller = launchWithApk()
        controller.newIntent(statusIntent(PackageInstaller.STATUS_SUCCESS))
        assertTrue(controller.get().isFinishing)
        assertRecorded("outcome=installed")
    }

    @Test
    fun aFailureStatusRecordsFailedWithItsMessageAndFinishes() {
        val controller = launchWithApk()
        controller.newIntent(statusIntent(PackageInstaller.STATUS_FAILURE, "Package conflicts"))
        assertTrue(controller.get().isFinishing)
        assertRecorded("outcome=failed", "reason=Package conflicts")
    }
}
