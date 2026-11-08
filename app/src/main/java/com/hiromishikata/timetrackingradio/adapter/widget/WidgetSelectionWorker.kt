package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    override fun onStopped() {
        scope.cancel()
        controllerFactory(applicationContext).clearSending()
    }

    private suspend fun runSelection(): Result {
        val controller = controllerFactory(applicationContext)
        return try {
            val cell = requestedCell()
            if (cell == null) controller.refresh() else controller.performTap(cell)
            WidgetWorkScheduler.enqueueDiagnosticsUpload(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logWidgetWorkFailure(failure)
            controller.clearSending()
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

    companion object {
        const val UNIQUE_WORK_NAME = "widget-selection"
        const val KEY_PROJECT = "project"
        const val KEY_ACTIVITY = "activity"

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
