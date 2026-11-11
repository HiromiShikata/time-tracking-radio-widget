package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Data
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
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
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
import java.net.MalformedURLException
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

    private fun runStoredTap(
        input: Data,
        transport: HttpTransport,
        publish: (WidgetSettings?, WidgetSnapshot) -> Unit = { _, _ -> },
        clock: () -> Instant = { Instant.now() },
    ): ListenableWorker.Result {
        WidgetSelectionWorker.controllerFactory = {
            WidgetController(it, { transport }, { transport }, publish = publish, clock = clock)
        }
        return try {
            TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build().startWork().get()
        } finally {
            restoreDefaultWidgetFactories()
        }
    }

    private data class RingAgeCase(val name: String, val minutesAfterAcceptance: Long, val expectedSending: SelectionRequest?)

    private data class AcceptTapCase(
        val name: String,
        val previous: Selection?,
        val tapped: CellKey,
        val expectedSending: SelectionRequest,
    )

    private data class ClearCase(val name: String, val acceptedCell: CellKey?, val expectedSending: SelectionRequest?)

    private data class ProjectedTapCase(
        val name: String,
        val confirmed: Selection?,
        val earlierPendingTaps: List<CellKey>,
        val tapped: CellKey,
        val expectedRequest: SelectionRequest,
    )

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
        val accepted = controller.acceptQueued(meeting)
        runStoredTap(storedTapInput(context, accepted.workRequest), transport)
        assertEquals(emptyList<HttpRequest>(), transport.requests)
        assertEquals(WidgetSnapshot.EMPTY, KeyValueWidgetSnapshotRepository(store).read())
    }

    @Test
    fun performTapStoresTheAcceptedSelectionAndClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val body = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        val controller = controller(transport)
        val accepted = controller.acceptQueued(meeting)
        runStoredTap(storedTapInput(context, accepted.workRequest), transport) { tappedAt }
        val stored = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals(
            WidgetSnapshot(Selection(meeting, since), stored.fetchedAt, WidgetOutcome.Settled, null, null, savedAt = tappedAt, roundTripMillis = 0L),
            stored,
        )
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
        val accepted = controller.acceptQueued(meeting)
        rendered.clear()
        val result = runStoredTap(storedTapInput(context, accepted.workRequest), transport, publish = { _, snapshot -> rendered += snapshot })
        controller.discardTap(meeting, accepted.accepted.tappedAt)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
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
        val accepted = controller.acceptQueued(meeting)
        runStoredTap(storedTapInput(context, accepted.workRequest), transport)
        assertEquals(listOf("POST"), transport.requests.map { it.method })
        assertFalse(transport.requests.any { it.body.orEmpty().startsWith("{\"diagnostics\":[") })
        val tapEntry = KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }
        assertTrue(Regex("tapped_at=.+, project=project-a, activity=meeting, request=select, queued_taps=1, work_id=.+").matches(tapEntry.detail))
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
        val accepted = controller.acceptQueued(meeting)
        val result = runStoredTap(storedTapInput(context, accepted.workRequest), transport)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
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
        assertTrue(Regex("tapped_at=.+, project=project-a, activity=meeting, request=select, queued_taps=0, work_id=none").matches(entry.detail))
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
            val tappedAt = if (accepted != null) controller.acceptTap(accepted, workEnqueued = false).tappedAt else since
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
        val firstController = WidgetController(context, { transport }, { transport }, publish)
        val firstAccepted = firstController.acceptQueued(meeting)
        val firstInput = storedTapInput(context, firstAccepted.workRequest)
        val first = thread { runStoredTap(firstInput, transport, publish) }
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
        val firstController = WidgetController(context, { transport }, { transport }, publish)
        val firstAccepted = firstController.acceptQueued(meeting)
        val firstInput = storedTapInput(context, firstAccepted.workRequest)
        val first = thread { runStoredTap(firstInput, transport, publish) }
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
        val firstAccepted = controller.acceptQueued(meeting)
        val secondAccepted = controller.acceptQueued(meeting)
        runStoredTap(storedTapInput(context, firstAccepted.workRequest), transport)
        val afterFirst = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals("after the first work the cell is still shown as selected", R.drawable.cell_selected, renderedCellDrawableResId(context, afterFirst, meeting))
        runStoredTap(storedTapInput(context, secondAccepted.workRequest), transport)
        assertNull("after the second work the ring is cleared", KeyValueWidgetSnapshotRepository(store).read().sending)
    }

    @Test
    fun aFailedTapRemovesOnlyItsOwnEntryFromTheRing() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw IllegalStateException("unexpected") })))
        val controller = recordingController(transport, rendered)
        val accepted = controller.acceptQueued(meeting)
        controller.acceptTap(work, workEnqueued = false)
        val result = runStoredTap(storedTapInput(context, accepted.workRequest), transport, publish = { _, snapshot -> rendered += snapshot })
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        controller.discardTap(meeting, accepted.accepted.tappedAt)
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
    }

    @Test
    fun aTapAcceptedWhileAnEarlierTapIsSendingKeepsItsRingAndTheEarlierSelection() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(selectedMeetingBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val earlierController = recordingController(transport, rendered)
        val earlierAccepted = earlierController.acceptQueued(meeting)
        val earlierInput = storedTapInput(context, earlierAccepted.workRequest)
        val first = thread { runStoredTap(earlierInput, transport, publish = { _, snapshot -> rendered += snapshot }) }
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
        val firstAt = controller.acceptTap(meeting, workEnqueued = false).tappedAt
        now = acceptedAt.plusSeconds(1)
        val secondAt = controller.acceptTap(meeting, workEnqueued = false).tappedAt
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
    fun aTapAcceptedWithWorkEnqueuedShowsTheSavingStatusAndProjectsItsSelection() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = ForbiddenTransport()
        recordingController(transport, rendered).acceptQueued(work)
        val snapshot = rendered.last()
        val status = renderedStatusText(context, snapshot)
        assertEquals(SelectionRequest.Select(work), snapshot.sending)
        assertEquals(SelectionRequest.Select(work), KeyValueWidgetSnapshotRepository(store).read().sending)
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, snapshot, work))
        assertTrue("status=$status", SAVING_TAPPED_LINE.matches(stateLineOf(status)))
        assertEquals(0, transport.calls)
    }

    @Test
    fun theSavingStatusShowsTheSendTimeOnceTheTapsPostStartsAndTheProjectionStays() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(selectedMeetingBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val acceptedClock = tappedAt.minusSeconds(60)
        val workController = recordingController(transport, rendered) { acceptedClock }
        val accepted = workController.acceptQueued(work)
        assertEquals(
            "before the POST starts the status shows the tap time",
            "Saving… tapped 02:18:03",
            stateLineOf(renderedStatusText(context, rendered.last())),
        )
        val input = storedTapInput(context, accepted.workRequest)
        val first = thread { runStoredTap(input, transport, publish = { _, snapshot -> rendered += snapshot }) { tappedAt } }
        try {
            assertTrue("the POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            val sending = awaitLastRendered(rendered) { stateLineOf(renderedStatusText(context, it)) == "Saving… sent 02:19:03" }
            assertEquals("the status shows the send time", "Saving… sent 02:19:03", stateLineOf(renderedStatusText(context, sending)))
            assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, sending, work))
            assertEquals(SelectionRequest.Select(work), sending.sending)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun theSavingStatusReadsTappedUntilSendingStartsThenSentAtTheSendStartTime() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(selectedMeetingBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val sendingStartedAt = tappedAt.plusMillis(1_500)
        val accepted = recordingController(transport, rendered) { tappedAt }.acceptQueued(work)
        assertEquals(
            "after the tap and before the POST starts the status shows the tap time",
            "Saving… tapped 02:19:03",
            stateLineOf(renderedStatusText(context, rendered.last())),
        )
        val input = storedTapInput(context, accepted.workRequest)
        val first = thread { runStoredTap(input, transport, publish = { _, snapshot -> rendered += snapshot }) { sendingStartedAt } }
        try {
            assertTrue("the POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            val sending = awaitLastRendered(rendered) { stateLineOf(renderedStatusText(context, it)) == "Saving… sent 02:19:04" }
            assertEquals(
                "once the POST started the status shows the send start time",
                "Saving… sent 02:19:04",
                stateLineOf(renderedStatusText(context, sending)),
            )
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
        val workController = recordingController(transport, rendered) { clock.get() }
        val accepted = workController.acceptQueued(work)
        val input = storedTapInput(context, accepted.workRequest)
        val first = thread { runStoredTap(input, transport, { _, snapshot -> rendered += snapshot }) { clock.get() } }
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
    fun aPendingTapShowsItsProjectionAndSavingStatusWithinTheTwentyFourHourDisplayBound() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val clock = AtomicReference(acceptedAt)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { clock.get() }
        controller.acceptQueued(work)
        clock.set(acceptedAt.plusSeconds((23 * 60 + 59) * 60L))
        controller.render()
        val status = renderedStatusText(context, rendered.last())
        assertEquals(SelectionRequest.Select(work), rendered.last().sending)
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, rendered.last(), work))
        assertEquals("status=$status", "Saving… tapped 08:00:00", stateLineOf(status))
    }

    @Test
    fun aPendingTapBeyondTheTwentyFourHourDisplayBoundHidesItsRingButIsStillSentWithItsAcceptedRequest() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(store).write(settings)
        val clock = AtomicReference(acceptedAt)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { clock.get() }
        val accepted = controller.acceptQueued(work)
        val input = storedTapInput(context, accepted.workRequest)
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
    fun aPendingTapOlderThanTheTwentyFourHourDisplayBoundStillCountsForTheProjectedSelection() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(store).write(settings)
        val clock = AtomicReference(acceptedAt)
        val controller = recordingController(ForbiddenTransport(), mutableListOf()) { clock.get() }
        val older = controller.acceptQueued(meeting)
        val olderInput = storedTapInput(context, older.workRequest)
        clock.set(acceptedAt.plusSeconds(25 * 60 * 60L))
        val later = controller.acceptQueued(meeting)
        assertEquals(SelectionRequest.Clear, later.accepted.request)
        val sentTransport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        val result = try {
            WidgetSelectionWorker.controllerFactory = {
                WidgetController(it, { sentTransport }, { sentTransport }, publish = { _, _ -> }, clock = { clock.get() })
            }
            TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(olderInput).build().startWork().get()
        } finally {
            restoreDefaultWidgetFactories()
        }
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(SELECT_MEETING_BODY_AT_ACCEPTED_AT),
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
    fun theLastPendingTapOwnsTheProjectionWhileTheEarliestTapHeadsTheStatus() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val rendered = mutableListOf<WidgetSnapshot>()
        val controller = recordingController(ForbiddenTransport(), rendered) { tappedAt }
        controller.acceptQueued(meeting)
        controller.acceptQueued(work)
        val snapshot = rendered.last()
        val status = renderedStatusText(context, snapshot)
        assertEquals(SelectionRequest.Select(work), snapshot.sending)
        assertEquals(R.drawable.cell_selected, renderedCellDrawableResId(context, snapshot, work))
        assertEquals(R.drawable.cell_unselected, renderedCellDrawableResId(context, snapshot, meeting))
        assertEquals("status=$status", "Saving… tapped 02:19:03", stateLineOf(status))
    }

    @Test
    fun theEarliestPendingTapShowsTheSavingStatusWhileItsPostRunsAndTheLaterTapWaitsBehindIt() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(clearedBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val meetingController = recordingController(transport, rendered) { tappedAt }
        val meetingAccepted = meetingController.acceptQueued(meeting)
        recordingController(transport, rendered) { tappedAt }.acceptQueued(work)
        val meetingInput = storedTapInput(context, meetingAccepted.workRequest)
        val first = thread { runStoredTap(meetingInput, transport, { _, snapshot -> rendered += snapshot }) { tappedAt } }
        try {
            assertTrue("the earliest tap's POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            val sending = awaitLastRendered(rendered) { !WAITING_FOR_NETWORK_TEXT.containsMatchIn(renderedStatusText(context, it)) }
            val status = renderedStatusText(context, sending)
            assertEquals("status=$status", "Saving… sent 02:19:03", stateLineOf(status))
            assertEquals(SelectionRequest.Select(work), sending.sending)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }

    @Test
    fun whenTheEarliestPendingTapCompletesTheLaterTapBecomesTheHeadAndTheFirstProjectionIsGone() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = GatedSelectionTransport(clearedBody)
        val rendered = CopyOnWriteArrayList<WidgetSnapshot>()
        val meetingController = recordingController(transport, rendered) { tappedAt }
        val meetingAccepted = meetingController.acceptQueued(meeting)
        recordingController(transport, rendered) { tappedAt }.acceptQueued(work)
        val meetingInput = storedTapInput(context, meetingAccepted.workRequest)
        val first = thread { runStoredTap(meetingInput, transport, { _, snapshot -> rendered += snapshot }) { tappedAt } }
        try {
            assertTrue("the earliest tap's POST started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertEquals(SelectionRequest.Select(work), last.sending)
        assertEquals("status=$status", "Saving… tapped 02:19:03", stateLineOf(status))
        assertEquals("the completed tap's cell is no longer projected", R.drawable.cell_unselected, renderedCellDrawableResId(context, last, meeting))
        assertEquals("the later tap is projected", R.drawable.cell_selected, renderedCellDrawableResId(context, last, work))
    }

    @Test
    fun aTapFixesItsRequestAtAcceptanceSoAChangedSelectionDoesNotChangeTheSentBody() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val projectAWork = CellKey("project-a", ActivityKind.WORK)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, null, null))
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, clearedBody) })))
        val controller = recordingController(transport, mutableListOf()) { tappedAt }
        val accepted = controller.acceptQueued(projectAWork)
        val input = storedTapInput(context, accepted.workRequest)
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(projectAWork, since), since, WidgetOutcome.Settled, null, null))
        val result = runStoredTap(input, transport)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(listOf(SELECT_PROJECT_A_WORK_BODY_AT_TAPPED_AT), transport.requests.filter { it.method == "POST" }.map { it.body })
    }

    @Test
    fun twoPendingTapsOnTheSameCellKeepTheLaterEntryWhenTheEarlierOneCompletes() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        var now = tappedAt
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ HttpResponse(200, null, selectedMeetingBody) })))
        val controller = recordingController(transport, rendered) { now }
        val firstAccepted = controller.acceptQueued(meeting)
        now = tappedAt.plusSeconds(1)
        controller.acceptQueued(meeting)
        runStoredTap(storedTapInput(context, firstAccepted.workRequest), transport, { _, snapshot -> rendered += snapshot }) { now }
        val last = rendered.last()
        val status = renderedStatusText(context, last)
        assertEquals(SelectionRequest.Clear, last.sending)
        assertEquals("the pending Clear projects no selection on the meeting cell", R.drawable.cell_unselected, renderedCellDrawableResId(context, last, meeting))
        assertEquals("status=$status", "Saving… tapped 02:19:04", stateLineOf(status))
    }

    @Test
    fun aTapAfterAnEarlierTapOnAnotherCellIsResolvedAgainstTheEarlierTapNotTheConfirmedSelection() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val projectX = CellKey("project-a", ActivityKind.WORK)
        val projectY = work
        KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(projectX, since), since, WidgetOutcome.Settled, null, null))
        val controller = recordingController(ForbiddenTransport(), mutableListOf()) { tappedAt }
        val toY = controller.acceptQueued(projectY)
        val toX = controller.acceptQueued(projectX)
        assertEquals("the first tap on the other cell selects it", SelectionRequest.Select(projectY), toY.accepted.request)
        assertEquals("the tap on the confirmed cell selects it, because the earlier pending tap projects the other cell", SelectionRequest.Select(projectX), toX.accepted.request)
    }

    @Test
    fun twoTapsOnOneUnselectedCellResolveTheFirstToSelectAndTheSecondToClear() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val controller = recordingController(ForbiddenTransport(), mutableListOf()) { tappedAt }
        val first = controller.acceptQueued(meeting)
        val second = controller.acceptQueued(meeting)
        assertEquals("the first tap selects the unselected cell", SelectionRequest.Select(meeting), first.accepted.request)
        assertEquals("the second tap on the same cell clears it, because the first pending tap projects it selected", SelectionRequest.Clear, second.accepted.request)
    }

    @Test
    fun aTapsRequestIsResolvedAgainstTheSelectionProjectedFromTheEarlierPendingTaps() {
        val cases = listOf(
            ProjectedTapCase(
                "nothing is pending and the tapped cell is confirmed, so the tap clears it",
                Selection(meeting, since),
                emptyList(),
                meeting,
                SelectionRequest.Clear,
            ),
            ProjectedTapCase(
                "a pending clear makes the confirmed cell unselected, so the tap on it selects it",
                Selection(meeting, since),
                listOf(meeting),
                meeting,
                SelectionRequest.Select(meeting),
            ),
            ProjectedTapCase(
                "a pending select projects another cell, so the tap on the confirmed cell selects it",
                Selection(work, since),
                listOf(meeting),
                work,
                SelectionRequest.Select(work),
            ),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(case.confirmed, since, WidgetOutcome.Settled, null, null))
            val controller = recordingController(ForbiddenTransport(), mutableListOf()) { tappedAt }
            case.earlierPendingTaps.forEach { controller.acceptQueued(it) }
            val accepted = controller.acceptQueued(case.tapped)
            assertEquals("case=${case.name}", case.expectedRequest, accepted.accepted.request)
        }
    }

    private data class TerminalTapCase(val name: String, val transportFailure: Throwable?, val runsThroughWorker: Boolean)

    @Test
    fun aStampedTapWhoseSettingsAreAbsentWhenItsWorkRunsStopsProjectingItsRequest() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = ForbiddenTransport()
        val accepted = recordingController(transport, mutableListOf()).acceptQueued(meeting)
        val input = storedTapInput(context, accepted.workRequest)
        store.write(WIDGET_ENDPOINT_PREFERENCE_KEY, null)
        val result = runStoredTap(input, transport)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals("no network call is made without settings", 0, transport.calls)
        KeyValueWidgetSettingsRepository(store).write(settings)
        val probe = recordingController(ForbiddenTransport(), mutableListOf()).acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Select(meeting), probe.request)
    }

    @Test
    fun everyTerminalPathOfAStampedTapRemovesItsEntryAndTheSameCellSelectsAgain() {
        val cases = listOf(
            TerminalTapCase("a final failure", MalformedURLException("bad endpoint"), runsThroughWorker = true),
            TerminalTapCase("a success", null, runsThroughWorker = true),
            TerminalTapCase("an Error that is not an Exception", AssertionError("boom"), runsThroughWorker = false),
        )
        cases.forEach { case ->
            context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            KeyValueWidgetSettingsRepository(store).write(settings)
            val respond: () -> HttpResponse = {
                val failure = case.transportFailure
                if (failure != null) throw failure
                HttpResponse(200, null, clearedBody)
            }
            val transport = FakeHttpTransport(ArrayDeque(listOf(respond)))
            val controller = recordingController(transport, mutableListOf()) { tappedAt }
            val accepted = controller.acceptQueued(meeting)
            if (case.runsThroughWorker) {
                val input = storedTapInput(context, accepted.workRequest)
                val result = runStoredTap(input, transport)
                assertTrue("case=${case.name} result=$result", result is ListenableWorker.Result.Success)
            } else {
                val thrown = try {
                    runBlocking { controller.performTap(meeting, accepted.accepted.request, accepted.accepted.tappedAt) }
                    null
                } catch (error: AssertionError) {
                    error
                }
                assertEquals("case=${case.name}", "boom", thrown?.message)
            }
            val probe = controller.acceptTap(meeting, workEnqueued = false)
            assertEquals("case=${case.name}", SelectionRequest.Select(meeting), probe.request)
        }
    }

    @Test
    fun aTapWhoseNetworkAttemptFailsKeepsItsEntryAndTheSameCellStillClears() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw IOException("unreachable") })))
        val controller = recordingController(transport, mutableListOf()) { tappedAt }
        val accepted = controller.acceptQueued(meeting)
        val result = runStoredTap(storedTapInput(context, accepted.workRequest), transport)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        val probe = controller.acceptTap(meeting, workEnqueued = false)
        assertEquals(SelectionRequest.Clear, probe.request)
    }
}

private const val WIDGET_ENDPOINT_PREFERENCE_KEY = "endpoint_url"

private const val SELECT_PROJECT_A_WORK_BODY_AT_TAPPED_AT = """{"project":"project-a","activity":"work","tappedAt":"2026-11-09T02:19:03.112Z"}"""

private const val SELECT_WORK_BODY_AT_ACCEPTED_AT = """{"project":"project-b","activity":"work","tappedAt":"2026-11-07T08:00:00.000Z"}"""

private const val SELECT_MEETING_BODY_AT_ACCEPTED_AT = """{"project":"project-a","activity":"meeting","tappedAt":"2026-11-07T08:00:00.000Z"}"""
