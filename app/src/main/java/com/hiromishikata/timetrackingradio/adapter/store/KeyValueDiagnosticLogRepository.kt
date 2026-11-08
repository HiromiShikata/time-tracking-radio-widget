package com.hiromishikata.timetrackingradio.adapter.store

import com.hiromishikata.timetrackingradio.domain.DIAGNOSTIC_LOG_CAPACITY
import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticLogRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

/** Process-wide lock: every repository instance reads and writes the same stored log. */
private val diagnosticLogLock = Any()

class KeyValueDiagnosticLogRepository(
    private val store: KeyValueStore,
    private val capacity: Int = DIAGNOSTIC_LOG_CAPACITY,
) : DiagnosticLogRepository {
    override fun append(at: Instant, type: DiagnosticEventType, detail: String) = synchronized(diagnosticLogLock) {
        val current = readStored()
        val next = DiagnosticEntry(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1,
            at = at,
            type = type,
            detail = detail,
            delivered = false,
        )
        writeStored((listOf(next) + current).take(capacity))
    }

    override fun readAll(): List<DiagnosticEntry> = synchronized(diagnosticLogLock) { readStored() }

    override fun readUndelivered(limit: Int): List<DiagnosticEntry> = synchronized(diagnosticLogLock) {
        readStored().filterNot { it.delivered }.asReversed().take(limit)
    }

    override fun markDelivered(ids: Set<Long>) = synchronized(diagnosticLogLock) {
        writeStored(readStored().map { if (it.id in ids) it.copy(delivered = true) else it })
    }

    private fun readStored(): List<DiagnosticEntry> {
        val text = store.read(LOG_KEY) ?: return emptyList()
        return (Json.parseToJsonElement(text) as JsonArray).map { element ->
            val fields = element as JsonObject
            DiagnosticEntry(
                id = fields.getValue("id").jsonPrimitive.long,
                at = Instant.parse(fields.getValue("at").jsonPrimitive.content),
                type = DiagnosticEventType.entries.single { it.code == fields.getValue("type").jsonPrimitive.content },
                detail = fields.getValue("detail").jsonPrimitive.content,
                delivered = fields.getValue("delivered").jsonPrimitive.boolean,
            )
        }
    }

    private fun writeStored(entries: List<DiagnosticEntry>) {
        store.write(
            LOG_KEY,
            JsonArray(
                entries.map { entry ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(entry.id),
                            "at" to JsonPrimitive(entry.at.toString()),
                            "type" to JsonPrimitive(entry.type.code),
                            "detail" to JsonPrimitive(entry.detail),
                            "delivered" to JsonPrimitive(entry.delivered),
                        ),
                    )
                },
            ).toString(),
        )
    }

    private companion object {
        const val LOG_KEY = "diagnostic_log"
    }
}
