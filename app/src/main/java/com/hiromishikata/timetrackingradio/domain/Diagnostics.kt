package com.hiromishikata.timetrackingradio.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

const val DIAGNOSTIC_LOG_CAPACITY = 200
const val DIAGNOSTICS_UPLOAD_LIMIT = 200
const val REDACTED = "***"

enum class DiagnosticEventType(val code: String) {
    WIDGET_UPDATE_REQUESTED("widget_update_requested"),
    TAP_RECEIVED("tap_received"),
    REQUEST_SENT("request_sent"),
    HTTP_RESPONSE("http_response"),
    RESPONSE_PARSED("response_parsed"),
    SERVER_NG("server_ng"),
    EXCEPTION("exception"),
    SETTINGS_SAVED("settings_saved"),
    CONNECTION_CHECK("connection_check"),
    DIAGNOSTICS_UPLOAD("diagnostics_upload"),
    UPDATE_CHECK("update_check"),
}

data class DiagnosticEntry(
    val id: Long,
    val at: Instant,
    val type: DiagnosticEventType,
    val detail: String,
    val delivered: Boolean,
)

interface DiagnosticSink {
    fun record(type: DiagnosticEventType, detail: String)
}

object NoDiagnostics : DiagnosticSink {
    override fun record(type: DiagnosticEventType, detail: String) = Unit
}

interface DiagnosticLogRepository {
    fun append(at: Instant, type: DiagnosticEventType, detail: String)

    /** Newest entry first. */
    fun readAll(): List<DiagnosticEntry>

    /** Undelivered entries, oldest first, at most [limit]. */
    fun readUndelivered(limit: Int): List<DiagnosticEntry>

    fun markDelivered(ids: Set<Long>)
}

sealed interface DiagnosticsUploadResult {
    data object Delivered : DiagnosticsUploadResult

    data class Failed(val reason: String) : DiagnosticsUploadResult
}

interface DiagnosticsUploadRepository {
    fun upload(entries: List<DiagnosticEntry>): DiagnosticsUploadResult
}

class DiagnosticRecorder(
    private val log: DiagnosticLogRepository,
    private val secrets: Set<String>,
    private val clock: () -> Instant,
) : DiagnosticSink {
    override fun record(type: DiagnosticEventType, detail: String) {
        log.append(clock(), type, scrubSecrets(detail, secrets))
    }
}

private val keyParameter = Regex("""([?&]key=)([^&#]*)""")

fun redactEndpointKey(url: String): String = keyParameter.replace(url) { match ->
    match.groupValues[1] + REDACTED
}

fun endpointKeySecrets(url: String): Set<String> = keyParameter.findAll(url)
    .flatMap { match ->
        val raw = match.groupValues[2]
        val decoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
        listOf(raw, decoded)
    }
    .filter { it.isNotEmpty() }
    .toSet()

fun scrubSecrets(text: String, secrets: Set<String>): String =
    secrets.fold(text) { scrubbed, secret -> scrubbed.replace(secret, REDACTED) }

fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "unknown"

private val queryAndAfter = Regex("""\?[\s\S]*""")

/**
 * Replaces everything from the first `?` to the end of the text with `?***`. A query can contain characters
 * that java.net.URI rejects, so it is not cut at whitespace.
 */
fun redactUrlQueries(text: String): String = queryAndAfter.replace(text, "?$REDACTED")

/** The class and the message of a failure, with any URL query and every known secret removed. */
fun describeFailure(failure: Throwable, secrets: Set<String> = emptySet()): String =
    scrubSecrets(redactUrlQueries("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"), secrets)

fun formatDiagnosticLine(entry: DiagnosticEntry): String = "${entry.at} ${entry.type.code} ${entry.detail}"

fun renderDiagnosticLog(entries: List<DiagnosticEntry>): String =
    entries.joinToString("\n") { formatDiagnosticLine(it) }

fun encodeDiagnosticsUpload(entries: List<DiagnosticEntry>): String = JsonObject(
    mapOf(
        "diagnostics" to JsonArray(
            entries.map { entry ->
                JsonObject(
                    mapOf(
                        "at" to JsonPrimitive(entry.at.toString()),
                        "event" to JsonPrimitive(entry.type.code),
                        "detail" to JsonPrimitive(entry.detail),
                    ),
                )
            },
        ),
    ),
).toString()

fun parseDiagnosticsUploadResponse(body: String): DiagnosticsUploadResult = try {
    if (parseJsonObject(body).stringFieldOrNull("result") == "ok") {
        DiagnosticsUploadResult.Delivered
    } else {
        DiagnosticsUploadResult.Failed("endpoint did not answer ok")
    }
} catch (failure: MalformedResponseException) {
    DiagnosticsUploadResult.Failed("response was not understood")
}
