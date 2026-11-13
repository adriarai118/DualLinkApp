package com.duallink.downloader

/**
 * Routes for the VPN interface: all of IPv4 *except* local/private ranges, so LAN
 * traffic (router page, printers, other devices on the Wi-Fi, apps that serve on the
 * local network) never enters the VPN and keeps working exactly as without it.
 *
 * Works on every Android version (excludeRoute() only exists on Android 13+), by
 * emitting the complement of the excluded ranges as a list of prefixes.
 */
object Routes {

    /** Never routed through the VPN. */
    private val EXCLUDED_V4 = listOf(
        "0.0.0.0/8",          // "this" network
        "10.0.0.0/8",         // private
        "100.64.0.0/10",      // carrier-grade NAT (operator-internal)
        "127.0.0.0/8",        // loopback
        "169.254.0.0/16",     // link-local
        "172.16.0.0/12",      // private
        "192.168.0.0/16",     // private
        "224.0.0.0/4",        // multicast (LAN discovery, mDNS)
        "255.255.255.255/32", // broadcast
    )

    /** Public IPv6 only (global unicast); link-local and ULA (fc00::/7) stay off the VPN. */
    val V6: List<Pair<String, Int>> = listOf("2000::" to 3)

    val V4: List<Pair<String, Int>> by lazy {
        val excluded = EXCLUDED_V4.map { cidr ->
            val (ip, len) = cidr.split('/')
            val start = ipToLong(ip)
            val size = 1L shl (32 - len.toInt())
            start to (start + size - 1)
        }
        val out = ArrayList<Pair<String, Int>>()
        fun walk(start: Long, len: Int) {
            val end = start + (1L shl (32 - len)) - 1
            if (excluded.any { (s, e) -> s <= start && end <= e }) return // fully excluded
            if (excluded.none { (s, e) -> s <= end && start <= e }) {    // untouched
                out += longToIp(start) to len
                return
            }
            walk(start, len + 1)
            walk(start + (1L shl (32 - len - 1)), len + 1)
        }
        walk(0L, 0)
        out
    }

    /** True for addresses that belong to the local network side. */
    fun isLocal(a: java.net.InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || a.isMulticastAddress ||
            (a is java.net.Inet4Address && (a.address[0].toInt() and 0xff) == 100 &&
                (a.address[1].toInt() and 0xc0) == 64) ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)

    private fun ipToLong(ip: String): Long =
        ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }

    private fun longToIp(v: Long): String =
        "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
