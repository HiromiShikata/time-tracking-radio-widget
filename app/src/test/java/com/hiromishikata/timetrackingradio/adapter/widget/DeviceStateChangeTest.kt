package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.adapter.store.KeyValueWidgetSettingsRepository
import com.hiromishikata.timetrackingradio.domain.DiagnosticEntry
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.WidgetSettings
import com.hiromishikata.timetrackingradio.domain.formatDeviceStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val deviceStateActions = listOf(
    ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED,
    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class DeviceStateChangeTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun deviceStateEvents(): List<DiagnosticEntry> =
        widgetDiagnosticLog(app).readAll().filter { it.type == DiagnosticEventType.DEVICE_STATE_CHANGED }

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun eachDeviceStateBroadcastRecordsExactlyOneEventWhoseDetailIsTheSnapshot() {
        registerDeviceStateReceivers(app)
        deviceStateActions.forEach { action ->
            val before = deviceStateEvents().size
            broadcast(action)
            val after = deviceStateEvents()
            assertEquals("action=$action", before + 1, after.size)
            assertEquals(
                "action=$action",
                formatDeviceStateSnapshot(readDeviceStateSnapshot(app)),
                after.first().detail,
            )
        }
    }

    @Test
    fun registeringTwiceStillRecordsOneEventPerBroadcast() {
        registerDeviceStateReceivers(app)
        registerDeviceStateReceivers(app)
        broadcast(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        assertEquals(1, deviceStateEvents().size)
    }

    @Test
    fun noDeviceStateEventCarriesTheEndpointKey() {
        KeyValueWidgetSettingsRepository(widgetPreferences(app)).write(
            WidgetSettings("https://example.test/endpoint?key=beta-secret", listOf("project-a")),
        )
        registerDeviceStateReceivers(app)
        deviceStateActions.forEach { broadcast(it) }
        assertEquals(deviceStateActions.size, deviceStateEvents().size)
        assertTrue(widgetDiagnosticLog(app).readAll().none { it.detail.contains("beta-secret") })
    }
}
