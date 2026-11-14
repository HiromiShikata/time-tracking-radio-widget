package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.hiromishikata.timetrackingradio.adapter.update.updateDiagnosticRecorder
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.formatDeviceStateSnapshot
import java.util.Collections
import java.util.WeakHashMap

private val registeredApplications: MutableSet<Application> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Application, Boolean>()))

private val deviceStateFilter: IntentFilter = IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED).apply {
    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
}

/** Registers the device state receivers for this Application instance; a second call for the same instance does nothing. */
fun registerDeviceStateReceivers(app: Application) {
    val firstRegistration = registeredApplications.add(app)
    if (!firstRegistration) return
    ContextCompat.registerReceiver(app, DeviceStateChangeReceiver(app), deviceStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
}

private class DeviceStateChangeReceiver(private val app: Application) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        updateDiagnosticRecorder(app).record(
            DiagnosticEventType.DEVICE_STATE_CHANGED,
            formatDeviceStateSnapshot(readDeviceStateSnapshot(app)),
        )
    }
}
