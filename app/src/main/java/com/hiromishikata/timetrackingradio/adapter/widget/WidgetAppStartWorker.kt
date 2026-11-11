package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetAppStartWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            WidgetWorkScheduler.releaseBackedOffTaps(applicationContext)
            WidgetWorkScheduler.kickPendingTaps(applicationContext)
            Result.success()
        } catch (failure: Exception) {
            logWidgetWorkFailure(failure)
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget-app-start"
    }
}
