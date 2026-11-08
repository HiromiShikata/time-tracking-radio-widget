package com.hiromishikata.timetrackingradio.domain

sealed interface ConnectionCheckResult {
    data class Connected(val selection: Selection?) : ConnectionCheckResult

    data class ServerRejected(val message: String) : ConnectionCheckResult

    data class Failed(val reason: String) : ConnectionCheckResult
}
