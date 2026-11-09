package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
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
        inputTapOrNull()?.let { (cell, tappedAt) -> controllerFactory(applicationContext).markWaiting(cell, tappedAt) }
    }

    private suspend fun runSelection(): Result {
        val controller = controllerFactory(applicationContext)
        return try {
            val cell = requestedCell()
            if (cell == null) controller.refresh() else controller.performTap(cell, requestedTappedAt())
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
            inputTapOrNull()?.let { (cell, tappedAt) -> controller.discardTap(cell, tappedAt) }
            currentCoroutineContext().ensureActive()
            WidgetWorkScheduler.enqueueDiagnosticsUpload(applicationContext)
            Result.success()
        }
    }

    private fun requestedCell(): CellKey? {
        val project = inputData.getString(KEY_PROJECT)
        val activityCode = inputData.getString(KEY_ACTIVITY)
        if (project == null && activityCode == null) return null
        val activity = ActivityKind.fromCode(activityCode.orEmpty())
        return CellKey(
            requireNotNull(project) { "tap input has no project" },
            requireNotNull(activity) { "tap input has an unknown activity" },
        )
    }

    private fun requestedTappedAt(): Instant =
        Instant.parse(requireNotNull(inputData.getString(KEY_TAPPED_AT)) { "tap input has no tappedAt" })

    private fun inputCellOrNull(): CellKey? = try {
        requestedCell()
    } catch (invalid: IllegalArgumentException) {
        null
    }

    private fun inputTappedAtOrNull(): Instant? = try {
        requestedTappedAt()
    } catch (invalid: IllegalArgumentException) {
        null
    } catch (invalid: DateTimeException) {
        null
    }

    private fun inputTapOrNull(): Pair<CellKey, Instant>? {
        val cell = inputCellOrNull() ?: return null
        val tappedAt = inputTappedAtOrNull() ?: return null
        return cell to tappedAt
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget-selection"
        const val KEY_PROJECT = "project"
        const val KEY_ACTIVITY = "activity"
        const val KEY_TAPPED_AT = "tappedAt"

        var controllerFactory: (Context) -> WidgetController = { WidgetController(it) }
    }
}

private class CompletableListenableFuture<V>(private val delegate: CompletableFuture<V>) :
    ListenableFuture<V>,
    Future<V> by delegate {
    override fun addListener(listener: Runnable, executor: Executor) {
        delegate.whenCompleteAsync({ _, _ -> listener.run() }, executor)
    }
}
