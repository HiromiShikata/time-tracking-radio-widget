package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetDiagnosticsUploadWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
    }

    @Test
    fun aPendingEntryIsUploadedAndTheWorkSucceeds() {
        val transport = EchoSelectionTransport()
        WidgetDiagnosticsUploadWorker.controllerFactory = { WidgetController(it, { transport }, { transport }) { _, _ -> } }
        WidgetController(context, { transport }, { transport }) { _, _ -> }.acceptTap(meeting)
        val result = TestListenableWorkerBuilder<WidgetDiagnosticsUploadWorker>(context).build().startWork().get()
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertEquals(listOf("POST"), transport.requests.map { it.method })
        assertTrue(widgetDiagnosticLog(context).readAll().single { it.type == DiagnosticEventType.TAP_RECEIVED }.delivered)
    }

    @Test
    fun anUploadThatThrowsFailsTheWorkAndLeavesTheSelectionUnchanged() {
        val before = WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, null)
        widgetSnapshots(context).write(before)
        val rendered = mutableListOf<WidgetSnapshot>()
        val transport = EchoSelectionTransport()
        WidgetDiagnosticsUploadWorker.controllerFactory = {
            WidgetController(it, { transport }, { throw IllegalStateException("upload transport unavailable") }) { _, snapshot ->
                rendered += snapshot
            }
        }
        val result = TestListenableWorkerBuilder<WidgetDiagnosticsUploadWorker>(context).build().startWork().get()
        assertTrue("result=$result", result is ListenableWorker.Result.Failure)
        assertEquals(before, widgetSnapshots(context).read())
        assertTrue("nothing was rendered", rendered.isEmpty())
    }
}
