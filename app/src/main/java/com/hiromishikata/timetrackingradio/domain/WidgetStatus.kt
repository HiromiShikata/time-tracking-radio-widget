package com.hiromishikata.timetrackingradio.domain

import java.time.Instant

sealed interface WidgetStatus {
    data class Recording(val selection: Selection) : WidgetStatus

    data object NotRecording : WidgetStatus

    data class Switching(val cell: CellKey) : WidgetStatus

    data class Unreachable(val asOf: Instant) : WidgetStatus

    data class ServerRejected(val message: String) : WidgetStatus
}

fun resolveWidgetStatus(
    selection: Selection?,
    sending: SelectionRequest?,
    outcome: WidgetOutcome,
    asOf: Instant,
): WidgetStatus = when {
    sending is SelectionRequest.Select -> WidgetStatus.Switching(sending.cell)
    sending == SelectionRequest.Clear -> WidgetStatus.NotRecording
    outcome == WidgetOutcome.Unreachable -> WidgetStatus.Unreachable(asOf)
    outcome is WidgetOutcome.Rejected -> WidgetStatus.ServerRejected(outcome.message)
    selection != null -> WidgetStatus.Recording(selection)
    else -> WidgetStatus.NotRecording
}
