package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @Test
    fun aSelectionInFlightRendersTheSendingRingOnItsCellAndTheSwitchingStatus() {
        val meeting = CellKey("project-a", ActivityKind.MEETING)
        val work = CellKey("project-a", ActivityKind.WORK)
        val snapshot = WidgetSnapshot(null, null, WidgetOutcome.Settled, SelectionRequest.Select(meeting), SelectionRequest.Select(meeting))
        val cases = listOf(
            Pair(meeting, R.drawable.cell_sending),
            Pair(work, R.drawable.cell_unselected),
        )
        cases.forEach { (cell, expectedDrawable) ->
            assertEquals("cell=$cell", expectedDrawable, renderedCellDrawableResId(context, snapshot, cell))
        }
        assertEquals("Switching to project-a / meeting…", renderedStatusText(context, snapshot))
    }

    @Test
    fun aSendingRingWithoutAStatusRequestDoesNotFallBackToTheSwitchingStatus() {
        val meeting = CellKey("project-a", ActivityKind.MEETING)
        val idle = WidgetSnapshot(null, null, WidgetOutcome.Settled, null, null)
        val snapshot = WidgetSnapshot(null, null, WidgetOutcome.Settled, SelectionRequest.Select(meeting), null)
        assertEquals(renderedStatusText(context, idle), renderedStatusText(context, snapshot))
    }
}
