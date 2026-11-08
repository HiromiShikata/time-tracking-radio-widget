package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.DIAGNOSTICS_UPLOAD_LIMIT
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticLogRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticSink
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticsUploadResult
import com.hiromishikata.timetrackingradio.domain.describeFailure
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide: every widget and settings instance shares the one upload in flight. */
private val uploadInFlight = AtomicBoolean(false)

/**
 * Sends the undelivered entries once. A failure is recorded and the entries stay pending for the next
 * attempt; nothing here retries, so a failing upload cannot start another upload.
 */
class DiagnosticUploadUseCase(
    private val log: DiagnosticLogRepository,
    private val uploads: DiagnosticsUploadRepository,
    private val diagnostics: DiagnosticSink,
) {
    /**
     * Skips when another upload is in flight: that upload already holds these entries, and entries recorded
     * meanwhile go out with the next upload, so no entry is sent twice.
     */
    fun execute() {
        if (!uploadInFlight.compareAndSet(false, true)) return
        try {
            uploadPending()
        } finally {
            uploadInFlight.set(false)
        }
    }

    private fun uploadPending() {
        val pending = log.readUndelivered(DIAGNOSTICS_UPLOAD_LIMIT)
        if (pending.isEmpty()) return
        val outcome = try {
            uploads.upload(pending)
        } catch (failure: Exception) {
            DiagnosticsUploadResult.Failed(describeFailure(failure))
        }
        when (outcome) {
            DiagnosticsUploadResult.Delivered -> {
                log.markDelivered(pending.map { it.id }.toSet())
                diagnostics.record(DiagnosticEventType.DIAGNOSTICS_UPLOAD, "delivered ${pending.size} entries")
            }
            is DiagnosticsUploadResult.Failed ->
                diagnostics.record(DiagnosticEventType.DIAGNOSTICS_UPLOAD, "failed: ${outcome.reason}")
        }
    }
}
