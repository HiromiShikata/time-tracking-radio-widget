package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import kotlinx.coroutines.launch

class SelectionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val controller = WidgetController(context)
        controller.render()
        launchAsync(this) { controller.refresh() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CELL_TAP) {
            super.onReceive(context, intent)
            return
        }
        val project = intent.getStringExtra(EXTRA_PROJECT)
        val activity = ActivityKind.fromCode(intent.getStringExtra(EXTRA_ACTIVITY).orEmpty())
        if (project == null || activity == null) {
            Log.e(TAG, "tap intent has no valid cell")
            return
        }
        val controller = WidgetController(context)
        launchAsync(this) { controller.tap(CellKey(project, activity)) }
    }

    companion object {
        const val ACTION_CELL_TAP = "com.hiromishikata.timetrackingradio.action.CELL_TAP"
        const val EXTRA_PROJECT = "com.hiromishikata.timetrackingradio.extra.PROJECT"
        const val EXTRA_ACTIVITY = "com.hiromishikata.timetrackingradio.extra.ACTIVITY"
        private const val TAG = "SelectionWidget"

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

private fun launchAsync(receiver: BroadcastReceiver, block: suspend () -> Unit) {
    val pending = receiver.goAsync()
    WidgetScope.scope.launch {
        try {
            block()
        } catch (failure: Exception) {
            Log.e("SelectionWidget", "widget work failed", failure)
        } finally {
            pending.finish()
        }
    }
}
