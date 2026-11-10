package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueLastUpdateCheckRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.LastUpdateCheckRepository
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

object UpdateCheckScheduler {
    private const val UNIQUE_WORK_NAME = "app-update-check"
    private const val ON_DEMAND_WORK_NAME = "app-update-check-once"
    private const val INTERVAL_HOURS = 6L
    private const val FOREGROUND_WORK_NAME = "app-update-check-foreground"
    private const val FOREGROUND_CHECK_KEY = "last_foreground_update_check_at"
    private const val TAG = "UpdateCheckScheduler"
    private val foregroundThrottle: Duration = Duration.ofSeconds(60)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(connectedConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun lastCheckRepository(context: Context): LastUpdateCheckRepository = KeyValueLastUpdateCheckRepository(
        SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)),
    )

    fun enqueueOnDemandIfStale(context: Context, now: Instant) {
        val lastChecked = lastCheckRepository(context).read()
        if (lastChecked != null && Duration.between(lastChecked, now) <= Duration.ofHours(INTERVAL_HOURS)) return
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(connectedConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ON_DEMAND_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueForegroundCheckIfDue(context: Context, now: Instant) {
        val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
        val lastChecked = store.read(FOREGROUND_CHECK_KEY)?.let(::storedInstant)
        if (lastChecked != null && Duration.between(lastChecked, now) < foregroundThrottle) return
        store.write(FOREGROUND_CHECK_KEY, now.toString())
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(connectedConstraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FOREGROUND_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun storedInstant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (unparseable: DateTimeParseException) {
        Log.w(TAG, "stored foreground check time is not an instant; the check is due")
        null
    }

    private fun connectedConstraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
