package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult

class FakeDiagnosticsUploadRepository(
    private val onUpload: (List<DiagnosticEntry>) -> DiagnosticsUploadResult = { DiagnosticsUploadResult.Delivered },
) : DiagnosticsUploadRepository {
    val calls = mutableListOf<List<DiagnosticEntry>>()

    override fun upload(entries: List<DiagnosticEntry>): DiagnosticsUploadResult {
        calls += entries
        return onUpload(entries)
    }
}
