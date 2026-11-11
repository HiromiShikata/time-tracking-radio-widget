package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.MalformedURLException
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import java.time.Instant

class DiagnosticDetailsTest {
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val alpha = CellKey("alpha", ActivityKind.WORK)
    private val workId = "0b1f6c1e-3a57-4f4e-9a52-6f0f2d7a9c11"

    @Test
    fun selectionRequestSummaryNamesTheSentFieldsAndNeverAKey() {
        val cases = listOf(
            Triple(SelectionRequest.Select(alpha), tappedAt, "project=alpha, activity=work, tapped_at=2026-11-09T02:19:03.112Z"),
            Triple(SelectionRequest.Select(alpha), null, "project=alpha, activity=work, tapped_at=none"),
            Triple(SelectionRequest.Clear, tappedAt, "project=none, activity=none, tapped_at=2026-11-09T02:19:03.112Z"),
            Triple(SelectionRequest.Clear, null, "project=none, activity=none, tapped_at=none"),
        )
        cases.forEach { (request, at, expected) ->
            assertEquals("case=$expected", expected, selectionRequestSummary(request, at))
        }
    }

    @Test
    fun requestKindFollowsTheSelectionRequest() {
        assertEquals(RequestKind.SELECTION, requestKindOf(SelectionRequest.Select(alpha)))
        assertEquals(RequestKind.CLEAR, requestKindOf(SelectionRequest.Clear))
    }

    @Test
    fun requestSentNamesTheKindMethodTargetAndSentFields() {
        val summary = selectionRequestSummary(SelectionRequest.Select(alpha), tappedAt)
        assertEquals(
            "kind=selection, method=POST, target=https://h.example/exec?key=***, project=alpha, activity=work, tapped_at=2026-11-09T02:19:03.112Z",
            requestSentDetail(RequestDiagnostics(RequestKind.SELECTION, summary), "POST", "https://h.example/exec?key=***"),
        )
        assertEquals(
            "kind=refresh, method=GET, target=host=h.example",
            requestSentDetail(RequestDiagnostics(RequestKind.REFRESH), "GET", "host=h.example"),
        )
        assertEquals(
            "kind=unspecified, method=GET, target=host=h.example",
            requestSentDetail(null, "GET", "host=h.example"),
        )
    }

    @Test
    fun httpResponseNamesStatusElapsedTimeAndOnlyTheLocationHost() {
        val cases: List<Pair<Triple<Int, String?, Long?>, String>> = listOf(
            Triple(200, null, 41L) to "status=200, elapsed_ms=41",
            Triple(302, "https://script.googleusercontent.com/echo?user_content_key=t0ken", 7L) to
                "status=302, elapsed_ms=7, location_host=script.googleusercontent.com",
            Triple(500, null, null) to "status=500, elapsed_ms=unknown",
        )
        cases.forEach { (input, expected) ->
            val (status, location, elapsed) = input
            assertEquals("case=$expected", expected, httpResponseDetail(status, location, elapsed))
        }
        assertFalse(httpResponseDetail(302, "https://x.example/?t=t0ken", 1).contains("t0ken"))
    }

    @Test
    fun exceptionNamesTheFailurePhaseAndElapsedTimeAndNeverTheQuery() {
        assertEquals(
            "IOException: unreachable, phase=connecting, elapsed_ms=12",
            exceptionDetail(IOException("unreachable"), FailurePhase.CONNECTING, 12),
        )
        assertEquals(
            "MalformedURLException: no protocol: /echo?***, phase=unknown, elapsed_ms=unknown",
            exceptionDetail(MalformedURLException("no protocol: /echo?token=SECRET123"), FailurePhase.UNKNOWN, null),
        )
    }

    @Test
    fun failurePhaseIsTakenFromATransportPhaseFailureOnly() {
        assertEquals(FailurePhase.READING, failurePhaseOf(TransportPhaseFailure(FailurePhase.READING, IOException("cut"))))
        assertEquals(FailurePhase.UNKNOWN, failurePhaseOf(IOException("cut")))
    }

