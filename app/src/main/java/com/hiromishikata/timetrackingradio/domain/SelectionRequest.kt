package com.hiromishikata.timetrackingradio.domain

sealed interface SelectionRequest {
    data class Select(val cell: CellKey) : SelectionRequest

    data object Clear : SelectionRequest
}
