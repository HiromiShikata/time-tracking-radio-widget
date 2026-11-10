package com.hiromishikata.timetrackingradio.adapter.update

import android.content.Context
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
import java.util.concurrent.TimeUnit

object UpdateCheckScheduler {
    private const val UNIQUE_WORK_NAME = "app-update-check"
    private const val ON_DEMAND_WORK_NAME = "app-update-check-once"
    private const val INTERVAL_HOURS = 6L

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

    private fun connectedConstraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
