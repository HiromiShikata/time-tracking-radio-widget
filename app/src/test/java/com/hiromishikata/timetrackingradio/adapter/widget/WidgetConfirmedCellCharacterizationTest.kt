package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
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
class WidgetConfirmedCellCharacterizationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val cells = listOf(
        CellKey("project-a", ActivityKind.WORK),
        CellKey("project-a", ActivityKind.MEETING),
        CellKey("project-b", ActivityKind.WORK),
        CellKey("project-b", ActivityKind.MEETING),
    )

    @Test
    fun withNothingPendingOnlyTheConfirmedSelectionIsFilled() {
        val cases = listOf(
            "no selection" to null,
            "project-a work" to CellKey("project-a", ActivityKind.WORK),
            "project-b meeting" to CellKey("project-b", ActivityKind.MEETING),
        )
        cases.forEach { (name, selected) ->
            val snapshot = WidgetSnapshot(selected?.let { Selection(it, since) }, since, WidgetOutcome.Settled, null, null)
            cells.forEach { cell ->
                val expected = if (cell == selected) R.drawable.cell_selected else R.drawable.cell_unselected
                assertEquals("case=$name cell=$cell", expected, renderedCellDrawableResId(context, snapshot, cell))
            }
        }
    }
}
