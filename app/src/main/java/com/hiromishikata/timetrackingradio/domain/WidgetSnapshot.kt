package com.hiromishikata.timetrackingradio.domain

import java.time.Instant

sealed interface WidgetOutcome {
    data object Settled : WidgetOutcome

    data object Unreachable : WidgetOutcome

    data object WaitingForNetwork : WidgetOutcome

    data class Rejected(val message: String) : WidgetOutcome
}

data class UnconfirmedTap(val request: SelectionRequest, val tappedAt: Instant)

enum class CellMark { FILLED, UNSENT, EMPTY }

data class WidgetSnapshot(
    val selection: Selection?,
    val fetchedAt: Instant?,
    val outcome: WidgetOutcome,
    val sending: SelectionRequest?,
    val statusRequest: SelectionRequest?,
    val pendingTapAt: Instant? = null,
    val sentAt: Instant? = null,
    val oldestUnsentTapAt: Instant? = null,
    val savedAt: Instant? = null,
    val roundTripMillis: Long? = null,
    val queuedTapCount: Int = 0,
    val latestUnconfirmedTap: UnconfirmedTap? = null,
    val dataSaverRestricted: Boolean = false,
) {
    companion object {
        val EMPTY = WidgetSnapshot(null, null, WidgetOutcome.Settled, null, null)
    }
}

fun WidgetSnapshot.cellMarkOf(cell: CellKey): CellMark {
    val unsentCell = when (val request = latestUnconfirmedTap?.request) {
        null -> null
        is SelectionRequest.Select -> request.cell
        SelectionRequest.Clear -> selection?.cell
    }
    return when {
        cell == unsentCell -> CellMark.UNSENT
        cell == selection?.cell -> CellMark.FILLED
        else -> CellMark.EMPTY
    }
}

fun applySelectionResponse(response: SelectionResponse, previous: WidgetSnapshot, now: Instant): WidgetSnapshot =
    when (response) {
        is SelectionResponse.Accepted -> WidgetSnapshot(response.selection, now, WidgetOutcome.Settled, null, null)
        is SelectionResponse.Rejected -> when (val reported = response.reported) {
            is ReportedSelection.Reported ->
                WidgetSnapshot(reported.selection, now, WidgetOutcome.Rejected(response.message), null, null)
            ReportedSelection.NotReported ->
                previous.copy(outcome = WidgetOutcome.Rejected(response.message), sending = null)
        }
    }

fun markUnreachable(previous: WidgetSnapshot): WidgetSnapshot =
    previous.copy(outcome = WidgetOutcome.Unreachable, sending = null)
