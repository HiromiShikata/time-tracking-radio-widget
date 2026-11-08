package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class WidgetSelectionWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val meeting = CellKey("project-a", ActivityKind.MEETING)
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val rendered = mutableListOf<WidgetSnapshot>()
    private lateinit var transport: EchoSelectionTransport

    private data class RunCase(val name: String, val input: Data, val expectedMethods: List<String>)

    private val tapInput = workDataOf(WidgetSelectionWorker.KEY_PROJECT to "project-a", WidgetSelectionWorker.KEY_ACTIVITY to "meeting")

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        installHarmlessWidgetWorkFactories()
        transport = EchoSelectionTransport()
        WidgetSelectionWorker.controllerFactory = { WidgetController(it, { transport }, { EchoSelectionTransport() }) { _, snapshot -> rendered += snapshot } }
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
    }

    private fun runWorker(input: Data): ListenableWorker.Result =
        TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(input).build().startWork().get()

    @Test
    fun eachInputRunsItsSelectionCallAndRendersTheResult() {
        val cases = listOf(
            RunCase("a tap sends one selection post", tapInput, listOf("POST")),
            RunCase("a refresh only reads the selection", Data.EMPTY, listOf("GET")),
        )
        cases.forEach { case ->
            transport = EchoSelectionTransport()
            rendered.clear()
            val result = runWorker(case.input)
            assertTrue("case=${case.name}", result is ListenableWorker.Result.Success)
            assertEquals("case=${case.name}", case.expectedMethods, transport.requests.map { it.method })
            val stored = widgetSnapshots(context).read()
            assertNull("case=${case.name}", stored.sending)
            assertEquals("case=${case.name}", stored, rendered.last())
        }
    }

    @Test
    fun aTapStoresTheSelectionTheServerReturns() {
        runWorker(tapInput)
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }

    @Test
    fun aSucceededTapEnqueuesTheDiagnosticsUploadUnderItsOwnName() {
        runWorker(tapInput)
        assertEquals(1, uniqueWorkInfos(context, WidgetDiagnosticsUploadWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun anUnexpectedFailureOfTheSelectionCallClearsTheSendingStateAndSucceedsSoChainedWorkStillRuns() {
        transport = EchoSelectionTransport { throw IllegalStateException("unexpected") }
        val result = runWorker(tapInput)
        assertTrue("result=$result", result is ListenableWorker.Result.Success)
        assertTrue("the sending state was rendered first", rendered.any { it.sending != null })
        assertNull(widgetSnapshots(context).read().sending)
        assertNull("the last rendered screen still shows a sending cell", rendered.last().sending)
    }

    @Test
    fun aStoppedWorkClearsTheSendingState() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        widgetSnapshots(context).write(WidgetSnapshot(Selection(meeting, since), since, WidgetOutcome.Settled, SelectionRequest.Select(meeting)))
        val worker = TestListenableWorkerBuilder<WidgetSelectionWorker>(context).setInputData(tapInput).build()
        worker.onStopped()
        assertNull(widgetSnapshots(context).read().sending)
        assertNull(rendered.last().sending)
        assertEquals(Selection(meeting, since), widgetSnapshots(context).read().selection)
    }
}
