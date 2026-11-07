package com.hiromishikata.timetrackingradio.domain

import java.time.Instant

sealed interface WidgetOutcome {
    data object Settled : WidgetOutcome

    data object Unreachable : WidgetOutcome

    data class Rejected(val message: String) : WidgetOutcome
}

data class WidgetSnapshot(
    val selection: Selection?,
    val fetchedAt: Instant?,
    val outcome: WidgetOutcome,
    val sending: SelectionRequest?,
) {
    companion object {
        val EMPTY = WidgetSnapshot(null, null, WidgetOutcome.Settled, null)
    }
}

fun applySelectionResponse(response: SelectionResponse, previous: WidgetSnapshot, now: Instant): WidgetSnapshot =
    when (response) {
        is SelectionResponse.Accepted -> WidgetSnapshot(response.selection, now, WidgetOutcome.Settled, null)
        is SelectionResponse.Rejected -> when (val reported = response.reported) {
            is ReportedSelection.Reported ->
                WidgetSnapshot(reported.selection, now, WidgetOutcome.Rejected(response.message), null)
            ReportedSelection.NotReported ->
                previous.copy(outcome = WidgetOutcome.Rejected(response.message), sending = null)
        }
    }

fun markUnreachable(previous: WidgetSnapshot): WidgetSnapshot =
    previous.copy(outcome = WidgetOutcome.Unreachable, sending = null)
