package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpRequest
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val settings = WidgetSettings("https://example.com/exec?key=example", listOf("project-a", "project-b"))
    private val meeting = CellKey("project-a", ActivityKind.MEETING)

    private fun controller(transport: FakeHttpTransport) = WidgetController(context, { transport }) { _, _ -> }

    @Test
    fun tapWithoutSettingsDoesNothing() {
        val transport = FakeHttpTransport(ArrayDeque())
        runBlocking { controller(transport).tap(meeting) }
        assertEquals(emptyList<HttpRequest>(), transport.requests)
        assertEquals(WidgetSnapshot.EMPTY, KeyValueWidgetSnapshotRepository(store).read())
    }

    @Test
    fun tapStoresTheAcceptedSelectionAndClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val since = Instant.parse("2026-11-07T08:39:00Z")
        val body = """{"result":"ok","selection":{"project":"project-a","activity":"meeting","since":"2026-11-07T08:39:00Z"}}"""
        val transport = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, body) })))
        runBlocking { controller(transport).tap(meeting) }
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
        val controller = WidgetController(context, { transport }) { _, snapshot -> rendered += snapshot }
        val thrown = runCatching { runBlocking { controller.tap(meeting) } }.exceptionOrNull()
        assertTrue("thrown=$thrown", thrown is IllegalStateException)
        assertTrue("the dashed ring was never rendered", rendered.any { it.sending != null })
        assertNull("the last rendered screen still shows a sending cell", rendered.last().sending)
    }

    @Test
    fun aTapRecordsItsEventAndUploadsTheDiagnosticsAfterwards() {
        KeyValueWidgetSettingsRepository(store).write(settings)
        val selection = """{"result":"ok","selection":null}"""
        val transport = FakeHttpTransport(
            ArrayDeque(listOf({ HttpResponse(200, null, selection) }, { HttpResponse(200, null, """{"result":"ok","accepted":3}""") })),
        )
        runBlocking { controller(transport).tap(meeting) }
        val upload = transport.requests.last()
        assertEquals("POST", upload.method)
        assertTrue(upload.body.orEmpty().startsWith("{\"diagnostics\":["))
        assertTrue(upload.body.orEmpty().contains("\"detail\":\"project=project-a, activity=meeting\""))
        val tapEntry = KeyValueDiagnosticLogRepository(store).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }
        assertTrue(tapEntry.delivered)
    }

    @Test
    fun aTapWithoutSettingsRecordsTheTapAndSendsNothing() {
        val transport = FakeHttpTransport(ArrayDeque())
        runBlocking { controller(transport).tap(meeting) }
        assertEquals(listOf("tap_received"), KeyValueDiagnosticLogRepository(store).readAll().map { it.type.code })
        assertEquals(emptyList<HttpRequest>(), transport.requests)
    }

    @Test
    fun refreshRecordsTheUpdateRequest() {
        val transport = FakeHttpTransport(ArrayDeque())
        runBlocking { controller(transport).refresh() }
        assertEquals(listOf("widget_update_requested"), KeyValueDiagnosticLogRepository(store).readAll().map { it.type.code })
    }
}
