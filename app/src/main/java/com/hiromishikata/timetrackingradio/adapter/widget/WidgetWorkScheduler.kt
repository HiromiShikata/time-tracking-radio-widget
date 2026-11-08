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
        val request = networkRequest<WidgetSelectionWorker>(
            workDataOf(
                WidgetSelectionWorker.KEY_PROJECT to cell.project,
                WidgetSelectionWorker.KEY_ACTIVITY to cell.activity.code,
            ),
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
            networkRequest<WidgetSelectionWorker>(Data.EMPTY),
        )
    }

    fun enqueueDiagnosticsUpload(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            networkRequest<WidgetDiagnosticsUploadWorker>(Data.EMPTY),
        )
    }

    private inline fun <reified Worker : ListenableWorker> networkRequest(input: Data): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<Worker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(input)
            .build()
}
