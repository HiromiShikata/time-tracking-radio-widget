package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.hiromishikata.timetrackingradio.domain.DataSaverStatus
import com.hiromishikata.timetrackingradio.domain.DeviceStateSnapshot
import com.hiromishikata.timetrackingradio.domain.DiagnosticEventType
import com.hiromishikata.timetrackingradio.domain.ProcessImportance
import com.hiromishikata.timetrackingradio.domain.formatDeviceStateSnapshot

private const val UNKNOWN = "unknown"

private const val DEVICE_STATE_LOG_TAG = "DeviceState"

private data class NetworkFields(
    val networkPresent: String,
    val transports: String,
    val validated: String,
    val metered: String,
    val blocked: String,
)

private val UNKNOWN_NETWORK_FIELDS = NetworkFields(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)

/**
 * Reads the device state now. Each field is read on its own: a service that is missing or throws makes only the
 * fields that depend on it unknown, and the failure is logged rather than dropped.
 */
fun readDeviceStateSnapshot(context: Context): DeviceStateSnapshot {
    val network = readNetworkFields(context)
    return DeviceStateSnapshot(
        networkPresent = network.networkPresent,
        transports = network.transports,
        validated = network.validated,
        metered = network.metered,
        blocked = network.blocked,
        dataSaverStatus = readDataSaverStatus(context),
        deviceIdleMode = readField("device_idle_mode", UNKNOWN) {
            powerManager(context).isDeviceIdleMode.toString()
        },
        lightIdleMode = readField("light_idle_mode", UNKNOWN) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                UNKNOWN
            } else {
                powerManager(context).isDeviceLightIdleMode.toString()
            }
        },
        powerSaveMode = readField("power_save_mode", UNKNOWN) {
            powerManager(context).isPowerSaveMode.toString()
        },
        ignoresBatteryOptimizations = readField("ignores_battery_optimizations", UNKNOWN) {
            powerManager(context).isIgnoringBatteryOptimizations(context.packageName).toString()
        },
        standbyBucket = readField("standby_bucket", UNKNOWN) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                UNKNOWN
            } else {
                standbyBucketName(checkNotNull(context.getSystemService(UsageStatsManager::class.java)).appStandbyBucket)
            }
        },
        backgroundRestricted = readField("background_restricted", UNKNOWN) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                UNKNOWN
            } else {
                activityManager(context).isBackgroundRestricted.toString()
            }
        },
        processImportance = readField("process_importance", ProcessImportance.UNKNOWN) {
            val ownPid = Process.myPid()
            val ownProcess = checkNotNull(activityManager(context).runningAppProcesses.firstOrNull { it.pid == ownPid }) {
                "this process is not listed"
            }
            processImportanceOf(ownProcess.importance)
        },
    )
}

/** The diagnostic detail suffix for exception events: the device state at the moment the exception is recorded. */
fun deviceStateExceptionSuffixes(context: Context): (DiagnosticEventType) -> String? = { type ->
    if (type == DiagnosticEventType.EXCEPTION) formatDeviceStateSnapshot(readDeviceStateSnapshot(context)) else null
}

private fun readNetworkFields(context: Context): NetworkFields = readField("network", UNKNOWN_NETWORK_FIELDS) {
    val connectivity = connectivityManager(context)
    val active = connectivity.activeNetwork
    val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
    NetworkFields(
        networkPresent = (active != null).toString(),
        transports = when {
            active == null -> "none"
            capabilities == null -> UNKNOWN
            else -> transportNamesOf(capabilities::hasTransport).joinToString("+")
        },
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: UNKNOWN,
        metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }?.toString() ?: UNKNOWN,
        blocked = if (capabilities == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            UNKNOWN
        } else {
            (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)).toString()
        },
    )
}

private fun readDataSaverStatus(context: Context): DataSaverStatus = readField("data_saver", DataSaverStatus.UNKNOWN) {
    dataSaverStatusOf(connectivityManager(context).restrictBackgroundStatus)
}

private fun dataSaverStatusOf(restrictBackgroundStatus: Int): DataSaverStatus = when (restrictBackgroundStatus) {
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> DataSaverStatus.DISABLED
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> DataSaverStatus.ALLOWED
    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> DataSaverStatus.ENABLED
    else -> DataSaverStatus.UNKNOWN
}

private fun processImportanceOf(importance: Int): ProcessImportance = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> ProcessImportance.FOREGROUND
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> ProcessImportance.VISIBLE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> ProcessImportance.SERVICE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> ProcessImportance.BACKGROUND
    else -> ProcessImportance.UNKNOWN
}

private fun standbyBucketName(bucket: Int): String = when (bucket) {
    UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
    UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
    UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
    UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
    UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
    else -> UNKNOWN
}

private fun connectivityManager(context: Context): ConnectivityManager =
    checkNotNull(context.getSystemService(ConnectivityManager::class.java)) { "ConnectivityManager is not available" }

private fun powerManager(context: Context): PowerManager =
    checkNotNull(context.getSystemService(PowerManager::class.java)) { "PowerManager is not available" }

private fun activityManager(context: Context): ActivityManager =
    checkNotNull(context.getSystemService(ActivityManager::class.java)) { "ActivityManager is not available" }

private inline fun <T> readField(name: String, unknown: T, read: () -> T): T = try {
    read()
} catch (failure: Exception) {
    Log.w(DEVICE_STATE_LOG_TAG, "device state field $name is unknown", failure)
    unknown
}
