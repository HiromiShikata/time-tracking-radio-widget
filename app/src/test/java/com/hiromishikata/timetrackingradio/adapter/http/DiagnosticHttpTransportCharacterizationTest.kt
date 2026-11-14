package com.hiromishikata.timetrackingradio.adapter.http

import android.content.Context
import android.content.ContextWrapper
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.widget.WIDGET_TEST_SETTINGS
import com.hiromishikata.timetrackingradio.adapter.widget.WidgetController
import com.hiromishikata.timetrackingradio.adapter.widget.widgetDiagnosticLog
import com.hiromishikata.timetrackingradio.adapter.widget.widgetPreferences
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.DiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.FailurePhase
import com.hiromishikata.timetrackingradio.domain.TransportPhaseFailure
import com.hiromishikata.timetrackingradio.usecase.FakeDiagnosticLogRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

private enum class WidgetPathServiceFailure { MISSING, THROWS }

/** Replaces one system service with null or a throwing lookup; every other service is the real one. */
private class FailingServiceContext(
    base: Context,
    private val service: String,
    private val failure: WidgetPathServiceFailure,
) : ContextWrapper(base) {
    override fun getSystemService(name: String): Any? {
        if (name != service) return super.getSystemService(name)
        return when (failure) {
            WidgetPathServiceFailure.MISSING -> null
            WidgetPathServiceFailure.THROWS -> throw SecurityException("denied for test")
        }
    }
}

/**
 * Characterizes the exception and http_response events as the DiagnosticRecorder formats them directly, and the
 * exception events the widget path records through the production wiring, which appends the device state snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class DiagnosticHttpTransportCharacterizationTest {
    private val endpoint = "https://example.test/endpoint?key=alpha-secret"

    private fun eventsOf(type: DiagnosticEventType, delegate: HttpTransport): List<String> {
        val log = FakeDiagnosticLogRepository()
        val transport = DiagnosticHttpTransport(delegate, endpoint, DiagnosticRecorder(log, setOf("alpha-secret")) { Instant.EPOCH })
        runCatching { transport.send(HttpRequest("POST", endpoint, "{}")) }
        assertFalse(log.recorded.any { it.detail.contains("alpha-secret") })
        return log.recorded.filter { it.type == type }.map { it.detail }
    }

    /** Sends a refresh through WidgetController, which builds the production recorder, and returns the exception details it recorded. */
    private fun exceptionDetailsFromWidgetPath(context: Context, failure: Throwable): List<String> {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val log = widgetDiagnosticLog(context)
        val lastIdBefore = log.readAll().maxOfOrNull { it.id } ?: 0L
        val transport = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw failure })))
        val controller = WidgetController(context, { transport }, { transport }, publish = { _, _ -> }, clock = { Instant.EPOCH })
        runCatching { runBlocking { controller.refresh() } }
        return log.readAll().filter { it.id > lastIdBefore && it.type == DiagnosticEventType.EXCEPTION }.map { it.detail }
    }

    @Test
    fun exceptionEventNamesTheFailureClassMessagePhaseAndElapsedTime() {
        val cases = listOf(
            IOException("unreachable") to "IOException: unreachable, phase=unknown, elapsed_ms=",
            TransportPhaseFailure(FailurePhase.CONNECTING, IOException("unreachable")) to
                "IOException: unreachable, phase=connecting, elapsed_ms=",
        )
        cases.forEach { (failure, expectedPrefix) ->
            val delegate = FakeHttpTransport(ArrayDeque<() -> HttpResponse>(listOf({ throw failure })))
            val details = eventsOf(DiagnosticEventType.EXCEPTION, delegate)
            assertTrue("case=$expectedPrefix, details=$details", details.size == 1)
            assertTrue("case=$expectedPrefix, detail=${details.single()}", Regex(Regex.escape(expectedPrefix) + """\d+""").matches(details.single()))
        }
    }

    @Test
    fun productionWidgetPathExceptionEventEndsWithTheDeviceStateSnapshot() {
        val cases = listOf(
            IOException("unreachable") to "IOException: unreachable, phase=unknown, elapsed_ms=",
            TransportPhaseFailure(FailurePhase.CONNECTING, IOException("unreachable")) to
                "IOException: unreachable, phase=connecting, elapsed_ms=",
        )
        cases.forEach { (failure, expectedPrefix) ->
            val details = exceptionDetailsFromWidgetPath(RuntimeEnvironment.getApplication(), failure)
            assertTrue("case=$expectedPrefix, details=$details", details.isNotEmpty())
            details.forEach { detail ->
                val snapshotTail = Regex(
                    Regex.escape(expectedPrefix) + """\d+, network_present=.+, process_importance=(foreground|visible|service|background|unknown)$""",
                )
                assertTrue("case=$expectedPrefix, detail=$detail", snapshotTail.matches(detail))
            }
        }
    }

    @Test
    fun productionWidgetPathStillRecordsTheExceptionWhenASnapshotServiceIsUnavailable() {
        val expectedUnknownFields = listOf(
            Triple("connectivity", Context.CONNECTIVITY_SERVICE, "network_present=unknown, transports=unknown"),
            Triple("power", Context.POWER_SERVICE, "device_idle_mode=unknown, light_idle_mode=unknown"),
            Triple("usage stats", Context.USAGE_STATS_SERVICE, "standby_bucket=unknown"),
            Triple("activity", Context.ACTIVITY_SERVICE, "background_restricted=unknown, process_importance=unknown"),
        )
        val failures = listOf(WidgetPathServiceFailure.MISSING, WidgetPathServiceFailure.THROWS)
        expectedUnknownFields.forEach { (label, service, expectedFragment) ->
            failures.forEach { failure ->
                val context = FailingServiceContext(RuntimeEnvironment.getApplication(), service, failure)
                val details = exceptionDetailsFromWidgetPath(context, IOException("unreachable"))
                val caseLabel = "service=$label failure=$failure"
                assertTrue("$caseLabel details=$details", details.isNotEmpty())
                details.forEach { detail ->
                    assertTrue("$caseLabel detail=$detail", detail.startsWith("IOException: unreachable, phase=unknown, elapsed_ms="))
                    assertTrue("$caseLabel expected=$expectedFragment detail=$detail", detail.contains(expectedFragment))
                    assertTrue("$caseLabel detail=$detail", detail.contains("process_importance="))
                }
            }
        }
    }

    @Test
    fun httpResponseEventNamesStatusAndElapsedTime() {
        val delegate = FakeHttpTransport(ArrayDeque(listOf({ HttpResponse(200, null, "{}") })))
        val details = eventsOf(DiagnosticEventType.HTTP_RESPONSE, delegate)
        assertTrue("details=$details", details.size == 1)
        assertTrue("detail=${details.single()}", Regex("""status=200, elapsed_ms=\d+""").matches(details.single()))
    }
}
