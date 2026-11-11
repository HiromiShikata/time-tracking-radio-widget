package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.NetworkState
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetDiagnosticDetailTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val selectedMeetingBody = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
    private val network = NetworkState(true, listOf("wifi"), true, false, false, false)

    private fun log() = KeyValueDiagnosticLogRepository(
        SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)),
    )

    private fun saveSettings() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
    }

    @Test
    fun tapReceivedNamesTheTapTheRequestTheQueueAndTheWorkRequest() {
        saveSettings()
        val forbidden = ForbiddenTransport()
        val controller = WidgetController(context, { forbidden }, { forbidden }, publish = { _, _ -> }, clock = { tappedAt })
        val queued = controller.acceptQueuedTap(meeting) { at, request -> WidgetWorkScheduler.buildTapRequest(meeting, at, request) }
        assertEquals(
            "tapped_at=$tappedAt, project=project-a, activity=meeting, request=select, queued_taps=1, work_id=${queued.workRequest.id}",
            log().readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }.detail,
        )
    }

    @Test
    fun widgetStatusIsRecordedOnlyWhenTheShownTextChanges() {
        saveSettings()
        val forbidden = ForbiddenTransport()
        val controller = WidgetController(context, { forbidden }, { forbidden }, publish = { _, _ -> }, clock = { tappedAt })
        controller.render()
        controller.render()
        controller.acceptQueuedTap(meeting) { at, request -> WidgetWorkScheduler.buildTapRequest(meeting, at, request) }
        assertEquals(
            listOf(
                context.getString(R.string.status_not_recording),
                context.getString(R.string.status_waiting_for_network),
            ),
            log().readAll().filter { it.type == DiagnosticEventType.WIDGET_STATUS }.map { it.detail }.reversed(),
        )
    }

    @Test
    fun workStartedNamesTheTapDelayAttemptAndNetworkAndPrecedesTheSelectionRequest() {
        saveSettings()
        var now = tappedAt
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, selectedMeetingBody) })))
        val controller = WidgetController(context, { transport }, { transport }, publish = { _, _ -> }, clock = { now })
        controller.acceptQueuedTap(meeting) { at, request -> WidgetWorkScheduler.buildTapRequest(meeting, at, request) }
        now = tappedAt.plusMillis(2_500)
        runBlocking {
            controller.performWorkTap(meeting, SelectionRequest.Select(meeting), tappedAt, WorkRun(3) { network })
        }
        val entries = log().readAll().reversed()
        assertEquals(
            "tapped_at=$tappedAt, delay_ms=2500, run_attempt=3, network_active=true, transports=wifi, validated=true, metered=false, background_restricted=false, data_saver=false",
            entries.single { it.type == DiagnosticEventType.WORK_STARTED }.detail,
        )
        val workStartedIndex = entries.indexOfFirst { it.type == DiagnosticEventType.WORK_STARTED }
        val requestSentIndex = entries.indexOfFirst { it.type == DiagnosticEventType.REQUEST_SENT }
        assertTrue(workStartedIndex in 0 until requestSentIndex)
        assertEquals(
            "kind=selection, method=POST, target=https://example.com/exec?key=***, project=project-a, activity=meeting, tapped_at=$tappedAt",
            entries[requestSentIndex].detail,
        )
    }

    @Test
    fun theDeviceNetworkStateReportsNoActiveNetworkWhenThereIsNone() {
        val state = readDeviceNetworkState(context)
        assertFalse(state.activeNetwork)
        assertEquals(emptyList<String>(), state.transports)
    }
}
