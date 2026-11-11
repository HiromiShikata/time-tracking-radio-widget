package com.hiromishikata.timetrackingradio.domain

fun resolveTapRequest(current: Selection?, tapped: CellKey): SelectionRequest =
    if (current?.cell == tapped) SelectionRequest.Clear else SelectionRequest.Select(tapped)
