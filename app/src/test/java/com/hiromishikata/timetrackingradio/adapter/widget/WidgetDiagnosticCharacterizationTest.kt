package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.NetworkState
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Characterizes widget_update_requested and the stable leading fields of work_started as they are today.
 * The network fields of work_started are replaced by the device state change, so only the leading fields are pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetDiagnosticCharacterizationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val selectedMeetingBody = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
    private val network = NetworkState(true, listOf("wifi"), true, false, false, false)

    private fun log() = widgetDiagnosticLog(context)

    private fun saveSettings() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
    }

    @Test
    fun widgetUpdateRequestedIsRecordedAsRefreshFollowedByTheDeviceStateSnapshot() {
        saveSettings()
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, selectedMeetingBody) })))
        val controller = WidgetController(context, { transport }, { transport }, publish = { _, _ -> }, clock = { tappedAt })
        runBlocking { controller.refresh() }
        val details = log().readAll().filter { it.type == DiagnosticEventType.WIDGET_UPDATE_REQUESTED }.map { it.detail }
        assertEquals("details=$details", 1, details.size)
        val detail = details.single()
        assertTrue("detail=$detail", detail.startsWith("refresh, network_present="))
        val snapshotKeys = detail.removePrefix("refresh, ").split(", ").map { it.substringBefore("=") }
        assertEquals("detail=$detail", SNAPSHOT_KEYS, snapshotKeys)
    }

    @Test
    fun workStartedKeepsItsTapDelayAndRunAttemptFieldsFirst() {
        val cases = listOf(
            Triple(2_500L, 3, "tapped_at=$tappedAt, delay_ms=2500, run_attempt=3, "),
            Triple(0L, 1, "tapped_at=$tappedAt, delay_ms=0, run_attempt=1, "),
        )
        cases.forEach { (delayMillis, attempt, expectedPrefix) ->
            saveSettings()
            val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, selectedMeetingBody) })))
            var now = tappedAt
            val controller = WidgetController(context, { transport }, { transport }, publish = { _, _ -> }, clock = { now })
            controller.acceptQueuedTap(meeting) { at, request -> WidgetWorkScheduler.buildTapRequest(meeting, at, request) }
            now = tappedAt.plusMillis(delayMillis)
            runBlocking {
                controller.performWorkTap(meeting, SelectionRequest.Select(meeting), tappedAt, WorkRun(attempt) { network })
            }
            val detail = log().readAll().first { it.type == DiagnosticEventType.WORK_STARTED }.detail
            assertEquals("case=$expectedPrefix", true, detail.startsWith(expectedPrefix))
        }
    }
}

private val SNAPSHOT_KEYS = listOf(
    "network_present",
    "transports",
    "validated",
    "metered",
    "blocked",
    "data_saver",
    "device_idle_mode",
    "light_idle_mode",
    "power_save_mode",
    "ignores_battery_optimizations",
    "standby_bucket",
    "background_restricted",
    "process_importance",
)
