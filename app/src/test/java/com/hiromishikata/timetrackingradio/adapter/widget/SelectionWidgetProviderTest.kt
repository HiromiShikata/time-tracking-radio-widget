package com.hiromishikata.timetrackingradio.adapter.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.URISyntaxException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SelectionWidgetProviderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        installWorkManagerForTests(context)
        installHarmlessWidgetWorkFactories()
    }

    @After
    fun tearDown() {
        restoreDefaultWidgetFactories()
    }

    private fun tapIntent(project: String?, activity: String?) = Intent(SelectionWidgetProvider.ACTION_CELL_TAP).apply {
        project?.let { putExtra(SelectionWidgetProvider.EXTRA_PROJECT, it) }
        activity?.let { putExtra(SelectionWidgetProvider.EXTRA_ACTIVITY, it) }
    }

    @Test
    fun aTapWithoutAValidCellIsLoggedAndChangesNothing() {
        val cases = listOf(
            "unknown activity" to tapIntent("project-a", "lunch"),
            "missing activity" to tapIntent("project-a", null),
            "missing project" to tapIntent(null, "work"),
        )
        cases.forEach { (name, intent) ->
            ShadowLog.clear()
            SelectionCellTapReceiver().onReceive(context, intent)
            val errors = ShadowLog.getLogsForTag("SelectionWidget").filter { it.type == Log.ERROR }
            assertTrue("case=$name", errors.any { it.msg == "tap intent has no valid cell" })
            assertEquals(
                "case=$name",
                WidgetSnapshot.EMPTY,
                KeyValueWidgetSnapshotRepository(SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))).read(),
            )
        }
    }

    @Test
    fun aWidgetWorkFailureIsLoggedWithoutItsQueryOrThrowable() {
        ShadowLog.clear()
        logWidgetWorkFailure(URISyntaxException("https://script.example.com/exec?key=SECRET123 x", "Illegal character in query"))
        val entries = ShadowLog.getLogsForTag("SelectionWidget").filter { it.type == Log.ERROR }
        assertEquals(1, entries.size)
        assertNull(entries.single().throwable)
        assertFalse(entries.single().msg.contains("SECRET123"))
    }

    @Test
    fun aValidTapRecordsItsEventRendersItsSendingStateAndEnqueuesTheSelectionWithoutNetwork() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val forbidden = ForbiddenTransport()
        val rendered = mutableListOf<WidgetSnapshot>()
        SelectionWidgetProvider.controllerFactory = { WidgetController(it, { forbidden }, { forbidden }, publish = { _, snapshot -> rendered += snapshot }) }
        SelectionCellTapReceiver().onReceive(context, tapIntent("project-a", "meeting"))
        assertEquals(0, forbidden.calls)
        assertTrue(rendered.any { it.sending == SelectionRequest.Select(CellKey("project-a", ActivityKind.MEETING)) })
        assertTrue(
            widgetDiagnosticLog(context).readAll().any {
                it.type == DiagnosticEventType.TAP_RECEIVED && Regex("tapped_at=.+, project=project-a, activity=meeting, request=select, queued_taps=1, work_id=.+").matches(it.detail)
            },
        )
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun aValidTapIsRecordedWithItsTappedTimeAndSelectRequestAndItsWorkIsEnqueued() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val forbidden = ForbiddenTransport()
        SelectionWidgetProvider.controllerFactory = { WidgetController(it, { forbidden }, { forbidden }, publish = { _, _ -> }) }
        val cases = listOf(
            "work in project-b" to CellKey("project-b", ActivityKind.WORK),
            "meeting in project-a" to CellKey("project-a", ActivityKind.MEETING),
        )
        cases.forEach { (name, cell) ->
            val before = Instant.ofEpochMilli(System.currentTimeMillis())
            SelectionCellTapReceiver().onReceive(context, cellTapIntent(cell.project, cell.activity.code))
            val after = Instant.ofEpochMilli(System.currentTimeMillis())
            val detail = widgetDiagnosticLog(context).readAll()
                .first { it.type == DiagnosticEventType.TAP_RECEIVED }.detail
            val fields = Regex("tapped_at=([^,]+), project=([^,]+), activity=([^,]+), request=select, queued_taps=\\d+, work_id=.+")
                .matchEntire(detail)
            assertTrue("case=$name detail=$detail", fields != null)
            val tappedAt = Instant.parse(fields!!.groupValues[1])
            assertFalse("case=$name", tappedAt.isBefore(before))
            assertFalse("case=$name", tappedAt.isAfter(after))
            assertEquals("case=$name", cell.project, fields.groupValues[2])
            assertEquals("case=$name", cell.activity.code, fields.groupValues[3])
        }
        assertEquals(0, forbidden.calls)
        assertTrue(uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).isNotEmpty())
    }

    @Test
    fun aValidTapDeliveredToTheProviderIsNotQueuedAndTouchesNoController() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val forbidden = ForbiddenTransport()
        var controllersCreated = 0
        SelectionWidgetProvider.controllerFactory = {
            controllersCreated += 1
            WidgetController(it, { forbidden }, { forbidden }, publish = { _, _ -> })
        }
        val cases = listOf(
            "work in project-b" to CellKey("project-b", ActivityKind.WORK),
            "meeting in project-a" to CellKey("project-a", ActivityKind.MEETING),
        )
        cases.forEach { (name, cell) ->
            SelectionWidgetProvider().onReceive(context, cellTapIntent(cell.project, cell.activity.code))
            assertTrue("case=$name", widgetDiagnosticLog(context).readAll().none { it.type == DiagnosticEventType.TAP_RECEIVED })
            assertTrue("case=$name", uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).isEmpty())
        }
        assertEquals(0, controllersCreated)
        assertEquals(0, forbidden.calls)
    }

    @Test
    fun aWidgetUpdateRendersAndEnqueuesARefreshWithoutNetwork() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val forbidden = ForbiddenTransport()
        SelectionWidgetProvider.controllerFactory = { WidgetController(it, { forbidden }, { forbidden }, publish = { _, _ -> }) }
        SelectionWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), IntArray(0))
        assertEquals(0, forbidden.calls)
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }
}
