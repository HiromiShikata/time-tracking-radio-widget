package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetCellTapTargetTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val widgetWidthDp = 360f

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).roundToInt()

    private fun laidOutRow(): LinearLayout {
        val root = SelectionWidgetRemoteViewsFactory(context)
            .build(WIDGET_TEST_SETTINGS, WidgetSnapshot.EMPTY, now)
            .apply(context, FrameLayout(context))
        root.measure(
            MeasureSpec.makeMeasureSpec(dp(widgetWidthDp), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root.findViewById<LinearLayout>(R.id.grid_rows).getChildAt(0) as LinearLayout
    }

    @Test
    fun eachCellFrameIsTheFullRowHeightAndTheSameWidthAsItsTwin() {
        val row = laidOutRow()
        val work = row.findViewById<View>(R.id.cell_work)
        val meeting = row.findViewById<View>(R.id.cell_meeting)
        assertEquals("data row height", dp(56f), (work.parent as View).height)
        assertEquals("work frame height", dp(56f), work.height)
        assertEquals("meeting frame height", dp(56f), meeting.height)
        assertEquals("cells share the width", work.width, meeting.width)
        assertTrue("cell frame has a tappable width", work.width > dp(48f))
    }

    @Test
    fun eachCellFrameDeclaresWeightOneAndMatchesTheRowHeight() {
        val row = laidOutRow()
        listOf(R.id.cell_work, R.id.cell_meeting).forEach { id ->
            val params = row.findViewById<View>(id).layoutParams as LinearLayout.LayoutParams
            assertEquals("id=$id", 1f, params.weight, 0f)
            assertEquals("id=$id", FrameLayout.LayoutParams.MATCH_PARENT, params.height)
        }
    }

    @Test
    fun theCircleIconIsInsideItsCellFrameSoTheFrameIsTheTapTarget() {
        val row = laidOutRow()
        assertEquals(R.id.cell_work, (row.findViewById<View>(R.id.cell_work_icon).parent as View).id)
        assertEquals(R.id.cell_meeting, (row.findViewById<View>(R.id.cell_meeting_icon).parent as View).id)
    }
}
