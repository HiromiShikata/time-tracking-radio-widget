package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApplication::class)
class SelectionWidgetTransparencyTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-08T00:00:00Z")
    private val settings = WidgetSettings(
        "https://example.com/exec?key=example",
        listOf("project-a", "project-b"),
    )

    private class Rendered(val root: View, val bitmap: Bitmap) {
        fun pixelAt(view: View): Int {
            var x = view.width / 2
            var y = view.height / 2
            var current: View = view
            while (current !== root) {
                x += current.left
                y += current.top
                current = current.parent as View
            }
            return bitmap.getPixel(x, y)
        }
    }

    private fun render(): Rendered {
        val root = SelectionWidgetRemoteViewsFactory(context)
            .build(settings, WidgetSnapshot.EMPTY, now)
            .apply(context, FrameLayout(context))
        val width = WIDTH_PX
        val height = HEIGHT_PX
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        root.draw(Canvas(bitmap))
        return Rendered(root, bitmap)
    }

    @Test
    fun theWidgetRootHasNoBackgroundAndItsPixelsAreTransparent() {
        val rendered = render()
        assertNull("root background", rendered.root.background)
        assertEquals("root corner pixel", Color.TRANSPARENT, rendered.bitmap.getPixel(0, 0))
    }

    @Test
    fun tableFillAndCircleInteriorsAreTransparent() {
        val rendered = render()
        val headerBlank = rendered.root.findViewById<View>(R.id.header_blank)
        val firstRow = rendered.root.findViewById<ViewGroup>(R.id.grid_rows).getChildAt(0)
        val workIcon = firstRow.findViewById<View>(R.id.cell_work_icon)
        assertEquals("table fill pixel", Color.TRANSPARENT, rendered.pixelAt(headerBlank))
        assertEquals("circle interior pixel", Color.TRANSPARENT, rendered.pixelAt(workIcon))
    }

    @Test
    fun gridLinesStayWhite() {
        val rendered = render()
        val headerBlank = rendered.root.findViewById<View>(R.id.header_blank)
        val verticalLine = (headerBlank.parent as ViewGroup).getChildAt(
            (headerBlank.parent as ViewGroup).indexOfChild(headerBlank) + 1,
        )
        assertEquals("vertical grid line pixel", Color.WHITE, rendered.pixelAt(verticalLine))
    }

    private companion object {
        const val WIDTH_PX = 720
        const val HEIGHT_PX = 480
    }
}
