package com.hiromishikata.timetrackingradio.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.widget.SelectionWidgetRemoteViewsFactory
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.UnconfirmedTap
import com.hiromishikata.timetrackingradio.domain.WidgetOutcome
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.WidgetSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApplication::class, qualifiers = "port-xhdpi")
class UnsentTapScreenshotRenderingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = WidgetSettings("https://example.com/exec?key=example", listOf("alpha", "beta"))
    private val alphaMeeting = CellKey("alpha", ActivityKind.MEETING)
    private val betaWork = CellKey("beta", ActivityKind.WORK)
    private val confirmedSince = Instant.parse("2026-11-09T02:10:00Z")
    private val tappedAt = Instant.parse("2026-11-09T02:22:42Z")
    private val sentAt = Instant.parse("2026-11-09T02:22:44Z")
    private val now = Instant.parse("2026-11-09T02:22:50Z")
    private val outputDirectory = File("build/outputs/roborazzi")

    @Test
    fun rendersTheUnsentTapStates() {
        val states = listOf(
            "after-tap-not-sent-data-saver" to WidgetSnapshot(
                selection = Selection(alphaMeeting, confirmedSince),
                fetchedAt = confirmedSince,
                outcome = WidgetOutcome.WaitingForNetwork,
                sending = null,
                statusRequest = null,
                pendingTapAt = tappedAt,
                oldestUnsentTapAt = tappedAt,
                queuedTapCount = 1,
                latestUnconfirmedTap = UnconfirmedTap(SelectionRequest.Select(betaWork), tappedAt),
                dataSaverRestricted = true,
            ),
            "after-tap-in-flight" to WidgetSnapshot(
                selection = Selection(alphaMeeting, confirmedSince),
                fetchedAt = confirmedSince,
                outcome = WidgetOutcome.Settled,
                sending = null,
                statusRequest = null,
                pendingTapAt = tappedAt,
                sentAt = sentAt,
                queuedTapCount = 1,
                latestUnconfirmedTap = UnconfirmedTap(SelectionRequest.Select(betaWork), tappedAt),
            ),
            "after-tap-saved" to WidgetSnapshot(
                selection = Selection(betaWork, tappedAt),
                fetchedAt = Instant.parse("2026-11-09T02:22:45.100Z"),
                outcome = WidgetOutcome.Settled,
                sending = null,
                statusRequest = null,
                savedAt = Instant.parse("2026-11-09T02:22:45.100Z"),
                roundTripMillis = 2_600,
            ),
            "after-clear-not-sent" to WidgetSnapshot(
                selection = Selection(alphaMeeting, confirmedSince),
                fetchedAt = confirmedSince,
                outcome = WidgetOutcome.Settled,
                sending = null,
                statusRequest = null,
                pendingTapAt = tappedAt,
                queuedTapCount = 1,
                latestUnconfirmedTap = UnconfirmedTap(SelectionRequest.Clear, tappedAt),
            ),
            "after-rejected" to WidgetSnapshot(
                selection = Selection(betaWork, confirmedSince),
                fetchedAt = now,
                outcome = WidgetOutcome.Rejected("project not allowed"),
                sending = null,
                statusRequest = null,
            ),
        )
        val factory = SelectionWidgetRemoteViewsFactory(context)
        states.forEach { (name, snapshot) ->
            val views = factory.build(settings, snapshot, now)
            val host = Robolectric.buildActivity(Activity::class.java).setup().get()
            val container = FrameLayout(host)
            host.setContentView(container)
            container.addView(views.apply(host, container))
            val file = capture(container, name)
            assertTrue("$name screenshot is empty", file.length() > 0)
        }
    }

    private fun widgetWidthPx(): Int = (WIDGET_WIDTH_DP * context.resources.displayMetrics.density).toInt()

    private fun widgetHeightPx(): Int = (WIDGET_HEIGHT_DP * context.resources.displayMetrics.density).toInt()

    private fun capture(view: View, name: String): File {
        outputDirectory.mkdirs()
        val file = File(outputDirectory, "$name.png")
        val width = widgetWidthPx()
        val height = widgetHeightPx()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(BACKDROP_COLOR)
            view.draw(this)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return file
    }

    private companion object {
        const val WIDGET_WIDTH_DP = 166
        const val WIDGET_HEIGHT_DP = 300
        const val PNG_QUALITY = 100

        val BACKDROP_COLOR = Color.rgb(0x2E, 0x2E, 0x32)
    }
}
