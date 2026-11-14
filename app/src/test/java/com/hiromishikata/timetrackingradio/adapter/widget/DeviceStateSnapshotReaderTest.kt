package com.hiromishikata.timetrackingradio.adapter.widget

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import com.hiromishikata.timetrackingradio.TestApplication
import com.hiromishikata.timetrackingradio.domain.formatDeviceStateSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private enum class ServiceFailure { MISSING, THROWS }

/** Replaces one system service: it is either absent (null) or throws, and every other service is the real one. */
private class ServiceOverrideContext(
    base: Context,
    private val service: String,
    private val failure: ServiceFailure,
) : ContextWrapper(base) {
    override fun getSystemService(name: String): Any? {
        if (name != service) return super.getSystemService(name)
        return when (failure) {
            ServiceFailure.MISSING -> null
            ServiceFailure.THROWS -> throw SecurityException("denied for test")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class DeviceStateSnapshotReaderTest {
    private val base: Context = RuntimeEnvironment.getApplication()

    @Test
    fun aMissingOrFailingServiceYieldsUnknownForItsFieldsAndNeverThrows() {
        val connectivityFields = "network_present=unknown, transports=unknown, validated=unknown, metered=unknown, " +
            "suspended=unknown, data_saver=unknown"
        val powerFields = "device_idle_mode=unknown, light_idle_mode=unknown, power_save_mode=unknown, " +
            "ignores_battery_optimizations=unknown"
        val usageStatsFields = "standby_bucket=unknown"
        val activityFields = "background_restricted=unknown, process_importance=unknown"
        val cases = listOf(
            Triple(Context.CONNECTIVITY_SERVICE, ServiceFailure.MISSING, connectivityFields),
            Triple(Context.CONNECTIVITY_SERVICE, ServiceFailure.THROWS, connectivityFields),
            Triple(Context.POWER_SERVICE, ServiceFailure.MISSING, powerFields),
            Triple(Context.POWER_SERVICE, ServiceFailure.THROWS, powerFields),
            Triple(Context.USAGE_STATS_SERVICE, ServiceFailure.MISSING, usageStatsFields),
            Triple(Context.USAGE_STATS_SERVICE, ServiceFailure.THROWS, usageStatsFields),
            Triple(Context.ACTIVITY_SERVICE, ServiceFailure.MISSING, activityFields),
            Triple(Context.ACTIVITY_SERVICE, ServiceFailure.THROWS, activityFields),
        )
        cases.forEach { (service, failure, expectedFragment) ->
            val detail = formatDeviceStateSnapshot(readDeviceStateSnapshot(ServiceOverrideContext(base, service, failure)))
            assertTrue("service=$service failure=$failure expected=$expectedFragment detail=$detail", detail.contains(expectedFragment))
        }
    }

    @Test
    fun anAbsentActiveNetworkReportsNoTransportsAndUnknownValidationAndMetering() {
        shadowOf(base.getSystemService(ConnectivityManager::class.java)).setDefaultNetworkActive(false)
        val detail = formatDeviceStateSnapshot(readDeviceStateSnapshot(base))
        assertTrue(
            "detail=$detail",
            detail.startsWith("network_present=false, transports=none, validated=unknown, metered=unknown, "),
        )
    }

    @Test
    @Config(sdk = [26])
    fun onSdk26StandbyBucketAndBackgroundRestrictionAreUnknown() {
        assertStandbyAndRestrictionUnknownWithoutThrowing()
    }

    @Test
    @Config(sdk = [27])
    fun onSdk27StandbyBucketAndBackgroundRestrictionAreUnknown() {
        assertStandbyAndRestrictionUnknownWithoutThrowing()
    }

    private fun assertStandbyAndRestrictionUnknownWithoutThrowing() {
        val detail = formatDeviceStateSnapshot(readDeviceStateSnapshot(base))
        assertTrue("detail=$detail", detail.contains("standby_bucket=unknown, background_restricted=unknown"))
    }
}
