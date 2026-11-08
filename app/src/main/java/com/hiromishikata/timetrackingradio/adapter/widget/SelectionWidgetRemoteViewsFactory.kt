package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.CellVisual
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.WidgetStatus
import com.hiromishikata.timetrackingradio.domain.formatUtcHourMinute
import com.hiromishikata.timetrackingradio.domain.resolveCellVisual
import com.hiromishikata.timetrackingradio.domain.resolveWidgetStatus
import java.time.Instant

class SelectionWidgetRemoteViewsFactory(private val context: Context) {
    fun build(settings: WidgetSettings?, snapshot: WidgetSnapshot, now: Instant): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_grid)
        val projects = settings?.projects.orEmpty()
        views.removeAllViews(R.id.grid_rows)
        projects.forEachIndexed { rowIndex, project ->
            views.addView(R.id.grid_rows, buildRow(rowIndex, project, snapshot))
        }
        val status = resolveWidgetStatus(
            selection = snapshot.selection,
            sending = snapshot.sending,
            outcome = snapshot.outcome,
            asOf = snapshot.fetchedAt ?: now,
        )
        views.setTextViewText(R.id.status_text, statusText(status))
        return views
    }

    private fun buildRow(rowIndex: Int, project: String, snapshot: WidgetSnapshot): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_grid_row)
        row.setTextViewText(R.id.project_name, project)
        ActivityKind.entries.forEach { activity ->
            val cell = CellKey(project, activity)
            val viewId = when (activity) {
                ActivityKind.WORK -> R.id.cell_work
                ActivityKind.MEETING -> R.id.cell_meeting
            }
            val visual = resolveCellVisual(cell, snapshot.selection, snapshot.sending)
            row.setImageViewResource(iconViewId(viewId), drawableFor(visual))
            row.setOnClickPendingIntent(viewId, tapPendingIntent(rowIndex, cell))
        }
        return row
    }

    private fun iconViewId(cellViewId: Int): Int = when (cellViewId) {
        R.id.cell_work -> R.id.cell_work_icon
        else -> R.id.cell_meeting_icon
    }

    private fun drawableFor(visual: CellVisual): Int = when (visual) {
        CellVisual.UNSELECTED -> R.drawable.cell_unselected
        CellVisual.SELECTED -> R.drawable.cell_selected
        CellVisual.SENDING -> R.drawable.cell_sending
    }

    private fun tapPendingIntent(rowIndex: Int, cell: CellKey): PendingIntent {
        val intent = Intent(context, SelectionWidgetProvider::class.java)
            .setAction(SelectionWidgetProvider.ACTION_CELL_TAP)
            .setData("timetrackingradio://cell/$rowIndex/${cell.activity.code}".toUri())
            .putExtra(SelectionWidgetProvider.EXTRA_PROJECT, cell.project)
            .putExtra(SelectionWidgetProvider.EXTRA_ACTIVITY, cell.activity.code)
        val requestCode = rowIndex * ActivityKind.entries.size + cell.activity.ordinal
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun statusText(status: WidgetStatus): String = when (status) {
        is WidgetStatus.Recording -> context.getString(
            R.string.status_recording,
            status.selection.cell.project,
            status.selection.cell.activity.code,
            formatUtcHourMinute(status.selection.since),
        )
        WidgetStatus.NotRecording -> context.getString(R.string.status_not_recording)
        is WidgetStatus.Switching -> context.getString(
            R.string.status_switching,
            status.cell.project,
            status.cell.activity.code,
        )
        is WidgetStatus.Unreachable -> context.getString(
            R.string.status_unreachable,
            formatUtcHourMinute(status.asOf),
        )
        is WidgetStatus.ServerRejected -> context.getString(R.string.status_server_rejected, status.message)
    }
}
