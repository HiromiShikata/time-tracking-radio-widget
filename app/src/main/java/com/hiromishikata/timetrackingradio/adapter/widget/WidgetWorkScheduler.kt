package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import java.time.Instant
import java.util.concurrent.TimeUnit

object WidgetWorkScheduler {
    private const val TAP_RETRY_INITIAL_BACKOFF_SECONDS = 10L

    const val DRAIN_UNIQUE_WORK_NAME = "widget-drain"
    const val DRAIN_RETRY_UNIQUE_WORK_NAME = "widget-drain-retry"

    fun kickPendingTaps(context: Context) = Unit

    fun buildTapRequest(cell: CellKey, tappedAt: Instant, request: SelectionRequest): OneTimeWorkRequest {
        require(request !is SelectionRequest.Select || request.cell == cell) { "a tap's selection request names another cell" }
        return OneTimeWorkRequestBuilder<WidgetSelectionWorker>()
            .setInputData(
                workDataOf(
                    WidgetSelectionWorker.KEY_PROJECT to cell.project,
                    WidgetSelectionWorker.KEY_ACTIVITY to cell.activity.code,
                    WidgetSelectionWorker.KEY_TAPPED_AT to tappedAt.toString(),
                    WidgetSelectionWorker.KEY_REQUEST_KIND to requestKindOf(request),
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, TAP_RETRY_INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun enqueueWorkRequest(context: Context, workRequest: OneTimeWorkRequest) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetSelectionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest,
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

    private fun requestKindOf(request: SelectionRequest): String = when (request) {
        is SelectionRequest.Select -> WidgetSelectionWorker.REQUEST_KIND_SELECT
        SelectionRequest.Clear -> WidgetSelectionWorker.REQUEST_KIND_CLEAR
    }

    private inline fun <reified Worker : ListenableWorker> buildWorkRequest(input: Data, expedited: Boolean): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<Worker>().setInputData(input)
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return builder.build()
    }
}
