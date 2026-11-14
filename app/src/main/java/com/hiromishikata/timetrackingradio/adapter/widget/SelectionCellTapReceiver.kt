package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey

class SelectionCellTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val project = intent.getStringExtra(SelectionWidgetProvider.EXTRA_PROJECT)
        val activity = ActivityKind.fromCode(intent.getStringExtra(SelectionWidgetProvider.EXTRA_ACTIVITY).orEmpty())
        if (project == null || activity == null) {
            Log.e(LOG_TAG, "tap intent has no valid cell")
            return
        }
        val cell = CellKey(project, activity)
        val controller = SelectionWidgetProvider.controllerFactory(context)
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
}

private const val LOG_TAG = "SelectionWidget"
