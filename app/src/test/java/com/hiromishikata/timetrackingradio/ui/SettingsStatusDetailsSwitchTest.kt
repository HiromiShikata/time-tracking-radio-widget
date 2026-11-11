package com.hiromishikata.timetrackingradio.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SettingsStatusDetailsSwitchTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val context: Context = application
    private val endpoint = "https://script.example.com/exec?key=s3cr3t"
    private val store = SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val originalTransportFactory = SettingsActivity.transportFactory
    private val originalUploadTransportFactory = SettingsActivity.uploadTransportFactory
    private val originalExecutor = SettingsActivity.backgroundExecutor

    @Before
    fun runTheConnectionCheckInline() {
        SettingsActivity.backgroundExecutor = Executor { it.run() }
    }

    @After
    fun restoreDefaults() {
        SettingsActivity.transportFactory = originalTransportFactory
        SettingsActivity.uploadTransportFactory = originalUploadTransportFactory
        SettingsActivity.backgroundExecutor = originalExecutor
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java, Intent(context, SettingsActivity::class.java)).setup().get()

    private fun statusSwitch(activity: SettingsActivity): CompoundButton =
        activity.findViewById(R.id.show_status_details_switch)

    private fun savedStatusDetails(): Boolean? = KeyValueWidgetSettingsRepository(store).read()?.showStatusDetails

    private fun widgetUpdateBroadcasts(): Int =
        shadowOf(application).broadcastIntents.count { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE }

    @Test
    fun theSwitchIsOnWhenNoSettingsAreSavedYet() {
        assertTrue(statusSwitch(launch()).isChecked)
    }

    @Test
    fun theSwitchShowsTheSavedChoiceAndEachToggleIsPersistedAndRedrawsTheWidget() {
        KeyValueWidgetSettingsRepository(store).write(WidgetSettings(endpoint, listOf("project-a"), showStatusDetails = false))
        val activity = launch()
        assertFalse(statusSwitch(activity).isChecked)
        val redrawsBeforeFirstToggle = widgetUpdateBroadcasts()
        statusSwitch(activity).performClick()
        assertEquals(true, savedStatusDetails())
        assertEquals(redrawsBeforeFirstToggle + 1, widgetUpdateBroadcasts())
        statusSwitch(activity).performClick()
        assertEquals(false, savedStatusDetails())
        assertEquals(redrawsBeforeFirstToggle + 2, widgetUpdateBroadcasts())
    }

    @Test
    fun savingTheFormKeepsTheSwitchChoice() {
        KeyValueWidgetSettingsRepository(store).write(WidgetSettings(endpoint, listOf("project-a"), showStatusDetails = false))
        val responses = listOf(
            """{"result":"ok","selection":null}""",
            """{"result":"ok","accepted":2}""",
        )
        val transport = FakeHttpTransport(ArrayDeque(responses.map { body -> { HttpResponse(200, null, body) } }))
        SettingsActivity.transportFactory = { transport }
        SettingsActivity.uploadTransportFactory = { transport }
        val activity = launch()
        activity.findViewById<EditText>(R.id.endpoint_input).setText(endpoint)
        activity.findViewById<EditText>(R.id.projects_input).setText("project-a\nproject-b")
        activity.findViewById<Button>(R.id.save_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, savedStatusDetails())
        assertFalse(statusSwitch(activity).isChecked)
    }

    @Test
    fun theSwitchSitsDirectlyBetweenTheProjectsInputAndSave() {
        val activity = launch()
        val projects = activity.findViewById<EditText>(R.id.projects_input)
        val save = activity.findViewById<Button>(R.id.save_button)
        val switch = statusSwitch(activity)
        val parent = save.parent as ViewGroup
        assertEquals(parent, switch.parent)
        assertEquals(parent.indexOfChild(projects) + 1, parent.indexOfChild(switch))
        assertEquals(parent.indexOfChild(switch) + 1, parent.indexOfChild(save))
        assertEquals("Show status details on the widget", switch.text.toString())
    }
}
