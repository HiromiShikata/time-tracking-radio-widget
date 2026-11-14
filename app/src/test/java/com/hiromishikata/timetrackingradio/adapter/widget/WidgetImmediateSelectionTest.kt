package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.NetworkUnavailableException
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetImmediateSelectionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val work = CellKey("project-a", ActivityKind.WORK)
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val projectBWork = CellKey("project-b", ActivityKind.WORK)

    private data class UnsentCase(val name: String, val previous: Selection?, val tapped: CellKey, val expectedFilled: CellKey?, val expectedUnsent: CellKey?)

    private val tapEntries: List<Pair<String, (WidgetController, CellKey) -> Unit>> = listOf(
        "acceptTap" to { controller, cell -> controller.acceptTap(cell, workEnqueued = false) },
        "acceptQueuedTap" to { controller, cell ->
            controller.acceptQueuedTap(cell) { at, request -> WidgetWorkScheduler.buildTapRequest(cell, at, request) }
        },
    )

    private fun resetWith(previous: Selection?) {
        context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        KeyValueWidgetSettingsRepository(store).write(WIDGET_TEST_SETTINGS)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(previous, since, WidgetOutcome.Settled, null, null))
    }

    private fun recordingController(transport: HttpTransport, rendered: MutableList<WidgetSnapshot>) = WidgetController(
        context = context,
        transportFactory = { transport },
        uploadTransportFactory = { transport },
        publish = { _, snapshot -> rendered += snapshot },
        clock = { tappedAt },
    )

    private fun assertFilledCellIs(snapshot: WidgetSnapshot, filled: CellKey?, message: String) {
        listOf(work, meeting).forEach { cell ->
            val expected = if (cell == filled) R.drawable.cell_selected else R.drawable.cell_unselected
            assertEquals("$message cell=$cell", expected, renderedCellDrawableResId(context, snapshot, cell))
        }
    }

    private fun assertCellMarksAre(snapshot: WidgetSnapshot, filled: CellKey?, unsent: CellKey?, message: String) {
        listOf(work, meeting).forEach { cell ->
            val expected = when (cell) {
                unsent -> R.drawable.cell_unsent
                filled -> R.drawable.cell_selected
                else -> R.drawable.cell_unselected
            }
            assertEquals("$message cell=$cell", expected, renderedCellDrawableResId(context, snapshot, cell))
        }
    }

    @Test
    fun aTapShowsAsUnsentWithoutAnyNetworkCallAndKeepsTheConfirmedSelectionFilled() {
        val cases = listOf(
            UnsentCase("no selection dashes the tapped cell", null, meeting, expectedFilled = null, expectedUnsent = meeting),
            UnsentCase("a switch keeps the confirmed cell filled and dashes the tapped one", Selection(work, since), meeting, expectedFilled = work, expectedUnsent = meeting),
            UnsentCase("an unconfirmed clear dashes the confirmed cell", Selection(meeting, since), meeting, expectedFilled = null, expectedUnsent = meeting),
        )
        cases.forEach { case ->
            tapEntries.forEach { (entryName, tap) ->
                resetWith(case.previous)
                val transport = ForbiddenTransport()
                val rendered = mutableListOf<WidgetSnapshot>()
                tap(recordingController(transport, rendered), case.tapped)
                val message = "case=${case.name} entry=$entryName"
                assertEquals(message, 0, transport.calls)
                assertCellMarksAre(rendered.last(), case.expectedFilled, case.expectedUnsent, message)
            }
        }
    }

    @Test
    fun theServerSelectionReplacesTheProjectionWhenTheTapIsSaved() {
        resetWith(null)
        val body = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(transport, rendered)
        val accepted = controller.acceptTap(meeting, workEnqueued = false)
        assertCellMarksAre(rendered.last(), null, meeting, "unsent before the save")
        runBlocking { controller.performTap(meeting, accepted.request, accepted.tappedAt) }
        val saved = rendered.last()
        assertEquals(Selection(meeting, since), saved.selection)
        assertFilledCellIs(saved, meeting, "server")
        assertTrue(renderedStatusText(context, saved), renderedStatusText(context, saved).contains("since 08:39 UTC"))
    }

    @Test
    fun aRejectionShowsTheServerSelectionAndItsMessage() {
        resetWith(null)
        val body = """{"result":"ng","message":"wrong key","selection":{"project":"project-b","activity":"work","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(transport, rendered)
        val accepted = controller.acceptTap(meeting, workEnqueued = false)
        runBlocking { controller.performTap(meeting, accepted.request, accepted.tappedAt) }
        val rejected = rendered.last()
        assertEquals(WidgetOutcome.Rejected("wrong key"), rejected.outcome)
        assertEquals(Selection(projectBWork, since), rejected.selection)
        assertEquals(
            R.drawable.cell_selected,
            renderedCellDrawableResId(context, rejected, projectBWork),
        )
        assertTrue(renderedStatusText(context, rejected), renderedStatusText(context, rejected).contains("⚠ wrong key"))
    }

    @Test
    fun aSendThatCannotReachTheNetworkShowsTheWaitingLineSinceTheTapTime() {
        resetWith(null)
        val transport = FakeHttpTransport(ArrayDeque(listOf({ throw IOException("unreachable") })))
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(transport, rendered)
        val accepted = controller.acceptTap(meeting, workEnqueued = false)
        val thrown = runCatching { runBlocking { controller.performTap(meeting, accepted.request, accepted.tappedAt) } }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is NetworkUnavailableException)
        val waiting = rendered.last()
        assertEquals(WidgetOutcome.WaitingForNetwork, waiting.outcome)
        assertCellMarksAre(waiting, null, meeting, "the tap stays unsent while waiting")
        assertEquals("⚠ Waiting for network since 02:19:03", renderedStatusText(context, waiting).lines()[2])
    }

    @Test
    fun anUnreachableSendShowsTheCouldNotConnectLineAndNothingPending() {
        resetWith(null)
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(503, null, "") })))
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(transport, rendered)
        val accepted = controller.acceptTap(meeting, workEnqueued = false)
        runBlocking { controller.performTap(meeting, accepted.request, accepted.tappedAt) }
        val failed = rendered.last()
        assertEquals(WidgetOutcome.Unreachable, failed.outcome)
        assertFilledCellIs(failed, null, "reverted to the confirmed selection")
        val text = renderedStatusText(context, failed)
        assertTrue(text, text.contains("⚠ Could not connect (showing state as of"))
        assertTrue(text, !text.contains("Saving"))
    }

    @Test
    fun theWidgetStatusRecordIsTheStatusTextShownAfterATap() {
        resetWith(null)
        val rendered = mutableListOf<WidgetSnapshot>()
        recordingController(ForbiddenTransport(), rendered).acceptTap(meeting, workEnqueued = false)
        val recorded = KeyValueDiagnosticLogRepository(store).readAll()
            .filter { it.type == DiagnosticEventType.WIDGET_STATUS }
            .last()
            .detail
        assertEquals(SelectionWidgetRemoteViewsFactory(context).statusText(rendered.last(), tappedAt), recorded)
        assertTrue(recorded, recorded.contains("Saving… sent 02:19:03"))
    }

    @Test
    fun theDashedSendingDrawableNoLongerExists() {
        assertEquals(0, context.resources.getIdentifier("cell_sending", "drawable", context.packageName))
    }
}
