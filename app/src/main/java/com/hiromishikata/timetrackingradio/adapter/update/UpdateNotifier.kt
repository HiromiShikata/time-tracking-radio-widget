package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.SemanticVersion

class UpdateNotifier(private val context: Context) {
    fun notifyAvailable(version: SemanticVersion, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notifications are not permitted; update $version was not announced")
            return
        }
        ensureChannel()
        val intent = Intent(context, UpdateInstallActivity::class.java)
            .putExtra(UpdateInstallActivity.EXTRA_APK_URL, apkUrl)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_channel_name))
            .setContentText(context.getString(R.string.update_available, version.toString()))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private companion object {
        const val TAG = "UpdateNotifier"
        const val CHANNEL_ID = "app-updates"
        const val NOTIFICATION_ID = 1
        const val REQUEST_CODE = 1
    }
}
