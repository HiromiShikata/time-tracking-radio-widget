package com.hiromishikata.timetrackingradio.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.widget.SelectionWidgetRemoteViewsFactory
import com.hiromishikata.timetrackingradio.domain.ActivityKind
import com.hiromishikata.timetrackingradio.domain.CellKey
import com.hiromishikata.timetrackingradio.domain.Selection
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
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
@Config(sdk = [34], application = TestApplication::class, qualifiers = "ja")
class ScreenshotRenderingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val settings = WidgetSettings(
        "https://script.example.com/macros/s/example/exec/time-tracking-selection?key=example",
        listOf("project-a", "project-b"),
    )
    private val now = Instant.parse("2026-11-07T08:40:00Z")
    private val since = Instant.parse("2026-11-07T08:39:00Z")
    private val projectAWork = CellKey("project-a", ActivityKind.WORK)
    private val projectAMeeting = CellKey("project-a", ActivityKind.MEETING)
    private val projectBWork = CellKey("project-b", ActivityKind.WORK)
    private val outputDirectory = File("build/outputs/roborazzi")

    @Test
    fun rendersEveryApprovedWidgetState() {
        val states = listOf(
            "widget-idle" to WidgetSnapshot.EMPTY,
            "widget-recording" to WidgetSnapshot(Selection(projectBWork, since), now, WidgetOutcome.Settled, null, null),
            "widget-sending" to WidgetSnapshot(
                Selection(projectBWork, since),
                now,
                WidgetOutcome.Settled,
                SelectionRequest.Select(projectAMeeting),
                SelectionRequest.Select(projectAMeeting),
            ),
            "widget-network-error" to WidgetSnapshot(
                Selection(projectBWork, since),
                since,
                WidgetOutcome.Unreachable,
                null,
                null,
            ),
        )
        val factory = SelectionWidgetRemoteViewsFactory(context)
        states.forEach { (name, snapshot) ->
            val views = factory.build(settings, snapshot, now)
            val host = Robolectric.buildActivity(Activity::class.java).setup().get()
            val container = FrameLayout(host)
            host.setContentView(container)
            container.addView(views.apply(host, container))
            val file = capture(host.window.decorView, name)
            assertTrue("$name screenshot is empty", file.length() > 0)
        }
    }

    @Test
    fun rendersSettingsScreen() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val file = capture(activity.window.decorView, "settings")
        assertTrue("settings screenshot is empty", file.length() > 0)
    }

    private fun capture(view: View, name: String): File {
        outputDirectory.mkdirs()
        val file = File(outputDirectory, "$name.png")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        view.captureRoboImage(file.path)
        return file
    }

    private companion object {
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 760
    }
}