    @Test
    fun responseParsedNamesTheReturnedSelectionMessageAndTotalTime() {
        val since = Instant.parse("2026-11-07T08:39:00Z")
        val cases = listOf(
            responseParsedDetail("ok", Selection(alpha, since), null, 90) to
                "result=ok, selection_project=alpha, selection_activity=work, selection_since=2026-11-07T08:39:00Z, message=none, total_elapsed_ms=90",
            responseParsedDetail("ok", null, null, 3) to
                "result=ok, selection=none, message=none, total_elapsed_ms=3",
            responseParsedDetail("ng", null, "bad request", 5) to
                "result=ng, selection=none, message=bad request, total_elapsed_ms=5",
            responseParsedDetail("malformed", null, "response is not JSON", null) to
                "result=malformed, selection=none, message=response is not JSON, total_elapsed_ms=unknown",
        )
        cases.forEach { (actual, expected) -> assertEquals("case=$expected", expected, actual) }
    }

    @Test
    fun appStartedNamesTheVersionSdkAndDevice() {
        assertEquals(
            "version_name=1.4.0, version_code=14, sdk_int=36, manufacturer=Acme, model=Alpha One",
            appStartedDetail(AppStartInfo("1.4.0", 14L, 36, "Acme", "Alpha One")),
        )
    }

    @Test
    fun recordAppStartedWritesOneAppStartedEvent() {
        val log = FakeDiagnosticLogRepository()
        recordAppStarted(DiagnosticRecorder(log, emptySet()) { Instant.EPOCH }, AppStartInfo("1.4.0", 14L, 36, "Acme", "Alpha One"))
        assertEquals(
            listOf(DiagnosticEventType.APP_STARTED to "version_name=1.4.0, version_code=14, sdk_int=36, manufacturer=Acme, model=Alpha One"),
            log.recorded.map { it.type to it.detail },
        )
    }

    @Test
    fun tapReceivedNamesTheTapTheResolvedRequestTheQueueAndTheWorkId() {
        val cases = listOf(
            tapReceivedDetail(tappedAt, alpha, SelectionRequest.Select(alpha), 2, workId) to
                "tapped_at=2026-11-09T02:19:03.112Z, project=alpha, activity=work, request=select, queued_taps=2, work_id=$workId",
            tapReceivedDetail(tappedAt, alpha, SelectionRequest.Clear, 0, null) to
                "tapped_at=2026-11-09T02:19:03.112Z, project=alpha, activity=work, request=clear, queued_taps=0, work_id=none",
        )
        cases.forEach { (actual, expected) -> assertEquals("case=$expected", expected, actual) }
    }

    @Test
    fun networkStateNamesEveryFieldAndMarksUnavailableOnesUnknown() {
        val cases = listOf(
            NetworkState(false, emptyList(), null, null, null, null) to
                "network_active=false, transports=none, validated=unknown, metered=unknown, background_restricted=unknown, data_saver=unknown",
            NetworkState(true, listOf("wifi", "cellular"), false, true, true, true) to
                "network_active=true, transports=wifi+cellular, validated=false, metered=true, background_restricted=true, data_saver=true",
        )
        cases.forEach { (state, expected) -> assertEquals("case=$expected", expected, networkStateDetail(state)) }
    }

    @Test
    fun workStartedNamesTheTapDelayAttemptAndNetwork() {
        assertEquals(
            "tapped_at=2026-11-09T02:19:03.112Z, delay_ms=16200, run_attempt=2, network_active=true, transports=wifi, validated=true, metered=false, background_restricted=false, data_saver=unknown",
            workStartedDetail(tappedAt, 16_200, 2, NetworkState(true, listOf("wifi"), true, false, false, null)),
        )
    }

    @Test
    fun requestTimerMeasuresTheLatestRequestAndTheWholeExchange() {
        var now = 0L
        val timer = RequestTimer { now }
        assertNull(timer.millisSinceRequestSent())
        timer.beginExchange()
        now = 1_000_000L
        timer.requestSent()
        now = 5_000_000L
        assertEquals(4L, timer.millisSinceRequestSent())
        now = 9_000_000L
        timer.requestSent()
        now = 10_000_000L
        assertEquals(1L, timer.millisSinceRequestSent())
        assertEquals(9L, timer.millisSinceExchangeStart())
        timer.beginExchange()
        assertNull(timer.millisSinceExchangeStart())
    }
}
