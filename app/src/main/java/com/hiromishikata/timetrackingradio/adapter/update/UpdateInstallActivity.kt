package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.describeFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateInstallActivity : Activity() {
    private lateinit var scope: CoroutineScope
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_INSTALL_STATUS) {
            handleInstallStatus(intent)
            return
        }
        startInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_INSTALL_STATUS) handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    private fun startInstall() {
        val recorder = updateDiagnosticRecorder(this)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            Log.e(TAG, "install activity started without an APK path")
            recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=missing_apk, reason=no APK path was given")
            finish()
            return
        }
        val apk = File(apkPath)
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope.launch {
            when (decideInstallStart(installAllowed(this@UpdateInstallActivity), apk.isFile && apk.length() > 0)) {
                InstallStart.Commit -> {
                    val committed = withContext(ioDispatcher) { commitSession(apk, recorder) }
                    if (!committed) {
                        Toast.makeText(this@UpdateInstallActivity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                InstallStart.OpenUnknownAppSources -> {
                    openUnknownAppSourcesSettings(recorder)
                    finish()
                }
                InstallStart.MissingApk -> {
                    Log.e(TAG, "the downloaded APK is missing or empty")
                    recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=missing_apk, reason=APK file is missing or empty")
                    Toast.makeText(this@UpdateInstallActivity, R.string.update_apk_missing, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun commitSession(apk: File, recorder: DiagnosticRecorder): Boolean = try {
        commitApkSession(sessionApiFactory(applicationContext), apk, statusIntent())
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=session_committed")
        true
    } catch (failure: Exception) {
        val detail = describeFailure(failure)
        Log.e(TAG, "install session failed: $detail")
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=session_failed, reason=$detail")
        false
    }

    private fun statusIntent(): IntentSender {
        val intent = Intent(this, UpdateInstallActivity::class.java).setAction(ACTION_INSTALL_STATUS)
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(
            this,
            STATUS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        ).intentSender
    }

    private fun handleInstallStatus(statusIntent: Intent) {
        val recorder = updateDiagnosticRecorder(this)
        val status = statusIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS_EXTRA)
        when (val outcome = interpretInstallStatus(status, statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))) {
            InstallStatusOutcome.ConfirmationNeeded -> showInstallConfirmation(statusIntent, recorder)
            InstallStatusOutcome.Installed -> {
                recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=installed")
                finish()
            }
            is InstallStatusOutcome.Failed -> {
                recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=failed, reason=${outcome.message}")
                finish()
            }
        }
    }

    private fun showInstallConfirmation(statusIntent: Intent, recorder: DiagnosticRecorder) {
        val confirmation = confirmationIntent(statusIntent)
        if (confirmation == null) {
            recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=failed, reason=no confirmation intent")
            finish()
            return
        }
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resumed) {
            startActivity(confirmation)
            recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=pending_user_action, delivery=activity")
        } else {
            if (postInstallConfirmationNotification(confirmation)) {
                recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=pending_user_action, delivery=notification")
            } else {
                recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=failed, reason=notification permission missing")
            }
            finish()
        }
    }

    private fun postInstallConfirmationNotification(confirmation: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                INSTALL_CHANNEL_ID,
                getString(R.string.update_install_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val pending = PendingIntent.getActivity(
            this,
            CONFIRMATION_REQUEST_CODE,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, INSTALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.update_install_confirmation_title))
            .setContentText(getString(R.string.update_install_confirmation_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(null, INSTALL_CONFIRMATION_NOTIFICATION_ID, notification)
        return true
    }

    private fun confirmationIntent(statusIntent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            statusIntent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun openUnknownAppSourcesSettings(recorder: DiagnosticRecorder) {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=blocked_unknown_sources")
    }

    companion object {
        const val EXTRA_APK_PATH = "com.hiromishikata.timetrackingradio.extra.APK_PATH"
        const val ACTION_INSTALL_STATUS = "com.hiromishikata.timetrackingradio.APK_INSTALL_STATUS"
        const val INSTALL_CONFIRMATION_NOTIFICATION_ID = 2
        private const val TAG = "UpdateInstallActivity"
        private const val INSTALL_CHANNEL_ID = "app-update-install"
        private const val NO_STATUS_EXTRA = Int.MIN_VALUE
        private const val STATUS_REQUEST_CODE = 2
        private const val CONFIRMATION_REQUEST_CODE = 3

        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main
        internal var sessionApiFactory: (Context) -> InstallSessionApi = { PackageInstallerSessionApi(it) }
        internal var installAllowed: (Context) -> Boolean = { it.packageManager.canRequestPackageInstalls() }
    }
}
