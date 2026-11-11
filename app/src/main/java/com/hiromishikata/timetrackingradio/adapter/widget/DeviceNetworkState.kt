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

private fun transportNames(capabilities: NetworkCapabilities): List<String> = listOfNotNull(
    "wifi".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
    "cellular".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
    "ethernet".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
    "vpn".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
    "bluetooth".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
).ifEmpty { listOf("other") }
