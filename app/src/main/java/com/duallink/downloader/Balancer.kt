package com.duallink.downloader

import android.net.Network
import android.os.SystemClock
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
    /** Slowly decaying best download speed seen: our estimate of what the link can do. */
    @Volatile var capacityBps = 0.0
    /** While now < badUntil the link only gets connections if nothing else works. */
    @Volatile var badUntil = 0L
    internal val recentFails = AtomicInteger(0)
    internal var lastDown = 0L
    internal var lastUp = 0L
    internal var swrrCurrent = 0.0

    val network: Network? get() = provider()

    /**
     * Share weight. A link is never weighted below 35% of the best link, so it keeps
     * receiving connections and gets the chance to show its real speed. Without this
     * floor a link that happened to look slow once would be starved forever, which
     * is exactly the "sometimes only one link is used" symptom.
     */
    fun weight(bestCapacity: Double): Double =
        maxOf(capacityBps, bestCapacity * MIN_SHARE, MIN_CAPACITY)

    companion object {
        const val MIN_CAPACITY = 300.0 * 1024 // bytes/s
        const val MIN_SHARE = 0.5
    }
}

/**
 * Decides which link (Wi-Fi or mobile data) each new connection goes out on.
 *
 * SPEED mode: weighted least-connections (direct mode, where we know how many
 * connections each link has open) or smooth weighted round-robin (proxy mode,
 * where Xray owns the sockets). The weight is the link's measured capacity, so
 * the faster link carries proportionally more connections.
 *
 * STICKY mode: all connections to the same host use the same link, for sites that
 * tie a login session to your IP address.
 */
class Balancer(val links: List<VpnLink>, private val countsActive: Boolean = true) {

    @Volatile var sticky = false
    private val rr = AtomicInteger(0)

    /** Links that currently have a network, best first. */
    fun order(hostKey: String): List<VpnLink> {
        val up = links.filter { it.network != null }
        if (up.size <= 1) return up
        val now = SystemClock.elapsedRealtime()
        val healthy = up.filter { it.badUntil <= now }.ifEmpty { up }
        val rest = up.filter { it !in healthy }

        val first: VpnLink = when {
            healthy.size == 1 -> healthy[0]
            sticky -> healthy[Math.floorMod(hostKey.hashCode(), healthy.size)]
            countsActive -> leastLoaded(healthy)
            else -> smoothWeighted(healthy)
        }
        return listOf(first) + healthy.filter { it !== first } + rest
    }

    private fun leastLoaded(cands: List<VpnLink>): VpnLink {
        val best = cands.maxOf { it.capacityBps }
        val start = Math.floorMod(rr.getAndIncrement(), cands.size) // rotate ties
        return List(cands.size) { cands[(start + it) % cands.size] }
            .minBy { (it.active.get() + 1) / it.weight(best) }
    }

    @Synchronized
    private fun smoothWeighted(cands: List<VpnLink>): VpnLink {
        val best = cands.maxOf { it.capacityBps }
        var sum = 0.0
        var pick = cands[0]
        for (l in cands) {
            val w = l.weight(best)
            l.swrrCurrent += w
            sum += w
            if (l.swrrCurrent > pick.swrrCurrent) pick = l
        }
        pick.swrrCurrent -= sum
        return pick
    }

    fun reportSuccess(link: VpnLink) {
        link.recentFails.set(0)
    }

    /**
     * Three link-level failures in a row => avoid the link for 10 s
     * (e.g. mobile data switched on but with no internet).
     */
    fun reportFailure(link: VpnLink) {
        link.failures.incrementAndGet()
        if (link.recentFails.incrementAndGet() >= 3) {
            link.badUntil = SystemClock.elapsedRealtime() + 10_000
            link.recentFails.set(0)
        }
    }

    /**
     * Opens a TCP connection to host:port on the best link, falling back to the
     * other link if it fails. `host` is a domain name or an IP literal.
     */
    fun connect(host: String, port: Int, isName: Boolean): Pair<VpnLink, Socket> {
        var last: Exception? = null
        val failed = ArrayList<VpnLink>(2)
        // A private/LAN address (e.g. a local hostname) is only reachable on Wi-Fi.
        val local = !isName && runCatching { Routes.isLocal(InetAddress.getByName(host)) }.getOrDefault(false)
        val candidates = if (local) order(host).sortedBy { if (it === links[0]) 0 else 1 } else order(host)
        for (link in candidates) {
            val net = link.network ?: continue
            try {
                val addrs = resolve(net, host, isName)
                for (a in addrs.take(3)) {
                    val s = net.socketFactory.createSocket()
                    try {
                        s.connect(InetSocketAddress(a, port), CONNECT_TIMEOUT_MS)
                        s.tcpNoDelay = true
                        reportSuccess(link)
                        // Only blame a link when the other one reached the same place:
                        // a site that is down or filtered fails on both and is nobody's fault.
                        failed.forEach { reportFailure(it) }
                        return link to s
                    } catch (e: IOException) {
                        last = e
                        try { s.close() } catch (_: IOException) {}
                    }
                }
            } catch (e: Exception) {
                last = e
            }
            failed += link
        }
        throw IOException("no link could connect to $host:$port", last)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 7000

        /** DNS for a name is resolved over the same link the connection will use. */
        fun resolve(net: Network, host: String, isName: Boolean): List<InetAddress> {
            if (!isName) return listOf(InetAddress.getByName(host))
            val all = net.getAllByName(host).toList()
            // IPv4 first: mobile networks often have broken or missing IPv6.
            return all.filterIsInstance<Inet4Address>() + all.filter { it !is Inet4Address }
        }
    }
}
