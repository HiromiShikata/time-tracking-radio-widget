package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WidgetAppStartWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        const val UNIQUE_WORK_NAME = "widget-app-start"
    }
}
