package com.hiromishikata.timetrackingradio.adapter.widget

import android.net.NetworkCapabilities
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
}
