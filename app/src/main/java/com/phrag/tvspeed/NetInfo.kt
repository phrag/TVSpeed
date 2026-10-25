package com.phrag.tvspeed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * What the TV is connected through, shown next to every result: a test run
 * through a VPN measures the tunnel, not the line.
 */
data class NetInfo(
    /** "Wi-Fi", "Ethernet", … or "Offline". */
    val transport: String,
    val vpn: Boolean,
    /** Wi-Fi only: band, link rate and signal, e.g. "5 GHz · 866 Mbps link · −40 dBm". */
    val detail: String,
) {
    val summary: String
        get() = listOf(transport, detail, if (vpn) "VPN on" else "").filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun read(ctx: Context): NetInfo {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities) ?: return NetInfo("Offline", false, "")
            // Through a VPN the active network carries both VPN and the underlying transport.
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                vpn -> "VPN"
                else -> "Network"
            }
            val detail = if (transport == "Wi-Fi") wifiDetail(ctx) else ""
            return NetInfo(transport, vpn, detail)
        }

        // connectionInfo is deprecated but still the only no-location-permission way to get
        // RSSI / link speed / frequency on API 26-30, and works on later versions too.
        @Suppress("DEPRECATION")
        private fun wifiDetail(ctx: Context): String {
            val info = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return ""
            val band = when (info.frequency) {
                in 2400..2500 -> "2.4 GHz"
                in 4900..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> ""
            }
            val link = if (info.linkSpeed > 0) "${info.linkSpeed} Mbps link" else ""
            val rssi = if (info.rssi in -127..-1) "${info.rssi} dBm" else ""
            return listOf(band, link, rssi).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }
}
