package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.domain.CellKey

object WidgetWorkScheduler {
    fun enqueueTap(context: Context, cell: CellKey) {
        val request = buildWorkRequest<WidgetSelectionWorker>(
            workDataOf(
                WidgetSelectionWorker.KEY_PROJECT to cell.project,
                WidgetSelectionWorker.KEY_ACTIVITY to cell.activity.code,
            ),
            expedited = true,
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetSelectionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun enqueueRefresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetSelectionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildWorkRequest<WidgetSelectionWorker>(Data.EMPTY, expedited = true),
        )
    }

    fun enqueueDiagnosticsUpload(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildWorkRequest<WidgetDiagnosticsUploadWorker>(Data.EMPTY, expedited = false),
        )
    }

    private inline fun <reified Worker : ListenableWorker> buildWorkRequest(input: Data, expedited: Boolean): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<Worker>().setInputData(input)
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return builder.build()
    }
}
