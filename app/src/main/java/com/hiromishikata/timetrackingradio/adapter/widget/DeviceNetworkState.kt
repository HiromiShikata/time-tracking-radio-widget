package com.hiromishikata.timetrackingradio.adapter.widget

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.hiromishikata.timetrackingradio.domain.NetworkState

fun readDeviceNetworkState(context: Context): NetworkState {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val active = connectivity.activeNetwork
    val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
    val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    } else {
        null
    }
    return NetworkState(
        activeNetwork = active != null,
        transports = capabilities?.let { transportNames(it) }.orEmpty(),
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = capabilities?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
        backgroundRestricted = backgroundRestricted,
        dataSaverEnabled = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    )
}

private fun transportNames(capabilities: NetworkCapabilities): List<String> = transportNamesOf(capabilities::hasTransport)

internal fun transportNamesOf(hasTransport: (Int) -> Boolean): List<String> = KNOWN_TRANSPORTS
    .filter { (transport, _) -> hasTransport(transport) }
    .map { (_, name) -> name }
    .ifEmpty { listOf("other") }

private val KNOWN_TRANSPORTS = listOf(
    NetworkCapabilities.TRANSPORT_WIFI to "wifi",
    NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
    NetworkCapabilities.TRANSPORT_VPN to "vpn",
    NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
)
