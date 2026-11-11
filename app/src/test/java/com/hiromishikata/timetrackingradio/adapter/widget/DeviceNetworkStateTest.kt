package com.hiromishikata.timetrackingradio.adapter.widget

import android.net.NetworkCapabilities
import com.hiromishikata.timetrackingradio.domain.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNetworkStateTest {
    @Test
    fun transportNamesListEveryKnownTransportTheNetworkHasInOrder() {
        val cases = listOf(
            setOf(NetworkCapabilities.TRANSPORT_WIFI) to listOf("wifi"),
            setOf(NetworkCapabilities.TRANSPORT_CELLULAR) to listOf("cellular"),
            setOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI) to listOf("wifi", "cellular"),
            setOf(NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN) to listOf("ethernet", "vpn"),
            setOf(NetworkCapabilities.TRANSPORT_BLUETOOTH) to listOf("bluetooth"),
            emptySet<Int>() to listOf("other"),
            setOf(NetworkCapabilities.TRANSPORT_LOWPAN) to listOf("other"),
        )
        cases.forEach { (transports, expected) ->
            assertEquals("transports=$transports", expected, transportNamesOf { it in transports })
        }
    }

    @Test
    fun networkStateMapsEveryFieldAndReportsNoTransportWithoutAnActiveNetwork() {
        val cases = listOf(
            networkStateOf(false, { true }, true, true, false, false) to
                NetworkState(false, emptyList(), true, false, false, false),
            networkStateOf(true, { it == NetworkCapabilities.TRANSPORT_WIFI }, true, true, true, true) to
                NetworkState(true, listOf("wifi"), true, false, true, true),
            networkStateOf(true, { it == NetworkCapabilities.TRANSPORT_CELLULAR }, false, false, null, null) to
                NetworkState(true, listOf("cellular"), false, true, null, null),
            networkStateOf(true, { false }, null, null, null, null) to
                NetworkState(true, listOf("other"), null, null, null, null),
        )
        cases.forEach { (actual, expected) -> assertEquals("expected=$expected", expected, actual) }
    }
}
