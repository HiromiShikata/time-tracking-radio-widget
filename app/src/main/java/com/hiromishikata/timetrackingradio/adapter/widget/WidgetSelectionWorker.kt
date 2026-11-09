package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future

class WidgetSelectionWorker(context: Context, parameters: WorkerParameters) : ListenableWorker(context, parameters) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startWork(): ListenableFuture<Result> {
        val outcome = CompletableFuture<Result>()
        scope.launch { outcome.complete(runSelection()) }.invokeOnCompletion { outcome.cancel(false) }
        return CompletableListenableFuture(outcome)
    }

    override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> =
        CompletableListenableFuture(CompletableFuture.completedFuture(WidgetWorkNotification.foregroundInfo(applicationContext)))

    override fun onStopped() {
        scope.cancel()
        requestedStampedTapOrNull()?.let { tap -> controllerFactory(applicationContext).markWaiting(tap.cell, tap.tappedAt) }
    }

    private suspend fun runSelection(): Result {
        val controller = controllerFactory(applicationContext)
        return try {
            when (val work = requestedWork()) {
                RequestedWork.Refresh -> controller.refresh()
                is RequestedWork.StampedTap -> controller.performTap(work.cell, work.request, work.tappedAt)
                is RequestedWork.LegacyTap -> controller.performLegacyTap(work.cell)
            }
            currentCoroutineContext().ensureActive()
            WidgetWorkScheduler.enqueueDiagnosticsUpload(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: NetworkUnavailableException) {
            logWidgetWorkFailure(unavailable)
            Result.retry()
        } catch (failure: Exception) {
            logWidgetWorkFailure(failure)
            requestedStampedTapOrNull()?.let { tap -> controller.discardTap(tap.cell, tap.tappedAt) }
            currentCoroutineContext().ensureActive()
            WidgetWorkScheduler.enqueueDiagnosticsUpload(applicationContext)
            Result.success()
        }
    }

    private fun requestedWork(): RequestedWork {
        val project = inputData.getString(KEY_PROJECT)
        val activityCode = inputData.getString(KEY_ACTIVITY)
        val tappedAtText = inputData.getString(KEY_TAPPED_AT)
        if (project == null && activityCode == null && tappedAtText == null) return RequestedWork.Refresh
        val cell = CellKey(
            requireNotNull(project) { "tap input has no project" },
            requireNotNull(ActivityKind.fromCode(activityCode.orEmpty())) { "tap input has an unknown activity" },
        )
        if (tappedAtText == null) return RequestedWork.LegacyTap(cell)
        return RequestedWork.StampedTap(cell, requestedSelectionRequest(cell), Instant.parse(tappedAtText))
    }

    private fun requestedSelectionRequest(cell: CellKey): SelectionRequest = when (inputData.getString(KEY_REQUEST_KIND)) {
        REQUEST_KIND_SELECT -> SelectionRequest.Select(cell)
        REQUEST_KIND_CLEAR -> SelectionRequest.Clear
        else -> throw IllegalArgumentException("tap input has no known request kind")
    }

    private fun requestedStampedTapOrNull(): RequestedWork.StampedTap? = try {
        requestedWork() as? RequestedWork.StampedTap
    } catch (invalid: IllegalArgumentException) {
        null
    } catch (invalid: DateTimeException) {
        null
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget-selection"
        const val KEY_PROJECT = "project"
        const val KEY_ACTIVITY = "activity"
        const val KEY_TAPPED_AT = "tappedAt"
        const val KEY_REQUEST_KIND = "requestKind"
        const val REQUEST_KIND_SELECT = "select"
        const val REQUEST_KIND_CLEAR = "clear"

        var controllerFactory: (Context) -> WidgetController = { WidgetController(it) }
    }
}

private sealed interface RequestedWork {
    data object Refresh : RequestedWork

    data class StampedTap(val cell: CellKey, val request: SelectionRequest, val tappedAt: Instant) : RequestedWork

    data class LegacyTap(val cell: CellKey) : RequestedWork
}

private class CompletableListenableFuture<V>(private val delegate: CompletableFuture<V>) :
    ListenableFuture<V>,
    Future<V> by delegate {
    override fun addListener(listener: Runnable, executor: Executor) {
        delegate.whenCompleteAsync({ _, _ -> listener.run() }, executor)
    }
}
