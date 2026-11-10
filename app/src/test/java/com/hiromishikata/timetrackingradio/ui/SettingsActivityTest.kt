package com.hiromishikata.timetrackingradio.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.hiromishikata.timetrackingradio.R
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.http.FakeHttpTransport
import com.hiromishikata.timetrackingradio.adapter.http.HttpResponse
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueDiagnosticLogRepository
import com.hiromishikata.timetrackingradio.adapter.store.SETTINGS_PREFERENCES_NAME
import com.hiromishikata.timetrackingradio.adapter.store.SharedPreferencesKeyValueStore
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
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
import java.time.Instant
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SettingsActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val endpoint = "https://script.example.com/exec?key=s3cr3t"
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

    private fun launch(widgetId: Int? = null): SettingsActivity {
        val intent = Intent(context, SettingsActivity::class.java)
        widgetId?.let { intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, it) }
        return Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
    }

    private fun useTransport(vararg responses: String): FakeHttpTransport {
        val transport = FakeHttpTransport(ArrayDeque(responses.map { body -> { HttpResponse(200, null, body) } }))
        SettingsActivity.transportFactory = { transport }
        SettingsActivity.uploadTransportFactory = { transport }
        return transport
    }

    @Test
    fun aValidSaveShowsTheConnectionOutcomeAndKeepsTheScreenOpen() {
        val transport = useTransport("""{"result":"ok","selection":null}""", """{"result":"ok","accepted":2}""")
        val activity = launch(widgetId = 7)
        activity.findViewById<EditText>(R.id.endpoint_input).setText(endpoint)
        activity.findViewById<EditText>(R.id.projects_input).setText("project-a\nproject-b")
        activity.findViewById<Button>(R.id.save_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Connection OK: no current selection", activity.findViewById<TextView>(R.id.connection_status).text.toString())
        assertFalse(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(listOf("GET", "POST"), transport.requests.map { it.method })
        val logText = activity.findViewById<TextView>(R.id.log_text).text.toString()
        assertTrue(logText, logText.contains(" settings_saved projects=2"))
        assertTrue(logText, logText.contains(" connection_check connected, no selection"))
        assertFalse(logText, logText.contains("s3cr3t"))
    }

    @Test
    fun anUnparsableEndpointIsRejectedOnSaveAndNoCheckRuns() {
        val transport = useTransport("""{"result":"ok","selection":null}""")
        val activity = launch(widgetId = 7)
        activity.findViewById<EditText>(R.id.endpoint_input).setText("https://script.example.com/exec?key=a b")
        activity.findViewById<EditText>(R.id.projects_input).setText("project-a")
        activity.findViewById<Button>(R.id.save_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), transport.requests.map { it.method })
        assertEquals("", activity.findViewById<TextView>(R.id.connection_status).text.toString())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun anUnexpectedFailureOfTheConnectionCheckIsShownAndDoesNotCrash() {
        SettingsActivity.transportFactory = { throw IllegalStateException("boom") }
        val activity = launch(widgetId = 7)
        activity.findViewById<EditText>(R.id.endpoint_input).setText(endpoint)
        activity.findViewById<EditText>(R.id.projects_input).setText("project-a")
        activity.findViewById<Button>(R.id.save_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "Connection failed: IllegalStateException: boom",
            activity.findViewById<TextView>(R.id.connection_status).text.toString(),
        )
        assertTrue(activity.findViewById<TextView>(R.id.log_text).text.toString().contains("exception IllegalStateException: boom"))
    }

    @Test
    fun copyLogPutsTheWholeLogOnTheClipboard() {
        KeyValueDiagnosticLogRepository(
            SharedPreferencesKeyValueStore(context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)),
        ).append(Instant.parse("2026-11-08T00:01:00Z"), DiagnosticEventType.TAP_RECEIVED, "project=a, activity=work")
        val activity = launch()
        val expected = "2026-11-08T00:01:00Z tap_received project=a, activity=work"
        assertEquals(expected, activity.findViewById<TextView>(R.id.log_text).text.toString())
        activity.findViewById<Button>(R.id.copy_log_button).performClick()
        assertEquals(expected, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class SettingsNotificationGuideTest {
    private val application = RuntimeEnvironment.getApplication() as Application

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java, Intent(application, SettingsActivity::class.java)).setup().get()

    @Test
    fun aNotificationGuideOpensTheAppNotificationSettingsWhenPermissionIsMissing() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = launch()
        val guide = activity.findViewById<Button>(R.id.update_notification_guide_button)
        assertEquals(View.VISIBLE, guide.visibility)
        guide.performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(application.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun theNotificationGuideIsHiddenWhenPermissionIsGranted() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = launch()
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.update_notification_guide_button).visibility)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = TestApplication::class)
class SettingsNotificationGuideBelowAndroid13Test {
    @Test
    fun theNotificationGuideIsHiddenBelowAndroid13() {
        val activity = Robolectric.buildActivity(
            SettingsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), SettingsActivity::class.java),
        ).setup().get()
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.update_notification_guide_button).visibility)
    }
}
