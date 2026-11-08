package com.hiromishikata.timetrackingradio.ui

import android.content.Context
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.domain.ConnectionCheckResult
import com.hiromishikata.timetrackingradio.domain.formatUtcHourMinute

fun connectionCheckMessage(context: Context, result: ConnectionCheckResult): String = when (result) {
    is ConnectionCheckResult.Connected -> result.selection?.let {
        context.getString(
            R.string.connection_ok_selection,
            it.cell.project,
            it.cell.activity.code,
            formatUtcHourMinute(it.since),
        )
    } ?: context.getString(R.string.connection_ok_no_selection)
    is ConnectionCheckResult.ServerRejected -> context.getString(R.string.connection_server_rejected, result.message)
    is ConnectionCheckResult.Failed -> context.getString(R.string.connection_failed, result.reason)
}
