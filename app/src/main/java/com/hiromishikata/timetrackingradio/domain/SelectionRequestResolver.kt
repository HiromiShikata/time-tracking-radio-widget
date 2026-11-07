package com.hiromishikata.timetrackingradio.domain

enum class CellVisual { UNSELECTED, SELECTED, SENDING }

fun resolveTapRequest(current: Selection?, tapped: CellKey): SelectionRequest =
    if (current?.cell == tapped) SelectionRequest.Clear else SelectionRequest.Select(tapped)

fun resolveCellVisual(cell: CellKey, current: Selection?, sending: SelectionRequest?): CellVisual {
    val isSending = when (sending) {
        is SelectionRequest.Select -> sending.cell == cell
        SelectionRequest.Clear -> current?.cell == cell
        null -> false
    }
    return when {
        isSending -> CellVisual.SENDING
        current?.cell == cell -> CellVisual.SELECTED
        else -> CellVisual.UNSELECTED
    }
}
