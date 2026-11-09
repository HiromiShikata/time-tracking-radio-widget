package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.http.HttpTransport
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellVisual
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import com.hiromishikata.timetrackingradio.domain.resolveCellVisual
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val settings = WidgetSettings("https://example.com/exec?key=example", listOf("project-a", "project-b"))
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val work = CellKey("project-b", ActivityKind.WORK)
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val acceptedAt = Instant.parse("2026-11-07T08:00:00Z")
    private val tappedAt = Instant.parse("2026-11-09T02:19:03.112Z")
    private val selectedMeetingBody = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
    private val clearedBody = """{"result":"ok","selection":null}"""

    private fun controller(transport: FakeHttpTransport) = WidgetController(context, { transport }, { transport }, publish = { _, _ -> })

    private fun recordingController(
        transport: HttpTransport,
        rendered: MutableList<WidgetSnapshot>,
        clock: () -> Instant = { Instant.now() },
    ) = WidgetController(
        context = context,
        transportFactory = { transport },
        uploadTransportFactory = { transport },
        publish = { _, snapshot -> rendered += snapshot },
        clock = clock,
    )

    private data class RingAgeCase(val name: String, val minutesAfterAcceptance: Long, val expectedSending: SelectionRequest?)

    private data class AcceptTapCase(
        val name: String,
        val previous: Selection?,
        val tapped: CellKey,
        val expectedSending: SelectionRequest,
    )

    private data class ClearCase(val name: String, val acceptedCell: CellKey?, val expectedSending: SelectionRequest?)

    private data class UploadCase(
        val name: String,
        val settingsSaved: Boolean,
        val expectedMethods: List<String>,
        val expectedDelivered: Boolean,
    )

    private class GatedSelectionTransport(private val selectionBody: String) : HttpTransport {
        val selectionStarted = CountDownLatch(1)
        val releaseSelection = CountDownLatch(1)

        override fun send(request: HttpRequest): HttpResponse {
            selectionStarted.countDown()
            releaseSelection.await(10, TimeUnit.SECONDS)
            return HttpResponse(200, null, selectionBody)
        }
    }

    @Test
    fun performTapWithoutSettingsDoesNothing() {
        val transport = FakeHttpTransport(ArrayDeque())
        val controller = controller(transport)
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        runBlocking { controller.performTap(meeting, tappedAt) }
        assertEquals(emptyList<HttpRequest>(), transport.requests)
        assertEquals(WidgetSnapshot.EMPTY, KeyValueWidgetSnapshotRepository(store).read())
    }

    @Test
    fun performTapStoresTheAcceptedSelectionAndClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val body = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        val controller = controller(transport)
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        runBlocking { controller.performTap(meeting, tappedAt) }
        val stored = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals(WidgetSnapshot(Selection(meeting, since), stored.fetchedAt, WidgetOutcome.Settled, null, null), stored)
        assertEquals(1, transport.requests.count { it.method == "POST" && it.body?.startsWith("{\"project\":") == true })
    }

    @Test
    fun refreshWithAnUnreachableEndpointRecordsTheOutcome() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = FakeHttpTransport(ArrayDeque(listOf({ throw IOException("unreachable") })))
        runBlocking { controller(transport).refresh() }
        val stored = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals(WidgetOutcome.Unreachable, stored.outcome)
        assertNull(stored.sending)
    }

    @Test
    fun anUnexpectedFailureStillRendersTheClearedScreen() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque(listOf({ throw IllegalStateException("unexpected") })))
        val controller = WidgetController(context, { transport }, { transport }, publish = { _, snapshot -> rendered += snapshot })
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        rendered.clear()
        val thrown = runCatching { runBlocking { controller.performTap(meeting, tappedAt) } }.exceptionOrNull()
        controller.discardTap(meeting, tappedAt)
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        assertTrue("the dashed ring was never rendered", rendered.any { it.sending != null })
        assertNull("the last rendered screen still shows a sending cell", rendered.last().sending)
    }

    @Test
    fun aTapRecordsItsEventAndSendsNoDiagnosticsUpload() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val selection = """{"result":"ok","selection":null}"""
        val transport = FakeHttpTransport(
            ArrayDeque(listOf({ HttpResponse(200, null, selection) }, { HttpResponse(200, null, """{"result":"ok","accepted":3}""") })),
        )
        val controller = controller(transport)
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        runBlocking { controller.performTap(meeting, tappedAt) }
        assertEquals(listOf("POST"), transport.requests.map { it.method })
        assertFalse(transport.requests.any { it.body.orEmpty().startsWith("{\"diagnostics\":[") })
        val tapEntry = KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }
        assertEquals("project=project-a, activity=meeting", tapEntry.detail)
        assertFalse(tapEntry.delivered)
    }

    @Test
    fun aTapThatFailsUnexpectedlySendsNoDiagnosticsUpload() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = FakeHttpTransport(
            ArrayDeque<() -> HttpResponse>(
                listOf(
                    { throw IllegalStateException("unexpected") },
                    { HttpResponse(200, null, """{"result":"ok","accepted":1}""") },
                ),
            ),
        )
        val controller = controller(transport)
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        val thrown = runCatching { runBlocking { controller.performTap(meeting, tappedAt) } }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        assertEquals(1, transport.requests.size)
        assertFalse(KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }.delivered)
    }

    @Test
    fun aRefreshSendsNoDiagnosticsUpload() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = FakeHttpTransport(
            ArrayDeque<() -> HttpResponse>(
                listOf({ HttpResponse(200, null, """{"result":"ok","selection":null}""") }),
            ),
        )
        runBlocking { controller(transport).refresh() }
        assertEquals(listOf("GET"), transport.requests.map { it.method })
    }

    @Test
    fun anAcceptedTapWithoutSettingsRecordsTheTapAndSendsNothing() {
        val transport = FakeHttpTransport(ArrayDeque())
        controller(transport).acceptTap(meeting, workEnqueued = false)
        assertEquals(listOf("tap_received"), KeyValueDiagnosticLogRepository(store).readAll().map { it.type.code })
        assertEquals(emptyList<HttpRequest>(), transport.requests)
    }

    @Test
    fun refreshRecordsTheUpdateRequest() {
        val transport = FakeHttpTransport(ArrayDeque())
        runBlocking { controller(transport).refresh() }
        assertEquals(listOf("widget_update_requested"), KeyValueDiagnosticLogRepository(store).readAll().map { it.type.code })
    }

    @Test
    fun acceptTapRecordsTheTapAsAnUndeliveredEntryWithoutNetwork() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = ForbiddenTransport()
        WidgetController(context, { transport }, { transport }, publish = { _, _ -> }).acceptTap(meeting, workEnqueued = false)
        val entry = KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }
        assertEquals("project=project-a, activity=meeting", entry.detail)
        assertFalse(entry.delivered)
        assertEquals(0, transport.calls)
    }

    @Test
    fun acceptTapSetsTheSendingStateWithoutAnyNetworkCall() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val cases = listOf(
            AcceptTapCase("no selection selects the tapped cell", null, meeting, SelectionRequest.Select(meeting)),
            AcceptTapCase(
                "another selection is replaced by the tapped cell",
                Selection(CellKey("project-b", ActivityKind.WORK), since),
                meeting,
                SelectionRequest.Select(meeting),
            ),
            AcceptTapCase("the selected cell is cleared", Selection(meeting, since), meeting, SelectionRequest.Clear),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(case.previous, since, WidgetOutcome.Settled, null, null))
            val transport = ForbiddenTransport()
            val rendered = mutableListOf<WidgetSnapshot>()
            WidgetController(context, { transport }, { transport }, publish = { _, snapshot -> rendered += snapshot }).acceptTap(case.tapped, workEnqueued = false)
            val stored = KeyValueWidgetSnapshotRepository(store).read()
            assertEquals("case=${case.name}", 0, transport.calls)
            assertEquals("case=${case.name}", case.expectedSending, stored.sending)
            assertEquals("case=${case.name}", case.expectedSending, rendered.last().sending)
            assertEquals("case=${case.name}", case.previous, stored.selection)
        }
    }

    @Test
    fun discardingTheTapsEntryClearsItsSendingStateAndRendersItIdempotently() {
        val cases = listOf(
            ClearCase("nothing was sending", null, null),
            ClearCase("a selection was sending", work, SelectionRequest.Select(work)),
            ClearCase("a clear was sending", meeting, SelectionRequest.Clear),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, null, null))
            val transport = ForbiddenTransport()
            val rendered = mutableListOf<WidgetSnapshot>()
            val controller = WidgetController(context, { transport }, { transport }, publish = { _, snapshot -> rendered += snapshot })
            val accepted = case.acceptedCell
            val cell = accepted ?: meeting
            val tappedAt = if (accepted != null) controller.acceptTap(accepted, workEnqueued = false) else since
            assertEquals("case=${case.name}", case.expectedSending, KeyValueWidgetSnapshotRepository(store).read().sending)
            controller.discardTap(cell, tappedAt)
            controller.discardTap(cell, tappedAt)
            val stored = KeyValueWidgetSnapshotRepository(store).read()
            assertNull("case=${case.name}", stored.sending)
            assertEquals("case=${case.name}", Selection(meeting, since), stored.selection)
            assertNull("case=${case.name}", rendered.last().sending)
            assertEquals("case=${case.name}", 0, transport.calls)
        }
    }

    @Test
    fun uploadDiagnosticsPostsPendingEntriesOnlyWhenSettingsExist() {
        val cases = listOf(
            UploadCase("without settings nothing is sent", false, emptyList(), false),
            UploadCase("with settings the pending entries are posted once", true, listOf("POST"), true),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            if (case.settingsSaved) KeyValueWidgetSettingsRepository(store).write(settings)
            val transport = FakeHttpTransport(
                ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, """{"result":"ok","accepted":1}""") })),
            )
            val controller = controller(transport)
            controller.acceptTap(meeting, workEnqueued = false)
            controller.uploadDiagnostics()
            assertEquals("case=${case.name}", case.expectedMethods, transport.requests.map { it.method })
            val tapEntry = KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }
            assertEquals("case=${case.name}", case.expectedDelivered, tapEntry.delivered)
        }
    }

    @Test
    fun aSlowSelectionRequestDoesNotDelayTheNextTapsSendingRender() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport("""{"result":"ok","selection":null}""")
        val nextCell = CellKey("project-b", ActivityKind.WORK)
        val nextTapRendered = CountDownLatch(1)
        val publish: (WidgetSettings?, WidgetSnapshot) -> Unit = { _, snapshot ->
            if (snapshot.sending == SelectionRequest.Select(nextCell)) nextTapRendered.countDown()
        }
        val firstTappedAt = WidgetController(context, { transport }, { transport }, publish).acceptTap(meeting, workEnqueued = true)
        val first = thread { runBlocking { WidgetController(context, { transport }, { transport }, publish).performTap(meeting, firstTappedAt) } }
        try {
            assertTrue("the first selection request started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            WidgetController(context, { transport }, { transport }, publish).acceptTap(nextCell, workEnqueued = false)
            val rendered = nextTapRendered.await(2, TimeUnit.SECONDS)
            assertTrue("the next tap rendered its sending state while the selection request was still blocked", rendered)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun aQueuedTapKeepsItsSendingStateWhenAnEarlierTapCompletes() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(
            """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}""",
        )
        val queued = CellKey("project-b", ActivityKind.WORK)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val publish: (WidgetSettings?, WidgetSnapshot) -> Unit = { _, snapshot -> rendered += snapshot }
        val firstTappedAt = WidgetController(context, { transport }, { transport }, publish).acceptTap(meeting, workEnqueued = true)
        val first = thread { runBlocking { WidgetController(context, { transport }, { transport }, publish).performTap(meeting, firstTappedAt) } }
        try {
            assertTrue("the first selection request started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            WidgetController(context, { transport }, { transport }, publish).acceptTap(queued, workEnqueued = false)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
        assertEquals("the queued tap is still sending", SelectionRequest.Select(queued), rendered.last().sending)
        assertEquals(SelectionRequest.Select(queued), KeyValueWidgetSnapshotRepository(store).read().sending)
    }

    @Test
    fun aRefreshKeepsTheRingOfAnAcceptedTap() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        val controller = recordingController(transport, rendered)
        controller.acceptTap(work, workEnqueued = false)
        runBlocking { controller.refresh() }
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
    }

    @Test
    fun aRepeatedTapKeepsItsRingUntilEachOfItsTwoWorksCompletes() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = FakeHttpTransport(
            ArrayDeque<() -> HttpResponse>(
                listOf({ HttpResponse(200, null, selectedMeetingBody) }, { HttpResponse(200, null, clearedBody) }),
            ),
        )
        val controller = controller(transport)
        val firstTappedAt = controller.acceptTap(meeting, workEnqueued = true)
        val secondTappedAt = controller.acceptTap(meeting, workEnqueued = true)
        runBlocking { controller.performTap(meeting, firstTappedAt) }
        val afterFirst = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals("after the first work the ring is still on the cell", CellVisual.SENDING, resolveCellVisual(meeting, afterFirst.selection, afterFirst.sending))
        runBlocking { controller.performTap(meeting, secondTappedAt) }
        assertNull("after the second work the ring is cleared", KeyValueWidgetSnapshotRepository(store).read().sending)
    }

    @Test
    fun aFailedTapRemovesOnlyItsOwnEntryFromTheRing() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw IllegalStateException("unexpected") })))
        val controller = recordingController(transport, rendered)
        val tappedAt = controller.acceptTap(meeting, workEnqueued = true)
        controller.acceptTap(work, workEnqueued = false)
        val thrown = runCatching { runBlocking { controller.performTap(meeting, tappedAt) } }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        controller.discardTap(meeting, tappedAt)
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
    }

    @Test
    fun aTapAcceptedWhileAnEarlierTapIsSendingKeepsItsRingAndTheEarlierSelection() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(selectedMeetingBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val earlierTappedAt = recordingController(transport, rendered).acceptTap(meeting, workEnqueued = true)
        val first = thread { runBlocking { recordingController(transport, rendered).performTap(meeting, earlierTappedAt) } }
        try {
            assertTrue("the earlier selection request started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            recordingController(transport, rendered).acceptTap(work, workEnqueued = false)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
        val stored = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals(Selection(meeting, since), stored.selection)
        assertEquals(SelectionRequest.Select(work), stored.sending)
    }

    @Test
    fun anAcceptedTapStopsDerivingItsRingOnceOlderThanTheAgeBound() {
        val cases = listOf(
            RingAgeCase("one minute after acceptance, within the ten-minute age bound, the ring is shown", 1, SelectionRequest.Select(work)),
            RingAgeCase("eleven minutes after acceptance, beyond the ten-minute age bound, no ring is derived", 11, null),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            var now = acceptedAt
            val rendered = mutableListOf<WidgetSnapshot>()
            val transport = ForbiddenTransport()
            val controller = recordingController(transport, rendered) { now }
            controller.acceptTap(work, workEnqueued = false)
            now = acceptedAt.plusSeconds(case.minutesAfterAcceptance * 60)
            controller.render()
            assertEquals("case=${case.name}", case.expectedSending, rendered.last().sending)
            assertEquals("case=${case.name}", 0, transport.calls)
        }
    }

    @Test
    fun aRenderKeepsTheRingOfTheAcceptedTap() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered)
        controller.acceptTap(work, workEnqueued = false)
        controller.render()
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
    }

    @Test
    fun discardTapRemovesOneMatchingEntryPerCall() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        var now = acceptedAt
        val controller = recordingController(ForbiddenTransport(), mutableListOf()) { now }
        val firstAt = controller.acceptTap(meeting, workEnqueued = false)
        now = acceptedAt.plusSeconds(1)
        val secondAt = controller.acceptTap(meeting, workEnqueued = false)
        controller.discardTap(meeting, firstAt)
        assertEquals(SelectionRequest.Select(meeting), KeyValueWidgetSnapshotRepository(store).read().sending)
        controller.discardTap(meeting, secondAt)
        assertNull(KeyValueWidgetSnapshotRepository(store).read().sending)
    }

    private data class UnenqueuedAgeCase(val name: String, val secondsAfterAcceptance: Long, val expectedSending: SelectionRequest?)

    private fun awaitLastRendered(rendered: List<WidgetSnapshot>, condition: (WidgetSnapshot) -> Boolean): WidgetSnapshot {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition(rendered.last()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return rendered.last()
    }

    @Test
    fun aTapAcceptedWithWorkEnqueuedShowsTheWaitingStatusAndKeepsItsRing() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = ForbiddenTransport()
        recordingController(transport, rendered).acceptTap(work, workEnqueued = true)
        val snapshot = rendered.last()
        val status = renderedStatusText(context, snapshot)
        assertEquals(SelectionRequest.Select(work), snapshot.sending)
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
        assertEquals(0, transport.calls)
    }

    @Test
    fun theWaitingStatusGivesWayToTheSwitchingStatusWhenTheTapsPostStartsAndTheRingStays() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(selectedMeetingBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val tappedAt = recordingController(transport, rendered).acceptTap(work, workEnqueued = true)
        assertTrue("waiting before the POST starts", WAITING_FOR_NETWORK_TEXT.containsMatchIn(renderedStatusText(context, rendered.last())))
        val first = thread { runBlocking { recordingController(transport, rendered).performTap(work, tappedAt) } }
        try {
            assertTrue("the POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            val sending = awaitLastRendered(rendered) { !WAITING_FOR_NETWORK_TEXT.containsMatchIn(renderedStatusText(context, it)) }
            val status = renderedStatusText(context, sending)
            assertFalse("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
            assertTrue("status=$status", status.startsWith("Switching"))
            assertEquals(SelectionRequest.Select(work), sending.sending)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun aSendingTapKeepsItsRingPastTheTenMinuteAgeBoundWhileItsWorkRuns() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(clearedBody)
        val clock = AtomicReference(acceptedAt)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val tappedAt = recordingController(transport, rendered) { clock.get() }.acceptTap(work, workEnqueued = true)
        val first = thread { runBlocking { recordingController(transport, rendered) { clock.get() }.performTap(work, tappedAt) } }
        try {
            assertTrue("the POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            clock.set(acceptedAt.plusSeconds(11 * 60))
            recordingController(transport, rendered) { clock.get() }.render()
            assertEquals("the sending tap still holds its ring", SelectionRequest.Select(work), rendered.last().sending)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun aPendingTapShowsItsRingAndWaitingStatusWithinTheTwentyFourHourDisplayBound() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val clock = AtomicReference(acceptedAt)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { clock.get() }
        controller.acceptTap(work, workEnqueued = true)
        clock.set(acceptedAt.plusSeconds((23 * 60 + 59) * 60L))
        controller.render()
        val status = renderedStatusText(context, rendered.last())
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
    }

    @Test
    fun aPendingTapBeyondTheTwentyFourHourDisplayBoundHidesItsRingButIsStillSentWithItsAcceptedRequest() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(store).write(settings)
        val clock = AtomicReference(acceptedAt)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { clock.get() }
        val accepted = controller.acceptTap(work, workEnqueued = true)
        val input = storedTapInput(context, work, accepted, SelectionRequest.Select(work))
        clock.set(acceptedAt.plusSeconds((24 * 60 + 1) * 60L))
        controller.render()
        val hiddenStatus = renderedStatusText(context, rendered.last())
        assertNull("the ring is still shown after 24 hours", rendered.last().sending)
        assertFalse("status=$hiddenStatus", WAITING_FOR_NETWORK_TEXT.containsMatchIn(hiddenStatus))
        val sentTransport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { sentTransport }, { sentTransport }, publish = { _, _ -> }, clock = { clock.get() })
        }
        val result = try {
            TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build().startWork().get()
        } finally {
            restoreDefaultWidgetFactories()
        }
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(SELECT_WORK_BODY_AT_ACCEPTED_AT),
            sentTransport.requests.filter { it.method == "POST" }.map { it.body },
        )
    }

    @Test
    fun aTapThatWasNeverEnqueuedLosesItsRingJustAfterTheTenMinuteAgeBound() {
        val cases = listOf(
            UnenqueuedAgeCase("nine minutes fifty-nine seconds after acceptance, within the age bound, the ring is shown", 599, SelectionRequest.Select(work)),
            UnenqueuedAgeCase("ten minutes one second after acceptance, beyond the age bound, no ring is derived", 601, null),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            val clock = AtomicReference(acceptedAt)
            val rendered = mutableListOf<WidgetSnapshot>()
            val transport = ForbiddenTransport()
            val controller = recordingController(transport, rendered) { clock.get() }
            controller.acceptTap(work, workEnqueued = false)
            clock.set(acceptedAt.plusSeconds(case.secondsAfterAcceptance))
            controller.render()
            assertEquals("case=${case.name}", case.expectedSending, rendered.last().sending)
            assertEquals("case=${case.name}", 0, transport.calls)
        }
    }

    @Test
    fun theLastPendingTapOwnsTheRingWhileTheEarliestTapHeadsTheStatus() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { tappedAt }
        controller.acceptTap(meeting, workEnqueued = true)
        controller.acceptTap(work, workEnqueued = true)
        val snapshot = rendered.last()
        val status = renderedStatusText(context, snapshot)
        assertEquals(SelectionRequest.Select(work), snapshot.sending)
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
    }

    @Test
    fun theEarliestPendingTapShowsTheSwitchingStatusWhileItsPostRunsAndTheLaterTapWaitsBehindIt() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(clearedBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        recordingController(transport, rendered) { tappedAt }.acceptTap(meeting, workEnqueued = true)
        recordingController(transport, rendered) { tappedAt }.acceptTap(work, workEnqueued = true)
        val first = thread { runBlocking { recordingController(transport, rendered) { tappedAt }.performTap(meeting, tappedAt) } }
        try {
            assertTrue("the earliest tap's POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            val sending = awaitLastRendered(rendered) { !WAITING_FOR_NETWORK_TEXT.containsMatchIn(renderedStatusText(context, it)) }
            val status = renderedStatusText(context, sending)
            assertTrue("status=$status", status.startsWith("Switching to project-a / meeting"))
            assertEquals(SelectionRequest.Select(work), sending.sending)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun whenTheEarliestPendingTapCompletesTheLaterTapBecomesTheHeadAndTheFirstRingIsGone() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(clearedBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        recordingController(transport, rendered) { tappedAt }.acceptTap(meeting, workEnqueued = true)
        recordingController(transport, rendered) { tappedAt }.acceptTap(work, workEnqueued = true)
        val first = thread { runBlocking { recordingController(transport, rendered) { tappedAt }.performTap(meeting, tappedAt) } }
        try {
            assertTrue("the earliest tap's POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertEquals(SelectionRequest.Select(work), last.sending)
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
        assertFalse("the completed tap's ring is gone", renderedCellDrawableResId(context, last, meeting) == R.drawable.cell_sending)
    }

    @Test
    fun aTapFixesItsRequestAtAcceptanceSoAChangedSelectionDoesNotChangeTheSentBody() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val projectAWork = CellKey("project-a", ActivityKind.WORK)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(projectAWork, since), since, WidgetOutcome.Settled, null, null))
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        val controller = recordingController(transport, mutableListOf()) { tappedAt }
        val accepted = controller.acceptTap(projectAWork, workEnqueued = true)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, null, null))
        runBlocking { controller.performTap(projectAWork, accepted) }
        assertEquals(listOf(CLEAR_BODY_AT_TAPPED_AT), transport.requests.filter { it.method == "POST" }.map { it.body })
    }

    @Test
    fun twoPendingTapsOnTheSameCellKeepTheLaterEntryWhenTheEarlierOneCompletes() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        var now = tappedAt
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        val controller = recordingController(transport, rendered) { now }
        controller.acceptTap(meeting, workEnqueued = true)
        now = tappedAt.plusSeconds(1)
        controller.acceptTap(meeting, workEnqueued = true)
        runBlocking { controller.performTap(meeting, tappedAt) }
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertEquals(SelectionRequest.Select(meeting), last.sending)
        assertEquals(R.drawable.cell_sending, renderedCellDrawableResId(context, last, meeting))
        assertTrue("status=$status", WAITING_FOR_NETWORK_TEXT.containsMatchIn(status))
    }
}

private const val CLEAR_BODY_AT_TAPPED_AT = """{"project":null,"activity":null,"tappedAt":"2026-11-09T02:19:03.112Z"}"""

private const val SELECT_WORK_BODY_AT_ACCEPTED_AT = """{"project":"project-b","activity":"work","tappedAt":"2026-11-07T08:00:00.000Z"}"""
