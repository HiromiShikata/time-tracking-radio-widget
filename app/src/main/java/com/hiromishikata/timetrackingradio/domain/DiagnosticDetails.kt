package com.hiromishikata.timetrackingradio.domain

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class RequestKind(val code: String) {
    SELECTION("selection"),
    CLEAR("clear"),
    REFRESH("refresh"),
    DIAGNOSTICS_UPLOAD("diagnostics_upload"),
    UPDATE_CHECK("update_check"),
    UNSPECIFIED("unspecified"),
}

enum class FailurePhase(val code: String) {
    CONNECTING("connecting"),
    READING("reading"),
    AFTER_RESPONSE("after_response"),
    UNKNOWN("unknown"),
}

data class RequestDiagnostics(val kind: RequestKind, val summary: String = "")

fun requestKindOf(request: SelectionRequest): RequestKind = when (request) {
    is SelectionRequest.Select -> RequestKind.SELECTION
    SelectionRequest.Clear -> RequestKind.CLEAR
}

fun selectionRequestSummary(request: SelectionRequest, tappedAt: Instant?): String {
    val (project, activity) = when (request) {
        is SelectionRequest.Select -> request.cell.project to request.cell.activity.code
        SelectionRequest.Clear -> "none" to "none"
    }
    return "project=$project, activity=$activity, tapped_at=${tappedAt ?: "none"}"
}

fun failurePhaseOf(failure: Throwable): FailurePhase = (failure as? TransportPhaseFailure)?.phase ?: FailurePhase.UNKNOWN

fun unwrapTransportFailure(failure: IOException): IOException = (failure as? TransportPhaseFailure)?.original ?: failure

class RequestTimer(private val nanoTime: () -> Long = System::nanoTime) {
    private var exchangeStart: Long? = null
    private var requestStart: Long? = null

    fun beginExchange() {
        exchangeStart = null
        requestStart = null
    }

    fun requestSent() {
        val now = nanoTime()
        requestStart = now
        if (exchangeStart == null) exchangeStart = now
    }

    fun millisSinceRequestSent(): Long? = requestStart?.let { elapsedMillis(it) }

    fun millisSinceExchangeStart(): Long? = exchangeStart?.let { elapsedMillis(it) }

    private fun elapsedMillis(since: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanoTime() - since)
}

private fun elapsedText(millis: Long?): String = millis?.toString() ?: "unknown"

private fun booleanText(value: Boolean?): String = value?.toString() ?: "unknown"

fun requestSentDetail(diagnostics: RequestDiagnostics?, method: String, target: String): String {
    val kind = diagnostics?.kind ?: RequestKind.UNSPECIFIED
    val summary = diagnostics?.summary?.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
    return "kind=${kind.code}, method=$method, target=$target$summary"
}

fun httpResponseDetail(status: Int, location: String?, elapsedMillis: Long?): String {
    val locationHost = location?.let { ", location_host=${hostOf(it)}" }.orEmpty()
    return "status=$status, elapsed_ms=${elapsedText(elapsedMillis)}$locationHost"
}

fun exceptionDetail(failure: Throwable, phase: FailurePhase, elapsedMillis: Long?): String =
    "${describeFailure(failure)}, phase=${phase.code}, elapsed_ms=${elapsedText(elapsedMillis)}"

fun responseParsedDetail(result: String, selection: Selection?, message: String?, totalElapsedMillis: Long?): String {
    val selectionText = selection?.let {
        "selection_project=${it.cell.project}, selection_activity=${it.cell.activity.code}, selection_since=${it.since}"
    } ?: "selection=none"
    return "result=$result, $selectionText, message=${message ?: "none"}, total_elapsed_ms=${elapsedText(totalElapsedMillis)}"
}

data class AppStartInfo(
    val versionName: String,
    val versionCode: Long,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
)

fun appStartedDetail(info: AppStartInfo): String =
    "version_name=${info.versionName}, version_code=${info.versionCode}, sdk_int=${info.sdkInt}, " +
        "manufacturer=${info.manufacturer}, model=${info.model}"

fun recordAppStarted(sink: DiagnosticSink, info: AppStartInfo) {
    sink.record(DiagnosticEventType.APP_STARTED, appStartedDetail(info))
}

fun tapReceivedDetail(tappedAt: Instant, cell: CellKey, request: SelectionRequest, queuedTaps: Int, workId: String?): String {
    val resolved = when (request) {
        is SelectionRequest.Select -> "select"
        SelectionRequest.Clear -> "clear"
    }
    return "tapped_at=$tappedAt, project=${cell.project}, activity=${cell.activity.code}, " +
        "request=$resolved, queued_taps=$queuedTaps, work_id=${workId ?: "none"}"
}

data class NetworkState(
    val activeNetwork: Boolean,
    val transports: List<String>,
    val validated: Boolean?,
    val metered: Boolean?,
    val backgroundRestricted: Boolean?,
    val dataSaverEnabled: Boolean?,
)

fun networkStateDetail(state: NetworkState): String {
    val transports = state.transports.ifEmpty { listOf("none") }.joinToString("+")
    return "network_active=${state.activeNetwork}, transports=$transports, validated=${booleanText(state.validated)}, " +
        "metered=${booleanText(state.metered)}, background_restricted=${booleanText(state.backgroundRestricted)}, " +
        "data_saver=${booleanText(state.dataSaverEnabled)}"
}

fun workStartedDetail(tappedAt: Instant, delayMillis: Long, runAttemptCount: Int, network: NetworkState): String =
    "tapped_at=$tappedAt, delay_ms=$delayMillis, run_attempt=$runAttemptCount, ${networkStateDetail(network)}"
