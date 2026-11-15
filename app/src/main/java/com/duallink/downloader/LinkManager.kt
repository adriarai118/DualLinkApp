package com.duallink.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Keeps Wi-Fi and mobile data up at the same time.
 *
 * Normally Android tears down mobile data as soon as Wi-Fi connects. Calling
 * requestNetwork() with TRANSPORT_CELLULAR tells the system an app actively needs
 * the cellular network, so it stays up alongside Wi-Fi. Each Network object can then
 * open its own sockets (Network.openConnection), which is how the engine sends each
 * piece of the download over a specific link.
 *
 * Note: requested networks are non-VPN, so traffic here bypasses any active VPN app.
 */
class LinkManager(context: Context, private val log: Boolean = false) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private inner class Slot(val transport: Int, val label: String) : ConnectivityManager.NetworkCallback() {
        @Volatile var network: Network? = null
        @Volatile var validated: Boolean? = null

        override fun onAvailable(network: Network) {
            this.network = network
            if (log) DiagLog.add("شبکه $label وصل شد (${Diagnostics.describe(cm, network)})")
        }

        override fun onLost(network: Network) {
            if (this.network == network) {
                this.network = null
                validated = null
                if (log) DiagLog.add("شبکه $label قطع شد")
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (log && v != validated) {
                DiagLog.add(if (v) "$label: اینترنت تأیید شد (VALIDATED)" else "$label: اینترنت تأیید نشده (NOT validated)")
            }
            validated = v
        }

        override fun onUnavailable() {
            if (log) DiagLog.add("اندروید شبکه $label را نداد")
        }
    }

    private val wifiSlot = Slot(NetworkCapabilities.TRANSPORT_WIFI, "وای‌فای")
    private val cellSlot = Slot(NetworkCapabilities.TRANSPORT_CELLULAR, "دیتا")
    private val registered = mutableListOf<Slot>()

    val wifi: Network? get() = wifiSlot.network
    val cell: Network? get() = cellSlot.network

    /** DNS servers the operator / router gave this network. */
    fun dnsServers(network: Network?): List<java.net.InetAddress> =
        network?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()

    fun start(useWifi: Boolean, useCell: Boolean) {
        if (useWifi) request(wifiSlot)
        if (useCell) request(cellSlot)
    }

    private fun request(slot: Slot) {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(slot.transport)
            .build()
        cm.requestNetwork(req, slot)
        registered += slot
        if (log) DiagLog.add("درخواست شبکه ${slot.label} ثبت شد")
    }

    fun stop() {
        for (slot in registered) {
            try {
                cm.unregisterNetworkCallback(slot)
            } catch (_: Exception) {
            }
            slot.network = null
        }
        registered.clear()
    }
}
