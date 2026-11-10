package com.hiromishikata.timetrackingradio.adapter.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recorder = updateDiagnosticRecorder(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS_EXTRA)
        when (val outcome = interpretInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))) {
            InstallStatusOutcome.ConfirmationNeeded -> startConfirmation(context, intent, recorder)
            InstallStatusOutcome.Installed -> record(recorder, "outcome=installed")
            is InstallStatusOutcome.Failed -> record(recorder, "outcome=failed, reason=${outcome.message}")
        }
    }

    private fun startConfirmation(context: Context, intent: Intent, recorder: DiagnosticRecorder) {
        val confirmation = confirmationIntent(intent)
        if (confirmation == null) {
            record(recorder, "outcome=failed, reason=the install confirmation intent is missing")
            return
        }
        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        record(recorder, "outcome=pending_user_action")
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun record(recorder: DiagnosticRecorder, outcome: String) {
        recorder.record(DiagnosticEventType.UPDATE_INSTALL, outcome)
    }

    companion object {
        const val ACTION = "com.hiromishikata.timetrackingradio.APK_INSTALL_STATUS"
        private const val NO_STATUS_EXTRA = Int.MIN_VALUE
    }
}
