package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.formatUtcHourMinute
import com.hiromishikata.timetrackingradio.domain.formatUtcHourMinuteSecond
import java.time.Instant
import java.util.Locale

class SelectionWidgetRemoteViewsFactory(private val context: Context) {
    fun build(settings: WidgetSettings?, snapshot: WidgetSnapshot, now: Instant): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_grid)
        val projects = settings?.projects.orEmpty()
        views.removeAllViews(R.id.grid_rows)
        projects.forEachIndexed { rowIndex, project ->
            views.addView(R.id.grid_rows, buildRow(rowIndex, project, snapshot))
        }
        views.setTextViewText(R.id.status_text, statusText(snapshot, now))
        views.setViewVisibility(R.id.status_text, if (settings?.showStatusDetails == false) View.GONE else View.VISIBLE)
        return views
    }

    fun statusText(snapshot: WidgetSnapshot, now: Instant): String {
        val selection = snapshot.selection
        val selectionLines = if (selection == null) {
            listOf(context.getString(R.string.status_not_recording))
        } else {
            listOf(
                context.getString(R.string.status_recording, selection.cell.project, selection.cell.activity.code),
                context.getString(R.string.status_since, formatUtcHourMinute(selection.since)),
            )
        }
        val queueLine = context.getString(R.string.status_queue, snapshot.queuedTapCount.toString(), versionName())
        return (selectionLines + stateLine(snapshot, now) + queueLine).joinToString("\n")
    }

    private fun stateLine(snapshot: WidgetSnapshot, now: Instant): String {
        val asOf = snapshot.fetchedAt ?: now
        val oldestUnsent = snapshot.oldestUnsentTapAt
        val pending = snapshot.pendingTapAt
        val sentAt = snapshot.sentAt
        val savedAt = snapshot.savedAt
        val refreshedAt = snapshot.fetchedAt
        val roundTripMillis = snapshot.roundTripMillis
        val outcome = snapshot.outcome
        return when {
            oldestUnsent != null -> context.getString(R.string.status_waiting_for_network, formatUtcHourMinuteSecond(oldestUnsent))
            pending != null -> context.getString(R.string.status_saving, formatUtcHourMinuteSecond(sentAt ?: pending))
            outcome is WidgetOutcome.Rejected -> context.getString(R.string.status_server_rejected, outcome.message)
            outcome == WidgetOutcome.Unreachable -> context.getString(R.string.status_unreachable, formatUtcHourMinute(asOf))
            savedAt != null && roundTripMillis != null && (refreshedAt == null || savedAt >= refreshedAt) -> context.getString(
                R.string.status_saved,
                formatUtcHourMinuteSecond(savedAt),
                String.format(Locale.ROOT, "%.1f", roundTripMillis / MILLIS_PER_SECOND),
            )
            else -> context.getString(R.string.status_updated, formatUtcHourMinuteSecond(asOf))
        }
    }

    private fun versionName(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun buildRow(rowIndex: Int, project: String, snapshot: WidgetSnapshot): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_grid_row)
        row.setTextViewText(R.id.project_name, project)
        ActivityKind.entries.forEach { activity ->
            val cell = CellKey(project, activity)
            val viewId = when (activity) {
                ActivityKind.WORK -> R.id.cell_work
                ActivityKind.MEETING -> R.id.cell_meeting
            }
            val selected = snapshot.selection?.cell == cell
            row.setImageViewResource(
                iconViewId(viewId),
                if (selected) R.drawable.cell_selected else R.drawable.cell_unselected,
            )
            row.setOnClickPendingIntent(viewId, tapPendingIntent(rowIndex, cell))
        }
        return row
    }

    private fun iconViewId(cellViewId: Int): Int = when (cellViewId) {
        R.id.cell_work -> R.id.cell_work_icon
        else -> R.id.cell_meeting_icon
    }

    private fun tapPendingIntent(rowIndex: Int, cell: CellKey): PendingIntent {
        val intent = Intent(context, SelectionCellTapReceiver::class.java)
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

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
