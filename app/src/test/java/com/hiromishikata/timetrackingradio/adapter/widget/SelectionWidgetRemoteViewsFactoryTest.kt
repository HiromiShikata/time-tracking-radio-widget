package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.PendingIntent
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SelectionWidgetRemoteViewsFactoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val factory = SelectionWidgetRemoteViewsFactory(context)

    private fun settingsWithProjects(count: Int) = WidgetSettings(
        "https://example.com/exec?key=example",
        List(count) { "project-${it + 1}" },
    )

    @Test
    fun aRebuildOntoTheSameViewShowsOneRowPerConfiguredProject() {
        val cases = listOf(
            Triple(2, 2, "same project count"),
            Triple(3, 1, "fewer projects after the rebuild"),
            Triple(1, 3, "more projects after the rebuild"),
        )
        cases.forEach { (first, second, name) ->
            val root = factory.build(settingsWithProjects(first), WidgetSnapshot.EMPTY, now)
                .apply(context, FrameLayout(context))
            factory.build(settingsWithProjects(second), WidgetSnapshot.EMPTY, now).reapply(context, root)
            val rows = root.findViewById<ViewGroup>(R.id.grid_rows)
            assertEquals("case=$name", second, rows.childCount)
        }
    }

    private fun tapPendingIntentOf(rowIndex: Int, cell: CellKey): PendingIntent {
        val method = SelectionWidgetRemoteViewsFactory::class.java
            .getDeclaredMethod("tapPendingIntent", Int::class.javaPrimitiveType, CellKey::class.java)
        method.isAccessible = true
        return method.invoke(factory, rowIndex, cell) as PendingIntent
    }

    @Test
    fun aCellTapPendingIntentTargetsTheInternalCellTapReceiverExplicitly() {
        val cases = listOf(
            Triple(0, CellKey("project-a", ActivityKind.WORK), "work in the first row"),
            Triple(1, CellKey("project-b", ActivityKind.MEETING), "meeting in the second row"),
        )
        cases.forEach { (rowIndex, cell, name) ->
            val tap = shadowOf(tapPendingIntentOf(rowIndex, cell)).savedIntent
            assertEquals("case=$name", SelectionCellTapReceiver::class.java.name, tap.component?.className)
        }
    }

    @Test
    fun aCellTapPendingIntentCarriesTheCellTapActionTheProjectAndActivityExtrasAndIsImmutable() {
        val cases = listOf(
            Triple(0, CellKey("project-a", ActivityKind.WORK), "work in the first row"),
            Triple(1, CellKey("project-b", ActivityKind.MEETING), "meeting in the second row"),
        )
        cases.forEach { (rowIndex, cell, name) ->
            val pendingIntent = tapPendingIntentOf(rowIndex, cell)
            val shadow = shadowOf(pendingIntent)
            val tap = shadow.savedIntent
            assertTrue("case=$name", shadow.isBroadcast)
            assertTrue("case=$name", (shadow.flags and PendingIntent.FLAG_IMMUTABLE) != 0)
            assertEquals("case=$name", SelectionWidgetProvider.ACTION_CELL_TAP, tap.action)
            assertEquals("case=$name", "timetrackingradio://cell/$rowIndex/${cell.activity.code}", tap.dataString)
            assertEquals("case=$name", cell.project, tap.getStringExtra(SelectionWidgetProvider.EXTRA_PROJECT))
            assertEquals("case=$name", cell.activity.code, tap.getStringExtra(SelectionWidgetProvider.EXTRA_ACTIVITY))
        }
    }
}
