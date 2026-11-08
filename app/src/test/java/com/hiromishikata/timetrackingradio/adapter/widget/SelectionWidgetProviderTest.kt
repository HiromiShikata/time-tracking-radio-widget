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
            SelectionWidgetProvider().onReceive(context, intent)
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
        SelectionWidgetProvider.controllerFactory = { WidgetController(it, { forbidden }, { forbidden }) { _, snapshot -> rendered += snapshot } }
        SelectionWidgetProvider().onReceive(context, tapIntent("project-a", "meeting"))
        assertEquals(0, forbidden.calls)
        assertTrue(rendered.any { it.sending == SelectionRequest.Select(CellKey("project-a", ActivityKind.MEETING)) })
        assertTrue(
            widgetDiagnosticLog(context).readAll().any {
                it.type == DiagnosticEventType.TAP_RECEIVED && it.detail == "project=project-a, activity=meeting"
            },
        )
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }

    @Test
    fun aWidgetUpdateRendersAndEnqueuesARefreshWithoutNetwork() {
        KeyValueWidgetSettingsRepository(widgetPreferences(context)).write(WIDGET_TEST_SETTINGS)
        val forbidden = ForbiddenTransport()
        SelectionWidgetProvider.controllerFactory = { WidgetController(it, { forbidden }, { forbidden }) { _, _ -> } }
        SelectionWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), IntArray(0))
        assertEquals(0, forbidden.calls)
        assertEquals(1, uniqueWorkInfos(context, WidgetSelectionWorker.UNIQUE_WORK_NAME).size)
    }
}
