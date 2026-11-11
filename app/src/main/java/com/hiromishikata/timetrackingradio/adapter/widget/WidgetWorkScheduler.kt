package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.RetryKind
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

object WidgetWorkScheduler {
    const val DRAIN_UNIQUE_WORK_NAME = "widget-drain"
    const val DRAIN_RETRY_UNIQUE_WORK_NAME = "widget-drain-retry"

    const val TAP_RETRY_DELAY_SECONDS = 60L

    private const val WORK_INFO_TIMEOUT_SECONDS = 5L

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
            .setConstraints(connectedConstraints())
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

    fun enqueueAppStart(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetAppStartWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetAppStartWorker>().build(),
        )
    }

    fun kickPendingTaps(context: Context, attempt: Int = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DRAIN_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetSelectionWorker>()
                .setInputData(
                    workDataOf(
                        WidgetSelectionWorker.KEY_DRAIN to true,
                        WidgetSelectionWorker.KEY_ATTEMPT to attempt,
                    ),
                )
                .setConstraints(connectedConstraints())
                .build(),
        )
    }

    fun scheduleDrainRetry(context: Context, attempt: Int) {
        recordRetry(context, RetryKind.TAP_QUEUE, attempt)
        WorkManager.getInstance(context).enqueueUniqueWork(
            DRAIN_RETRY_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetSelectionWorker>()
                .setInputData(
                    workDataOf(
                        WidgetSelectionWorker.KEY_RELAY to true,
                        WidgetSelectionWorker.KEY_ATTEMPT to attempt,
                    ),
                )
                .setInitialDelay(TAP_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(connectedConstraints())
                .build(),
        )
    }

    fun scheduleLegacyTapRetry(context: Context, input: Data, attempt: Int) {
        recordRetry(context, RetryKind.LEGACY_TAP, attempt)
        val retryInput = Data.Builder().putAll(input).putInt(WidgetSelectionWorker.KEY_ATTEMPT, attempt).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WidgetSelectionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WidgetSelectionWorker>()
                .setInputData(retryInput)
                .setInitialDelay(TAP_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(connectedConstraints())
                .build(),
        )
    }

    fun releaseBackedOffTaps(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val waiting: Set<UUID> = workManager.getWorkInfosForUniqueWork(WidgetSelectionWorker.UNIQUE_WORK_NAME)
            .get(WORK_INFO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .map { it.id }
            .toSet()
        if (waiting.isEmpty()) return
        WidgetController(context).releaseWork(waiting).forEach { workManager.cancelWorkById(it) }
    }

    private fun recordRetry(context: Context, kind: RetryKind, attempt: Int) {
        WidgetController(context).recordRetryScheduled(kind, attempt, TAP_RETRY_DELAY_SECONDS)
    }

    private fun requestKindOf(request: SelectionRequest): String = when (request) {
        is SelectionRequest.Select -> WidgetSelectionWorker.REQUEST_KIND_SELECT
        SelectionRequest.Clear -> WidgetSelectionWorker.REQUEST_KIND_CLEAR
    }

    private fun connectedConstraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private inline fun <reified Worker : ListenableWorker> buildWorkRequest(input: Data, expedited: Boolean): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<Worker>().setInputData(input)
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return builder.build()
    }
}
