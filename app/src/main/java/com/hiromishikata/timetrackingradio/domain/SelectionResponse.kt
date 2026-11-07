package com.hiromishikata.timetrackingradio.domain

sealed interface SelectionResponse {
    data class Accepted(val selection: Selection?) : SelectionResponse

    data class Rejected(val message: String, val reported: ReportedSelection) : SelectionResponse
}

sealed interface ReportedSelection {
    data class Reported(val selection: Selection?) : ReportedSelection

    data object NotReported : ReportedSelection
}
