package com.hiromishikata.timetrackingradio.adapter.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            when (decideInstallStart(packageManager.canRequestPackageInstalls(), apk.isFile && apk.length() > 0)) {
                InstallStart.Commit -> {
                    val committed = withContext(ioDispatcher) { commitSession(apk, recorder) }
                    if (!committed) Toast.makeText(this@UpdateInstallActivity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                }
                InstallStart.OpenUnknownAppSources -> openUnknownAppSourcesSettings(recorder)
                InstallStart.MissingApk -> {
                    Log.e(TAG, "the downloaded APK is missing or empty")
                    recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=missing_apk, reason=APK file is missing or empty")
                    Toast.makeText(this@UpdateInstallActivity, R.string.update_apk_missing, Toast.LENGTH_LONG).show()
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        if (::scope.isInitialized) scope.cancel()
        super.onDestroy()
    }

    private fun commitSession(apk: File, recorder: DiagnosticRecorder): Boolean = try {
        commitApkSession(PackageInstallerSessionApi(applicationContext), apk, statusIntent())
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=session_committed")
        true
    } catch (failure: Exception) {
        val detail = describeFailure(failure)
        Log.e(TAG, "install session failed: $detail")
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=session_failed, reason=$detail")
        false
    }

    private fun statusIntent(): IntentSender {
        val intent = Intent(this, UpdateInstallStatusReceiver::class.java).setAction(UpdateInstallStatusReceiver.ACTION)
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            this,
            STATUS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability,
        ).intentSender
    }

    private fun openUnknownAppSourcesSettings(recorder: DiagnosticRecorder) {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, "outcome=blocked_unknown_sources")
    }

    companion object {
        const val EXTRA_APK_PATH = "com.hiromishikata.timetrackingradio.extra.APK_PATH"
        private const val TAG = "UpdateInstallActivity"
        private const val STATUS_REQUEST_CODE = 2

        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    }
}
