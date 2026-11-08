package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.ConnectionCheckResult
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionResponse
import com.hiromishikata.timetrackingradio.domain.scrubSecrets

class WidgetConnectionCheckUseCase(
    private val selections: SelectionRepository,
    private val diagnostics: DiagnosticSink,
    private val secrets: Set<String>,
) {
    fun execute(): ConnectionCheckResult {
        val result = try {
            when (val response = selections.read()) {
                is SelectionResponse.Accepted -> ConnectionCheckResult.Connected(response.selection)
                is SelectionResponse.Rejected ->
                    ConnectionCheckResult.ServerRejected(scrubSecrets(response.message, secrets))
            }
        } catch (failure: CommunicationFailedException) {
            ConnectionCheckResult.Failed(scrubSecrets(failure.message.orEmpty(), secrets))
        }
        diagnostics.record(DiagnosticEventType.CONNECTION_CHECK, describe(result))
        return result
    }

    private fun describe(result: ConnectionCheckResult): String = when (result) {
        is ConnectionCheckResult.Connected -> result.selection?.let {
            "connected, selection ${it.cell.project} / ${it.cell.activity.code} since ${it.since}"
        } ?: "connected, no selection"
        is ConnectionCheckResult.ServerRejected -> "server ng: ${result.message}"
        is ConnectionCheckResult.Failed -> "failed: ${result.reason}"
    }
}
