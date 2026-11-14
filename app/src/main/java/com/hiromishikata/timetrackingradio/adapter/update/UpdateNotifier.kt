package com.hiromishikata.timetrackingradio.adapter.update

import android.Manifest
import android.app.Notification
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
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.AnnounceOutcome
import com.hiromishikata.timetrackingradio.domain.ChannelImportance
import com.hiromishikata.timetrackingradio.domain.InterruptionFilter
import com.hiromishikata.timetrackingradio.domain.NotificationConditions
import com.hiromishikata.timetrackingradio.domain.SemanticVersion
import com.hiromishikata.timetrackingradio.domain.StoredUpdateAlert
import com.hiromishikata.timetrackingradio.domain.UpdateAnnouncer
import com.hiromishikata.timetrackingradio.domain.decideUpdateAlert
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

internal const val LAST_ALERT_VERSION_KEY = "last_announced_update_version"
internal const val LAST_ALERT_TIME_KEY = "last_update_alert_at"

class UpdateNotifier(
    private val context: Context,
    private val clock: () -> Instant = { Instant.now() },
    private val postOverride: ((Int, Notification) -> Unit)? = null,
) : UpdateAnnouncer {
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

    override fun announce(version: SemanticVersion, apk: File): AnnounceOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "notifications are not permitted; update $version was not announced")
            return AnnounceOutcome.NOT_PERMITTED
        }
        val store = announcementStore()
        val now = clock()
        val outcome = decideUpdateAlert(version.toString(), readStoredAlert(store), isUpdateNotificationActive(), now)
        if (!outcome.posted) return outcome
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
            .setOnlyAlertOnce(outcome == AnnounceOutcome.SILENT_UPDATE)
            .setAutoCancel(true)
            .build()
        val override = postOverride
        if (override != null) {
            override(NOTIFICATION_ID, notification)
        } else {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        if (outcome == AnnounceOutcome.ALERTED) {
            store.write(LAST_ALERT_VERSION_KEY, version.toString())
            store.write(LAST_ALERT_TIME_KEY, now.toString())
        }
        return outcome
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

    private fun announcementStore(): SharedPreferencesKeyValueStore =
        SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))

    private fun readStoredAlert(store: SharedPreferencesKeyValueStore): StoredUpdateAlert? {
        val version = store.read(LAST_ALERT_VERSION_KEY) ?: return null
        val alertedAt = store.read(LAST_ALERT_TIME_KEY) ?: return null
        return try {
            StoredUpdateAlert(version, Instant.parse(alertedAt))
        } catch (unparseable: DateTimeParseException) {
            Log.w(TAG, "stored update alert time is not an instant; the update is alerted again")
            null
        }
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
