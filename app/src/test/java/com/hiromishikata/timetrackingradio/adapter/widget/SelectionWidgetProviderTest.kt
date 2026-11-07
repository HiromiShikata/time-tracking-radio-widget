package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSnapshotRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SelectionWidgetProviderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

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
}
