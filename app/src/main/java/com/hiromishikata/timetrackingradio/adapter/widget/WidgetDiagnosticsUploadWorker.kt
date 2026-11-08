package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetDiagnosticsUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = controllerFactory(applicationContext)
        return try {
            withContext(Dispatchers.IO) { controller.uploadDiagnostics() }
            Result.success()
        } catch (failure: Exception) {
            logWidgetWorkFailure(failure)
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget-diagnostics-upload"

        var controllerFactory: (Context) -> WidgetController = { WidgetController(it) }
    }
}
