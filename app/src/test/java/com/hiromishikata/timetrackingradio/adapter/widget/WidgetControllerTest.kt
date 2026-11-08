package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val settings = WidgetSettings("https://example.com/exec?key=example", listOf("project-a", "project-b"))
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")

    private fun controller(transport: FakeHttpTransport) = WidgetController(context, { transport }, { transport }) { _, _ -> }

    private data class AcceptTapCase(
        val name: String,
        val previous: Selection?,
        val tapped: CellKey,
        val expectedSending: SelectionRequest,
    )

    private data class ClearCase(val name: String, val sending: SelectionRequest?)

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
        runBlocking { controller(transport).performTap(meeting) }
        assertEquals(emptyList<HttpRequest>(), transport.requests)
        assertEquals(WidgetSnapshot.EMPTY, KeyValueWidgetSnapshotRepository(store).read())
    }

    @Test
    fun performTapStoresTheAcceptedSelectionAndClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val body = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        runBlocking { controller(transport).performTap(meeting) }
        val stored = KeyValueWidgetSnapshotRepository(store).read()
        assertEquals(WidgetSnapshot(Selection(meeting, since), stored.fetchedAt, WidgetOutcome.Settled, null), stored)
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
        val controller = WidgetController(context, { transport }, { transport }) { _, snapshot -> rendered += snapshot }
        val thrown = runCatching { runBlocking { controller.performTap(meeting) } }.exceptionOrNull()
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
        controller.acceptTap(meeting)
        runBlocking { controller.performTap(meeting) }
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
        controller.acceptTap(meeting)
        val thrown = runCatching { runBlocking { controller.performTap(meeting) } }.exceptionOrNull()
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
        controller(transport).acceptTap(meeting)
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
        WidgetController(context, { transport }, { transport }) { _, _ -> }.acceptTap(meeting)
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
            KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(case.previous, since, WidgetOutcome.Settled, null))
            val transport = ForbiddenTransport()
            val rendered = mutableListOf<WidgetSnapshot>()
            WidgetController(context, { transport }, { transport }) { _, snapshot -> rendered += snapshot }.acceptTap(case.tapped)
            val stored = KeyValueWidgetSnapshotRepository(store).read()
            assertEquals("case=${case.name}", 0, transport.calls)
            assertEquals("case=${case.name}", case.expectedSending, stored.sending)
            assertEquals("case=${case.name}", case.expectedSending, rendered.last().sending)
            assertEquals("case=${case.name}", case.previous, stored.selection)
        }
    }

    @Test
    fun clearSendingClearsTheSendingStateAndRendersItIdempotently() {
        val cases = listOf(
            ClearCase("nothing was sending", null),
            ClearCase("a selection was sending", SelectionRequest.Select(meeting)),
            ClearCase("a clear was sending", SelectionRequest.Clear),
        )
        cases.forEach { case ->
            KeyValueWidgetSnapshotRepository(store).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, case.sending))
            val transport = ForbiddenTransport()
            val rendered = mutableListOf<WidgetSnapshot>()
            val controller = WidgetController(context, { transport }, { transport }) { _, snapshot -> rendered += snapshot }
            controller.clearSending()
            controller.clearSending()
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
            controller.acceptTap(meeting)
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
        val first = thread { runBlocking { WidgetController(context, { transport }, { transport }, publish).performTap(meeting) } }
        try {
            assertTrue("the first selection request started", transport.selectionStarted.await(5, TimeUnit.SECONDS))
            WidgetController(context, { transport }, { transport }, publish).acceptTap(nextCell)
            val rendered = nextTapRendered.await(2, TimeUnit.SECONDS)
            assertTrue("the next tap rendered its sending state while the selection request was still blocked", rendered)
        } finally {
            transport.releaseSelection.countDown()
            first.join(5_000)
        }
    }
}
