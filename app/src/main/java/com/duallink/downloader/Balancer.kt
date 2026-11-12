package com.duallink.downloader

import android.net.Network
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Live counters for one link in VPN mode. */
class VpnLink(val name: String, private val provider: () -> Network?) {
    val down = AtomicLong(0)
    val up = AtomicLong(0)
    val active = AtomicInteger(0)
    val total = AtomicLong(0)
    val failures = AtomicLong(0)
    @Volatile var downBps = 0.0
    @Volatile var upBps = 0.0
    /** Slowly decaying best speed seen: our estimate of what the link can do. */
    @Volatile var capacityBps = 0.0
    internal var lastDown = 0L
    internal var lastUp = 0L

    val network: Network? get() = provider()

    /** Lower is better: active connections per unit of capacity. */
    fun score(): Double = (active.get() + 1) / maxOf(capacityBps, MIN_CAPACITY)

    companion object {
        const val MIN_CAPACITY = 150.0 * 1024 // bytes/s
    }
}

/**
 * Decides which link (Wi-Fi or mobile data) each new connection goes out on.
 *
 * - SPEED mode: every new connection goes to the link with the fewest active
 *   connections relative to its measured capacity, so a faster link carries more.
 * - STICKY mode: all connections to the same host use the same link. Slower to
 *   spread, but sites that tie a login session to your IP (some banks) keep working.
 */
class Balancer(val links: List<VpnLink>) {

    @Volatile var sticky = false
    private val rr = AtomicInteger(0)

    /** Links that currently have a network, best first. */
    fun order(hostKey: String): List<VpnLink> {
        val up = links.filter { it.network != null }
        if (up.size <= 1) return up
        if (sticky) {
            val first = Math.floorMod(hostKey.hashCode(), up.size)
            return List(up.size) { up[(first + it) % up.size] }
        }
        val start = Math.floorMod(rr.getAndIncrement(), up.size) // rotate ties
        val rotated = List(up.size) { up[(start + it) % up.size] }
        return rotated.sortedBy { it.score() }
    }

    /**
     * Opens a TCP connection to host:port on the best link, falling back to the
     * other link if it fails. `host` is a domain name or an IP literal.
     */
    fun connect(host: String, port: Int, isName: Boolean): Pair<VpnLink, Socket> {
        var last: Exception? = null
        for (link in order(host)) {
            val net = link.network ?: continue
            try {
                val addrs = resolve(net, host, isName)
                for (a in addrs) {
                    val s = net.socketFactory.createSocket()
                    try {
                        s.connect(InetSocketAddress(a, port), CONNECT_TIMEOUT_MS)
                        s.tcpNoDelay = true
                        return link to s
                    } catch (e: IOException) {
                        last = e
                        try { s.close() } catch (_: IOException) {}
                    }
                }
            } catch (e: Exception) {
                last = e
            }
            link.failures.incrementAndGet()
        }
        throw IOException("no link could connect to $host:$port", last)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8000

        /** DNS for a name is resolved over the same link the connection will use. */
        fun resolve(net: Network, host: String, isName: Boolean): List<InetAddress> {
            if (!isName) return listOf(InetAddress.getByName(host))
            val all = net.getAllByName(host).toList()
            // IPv4 first: mobile networks often have broken or missing IPv6.
            return all.filterIsInstance<Inet4Address>() + all.filter { it !is Inet4Address }
        }
    }
}
