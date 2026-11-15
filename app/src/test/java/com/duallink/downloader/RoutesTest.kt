package com.duallink.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RoutesTest {

    private fun ipToLong(ip: String) = ip.split('.').fold(0L) { a, p -> (a shl 8) or p.toLong() }

    private fun routed(ip: String): Boolean {
        val v = ipToLong(ip)
        return Routes.V4.any { (net, len) ->
            val start = ipToLong(net)
            val size = 1L shl (32 - len)
            v >= start && v < start + size
        }
    }

    @Test fun publicAddressesGoThroughVpn() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "146.70.20.147", "185.143.232.1", "223.255.255.255")) {
            assertTrue("$ip should be routed", routed(ip))
        }
    }

    @Test fun tunnelInternalsStayInsideVpn() {
        assertTrue("mapped DNS", routed("198.18.0.2"))
        assertTrue("fake IP range", routed("240.0.0.1"))
        assertTrue("fake IP range end", routed("255.255.255.254"))
    }

    @Test fun localNetworksBypassVpn() {
        for (ip in listOf(
            "192.168.1.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "169.254.1.1",
            "127.0.0.1", "100.64.0.1", "100.127.255.255", "224.0.0.251", "255.255.255.255", "0.0.0.1",
        )) {
            assertFalse("$ip should bypass", routed(ip))
        }
        assertTrue("172.32 is public", routed("172.32.0.1"))
        assertTrue("100.128 is public", routed("100.128.0.1"))
    }

    @Test fun routesDoNotOverlap() {
        val ranges = Routes.V4.map { (net, len) -> ipToLong(net) to ipToLong(net) + (1L shl (32 - len)) - 1 }
            .sortedBy { it.first }
        for (i in 1 until ranges.size) assertTrue(ranges[i].first > ranges[i - 1].second)
        // 2^32 minus the excluded space
        val covered = ranges.sumOf { it.second - it.first + 1 }
        val excluded = listOf(24, 24, 22, 24, 16, 20, 16, 28, 0).sumOf { 1L shl it }
        assertEquals((1L shl 32) - excluded, covered)
    }

    @Test fun isLocalClassifiesAddresses() {
        assertTrue(Routes.isLocal(InetAddress.getByName("192.168.0.10")))
        assertTrue(Routes.isLocal(InetAddress.getByName("100.70.1.1")))
        assertTrue(Routes.isLocal(InetAddress.getByName("fd00::1")))
        assertFalse(Routes.isLocal(InetAddress.getByName("8.8.4.4")))
        assertFalse(Routes.isLocal(InetAddress.getByName("2001:4860::8888")))
    }
}
