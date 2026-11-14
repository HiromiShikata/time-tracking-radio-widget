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
import com.hiromishikata.timetrackingradio.domain.ChannelImportance
import com.hiromishikata.timetrackingradio.domain.InterruptionFilter
import com.hiromishikata.timetrackingradio.domain.NotificationConditions
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import java.io.File

class UpdateNotifier(private val context: Context) : UpdateAnnouncer {
    override fun notificationsPermitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun notificationConditions(): NotificationConditions = NotificationConditions(
        appNotificationsEnabled = readOrNull("app notification state") {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        },
        updateChannelImportance = readOrNull("update channel importance") {
            manager().getNotificationChannel(CHANNEL_ID)?.let { channelImportanceOf(it.importance) }
        } ?: ChannelImportance.UNKNOWN,
        interruptionFilter = readOrNull("interruption filter") {
            interruptionFilterOf(manager().currentInterruptionFilter)
        } ?: InterruptionFilter.UNKNOWN,
    )

    override fun isUpdateNotificationActive(): Boolean? = readOrNull("active notifications") {
        manager().activeNotifications.any { it.id == NOTIFICATION_ID && it.tag == null }
    }

    private fun manager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("the notification service is not available")

    private fun <T> readOrNull(what: String, read: () -> T?): T? = try {
        read()
    } catch (failure: Exception) {
        Log.w(TAG, "could not read the $what: ${failure.message}")
        null
    }

    override fun announce(version: SemanticVersion, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notifications are not permitted; update $version was not announced")
            return false
        }
        ensureChannel()
        val intent = Intent(context, UpdateInstallActivity::class.java)
            .putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apk.absolutePath)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_radio)
            .setContentTitle(context.getString(R.string.update_channel_name))
            .setContentText(context.getString(R.string.update_available, version.toString()))
            .setContentIntent(pending)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private companion object {
        const val TAG = "UpdateNotifier"
        const val LEGACY_CHANNEL_ID = "app-updates"
        const val CHANNEL_ID = "app-update-available"
        const val NOTIFICATION_ID = 1
        const val REQUEST_CODE = 1
    }
}
