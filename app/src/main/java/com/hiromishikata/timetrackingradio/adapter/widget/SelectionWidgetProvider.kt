package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.describeFailure

class SelectionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        controllerFactory(context).render()
        WidgetWorkScheduler.enqueueRefresh(context)
        WidgetWorkScheduler.kickPendingTaps(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CELL_TAP) {
            super.onReceive(context, intent)
            return
        }
        val project = intent.getStringExtra(EXTRA_PROJECT)
        val activity = ActivityKind.fromCode(intent.getStringExtra(EXTRA_ACTIVITY).orEmpty())
        if (project == null || activity == null) {
            Log.e(LOG_TAG, "tap intent has no valid cell")
            return
        }
        val cell = CellKey(project, activity)
        val controller = controllerFactory(context)
        val queued = controller.acceptQueuedTap(cell) { tappedAt, request ->
            WidgetWorkScheduler.buildTapRequest(cell, tappedAt, request)
        }
        try {
            WidgetWorkScheduler.enqueueWorkRequest(context, queued.workRequest)
        } catch (failure: Exception) {
            controller.discardTap(cell, queued.accepted.tappedAt)
            throw failure
        }
        WidgetWorkScheduler.kickPendingTaps(context)
    }

    companion object {
        const val ACTION_CELL_TAP = "com.hiromishikata.timetrackingradio.action.CELL_TAP"
        const val EXTRA_PROJECT = "com.hiromishikata.timetrackingradio.extra.PROJECT"
        const val EXTRA_ACTIVITY = "com.hiromishikata.timetrackingradio.extra.ACTIVITY"

        var controllerFactory: (Context) -> WidgetController = { WidgetController(it) }

        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SelectionWidgetProvider::class.java))
            context.sendBroadcast(
                Intent(context, SelectionWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}

private const val LOG_TAG = "SelectionWidget"

/** Logs the class and the redacted message only. The throwable itself is never logged: its message can carry the endpoint key. */
internal fun logWidgetWorkFailure(failure: Throwable) {
    Log.e(LOG_TAG, "widget work failed: ${describeFailure(failure)}")
}
