package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStateSnapshotTest {
    private val allUnknown = DeviceStateSnapshot(
        networkPresent = "unknown",
        transports = "unknown",
        validated = "unknown",
        metered = "unknown",
        suspended = "unknown",
        dataSaverStatus = DataSaverStatus.UNKNOWN,
        deviceIdleMode = "unknown",
        lightIdleMode = "unknown",
        powerSaveMode = "unknown",
        ignoresBatteryOptimizations = "unknown",
        standbyBucket = "unknown",
        backgroundRestricted = "unknown",
        processImportance = ProcessImportance.UNKNOWN,
    )

    private val healthy = allUnknown.copy(
        networkPresent = "true",
        transports = "wifi+cellular",
        validated = "true",
        metered = "false",
        suspended = "false",
        dataSaverStatus = DataSaverStatus.DISABLED,
        deviceIdleMode = "false",
        lightIdleMode = "false",
        powerSaveMode = "false",
        ignoresBatteryOptimizations = "true",
        standbyBucket = "ACTIVE",
        backgroundRestricted = "false",
        processImportance = ProcessImportance.FOREGROUND,
    )

    private val restricted = allUnknown.copy(
        networkPresent = "true",
        transports = "cellular",
        validated = "false",
        metered = "true",
        suspended = "true",
        dataSaverStatus = DataSaverStatus.ENABLED,
        deviceIdleMode = "true",
        lightIdleMode = "true",
        powerSaveMode = "true",
        ignoresBatteryOptimizations = "false",
        standbyBucket = "RESTRICTED",
        backgroundRestricted = "true",
        processImportance = ProcessImportance.BACKGROUND,
    )

    private val noNetworkAllowed = allUnknown.copy(
        networkPresent = "false",
        transports = "none",
        dataSaverStatus = DataSaverStatus.ALLOWED,
        standbyBucket = "RARE",
        processImportance = ProcessImportance.SERVICE,
    )

    @Test
    fun formatDeviceStateSnapshotNamesEveryFieldInFixedOrderAndKeepsUnavailableValuesUnknown() {
        val cases = listOf(
            allUnknown to
                "network_present=unknown, transports=unknown, validated=unknown, metered=unknown, suspended=unknown, " +
                "data_saver=unknown, device_idle_mode=unknown, light_idle_mode=unknown, power_save_mode=unknown, " +
                "ignores_battery_optimizations=unknown, standby_bucket=unknown, background_restricted=unknown, " +
                "process_importance=unknown",
            healthy to
                "network_present=true, transports=wifi+cellular, validated=true, metered=false, suspended=false, " +
                "data_saver=disabled, device_idle_mode=false, light_idle_mode=false, power_save_mode=false, " +
                "ignores_battery_optimizations=true, standby_bucket=ACTIVE, background_restricted=false, " +
                "process_importance=foreground",
            restricted to
                "network_present=true, transports=cellular, validated=false, metered=true, suspended=true, " +
                "data_saver=enabled, device_idle_mode=true, light_idle_mode=true, power_save_mode=true, " +
                "ignores_battery_optimizations=false, standby_bucket=RESTRICTED, background_restricted=true, " +
                "process_importance=background",
            noNetworkAllowed to
                "network_present=false, transports=none, validated=unknown, metered=unknown, suspended=unknown, " +
                "data_saver=allowed, device_idle_mode=unknown, light_idle_mode=unknown, power_save_mode=unknown, " +
                "ignores_battery_optimizations=unknown, standby_bucket=RARE, background_restricted=unknown, " +
                "process_importance=service",
            allUnknown.copy(processImportance = ProcessImportance.VISIBLE) to
                "network_present=unknown, transports=unknown, validated=unknown, metered=unknown, suspended=unknown, " +
                "data_saver=unknown, device_idle_mode=unknown, light_idle_mode=unknown, power_save_mode=unknown, " +
                "ignores_battery_optimizations=unknown, standby_bucket=unknown, background_restricted=unknown, " +
                "process_importance=visible",
        )
        cases.forEach { (snapshot, expected) ->
            assertEquals("expected=$expected", expected, formatDeviceStateSnapshot(snapshot))
        }
    }

    @Test
    fun dataSaverAndProcessImportanceCodesAreTheWordsTheLogShows() {
        val dataSaverCases = listOf(
            DataSaverStatus.DISABLED to "disabled",
            DataSaverStatus.ENABLED to "enabled",
            DataSaverStatus.ALLOWED to "allowed",
            DataSaverStatus.UNKNOWN to "unknown",
        )
        dataSaverCases.forEach { (value, expected) ->
            assertEquals("value=$value", expected, value.code)
        }
        val importanceCases = listOf(
            ProcessImportance.FOREGROUND to "foreground",
            ProcessImportance.VISIBLE to "visible",
            ProcessImportance.SERVICE to "service",
            ProcessImportance.BACKGROUND to "background",
            ProcessImportance.UNKNOWN to "unknown",
        )
        importanceCases.forEach { (value, expected) ->
            assertEquals("value=$value", expected, value.code)
        }
    }
}
