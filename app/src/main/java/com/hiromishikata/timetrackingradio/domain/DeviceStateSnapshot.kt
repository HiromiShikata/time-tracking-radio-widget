package com.hiromishikata.timetrackingradio.domain

enum class DataSaverStatus(val code: String) {
    DISABLED("disabled"),
    ENABLED("enabled"),
    ALLOWED("allowed"),
    UNKNOWN("unknown"),
}

enum class ProcessImportance(val code: String) {
    FOREGROUND("foreground"),
    VISIBLE("visible"),
    SERVICE("service"),
    BACKGROUND("background"),
    UNKNOWN("unknown"),
}

/** Device state read at one moment. A value the device does not expose, or that could not be read, is "unknown". */
data class DeviceStateSnapshot(
    val networkPresent: String,
    val transports: String,
    val validated: String,
    val metered: String,
    val blocked: String,
    val dataSaverStatus: DataSaverStatus,
    val deviceIdleMode: String,
    val lightIdleMode: String,
    val powerSaveMode: String,
    val ignoresBatteryOptimizations: String,
    val standbyBucket: String,
    val backgroundRestricted: String,
    val processImportance: ProcessImportance,
)

fun formatDeviceStateSnapshot(snapshot: DeviceStateSnapshot): String = listOf(
    "network_present=${snapshot.networkPresent}",
    "transports=${snapshot.transports}",
    "validated=${snapshot.validated}",
    "metered=${snapshot.metered}",
    "blocked=${snapshot.blocked}",
    "data_saver=${snapshot.dataSaverStatus.code}",
    "device_idle_mode=${snapshot.deviceIdleMode}",
    "light_idle_mode=${snapshot.lightIdleMode}",
    "power_save_mode=${snapshot.powerSaveMode}",
    "ignores_battery_optimizations=${snapshot.ignoresBatteryOptimizations}",
    "standby_bucket=${snapshot.standbyBucket}",
    "background_restricted=${snapshot.backgroundRestricted}",
    "process_importance=${snapshot.processImportance.code}",
).joinToString(", ")
