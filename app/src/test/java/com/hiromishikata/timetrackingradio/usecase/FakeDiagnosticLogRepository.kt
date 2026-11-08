package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticLogRepository
import java.time.Instant

class FakeDiagnosticLogRepository : DiagnosticLogRepository {
    val recorded = mutableListOf<DiagnosticEntry>()

    override fun append(at: Instant, type: DiagnosticEventType, detail: String) {
        recorded += DiagnosticEntry((recorded.maxOfOrNull { it.id } ?: 0L) + 1, at, type, detail, delivered = false)
    }

    override fun readAll(): List<DiagnosticEntry> = recorded.asReversed().toList()

    override fun readUndelivered(limit: Int): List<DiagnosticEntry> =
        recorded.filterNot { it.delivered }.take(limit)

    override fun markDelivered(ids: Set<Long>) {
        recorded.replaceAll { if (it.id in ids) it.copy(delivered = true) else it }
    }
}
