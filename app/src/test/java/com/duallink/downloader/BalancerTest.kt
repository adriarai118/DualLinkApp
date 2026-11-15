package com.duallink.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancerTest {

    /** A link without an Android Network behind it. */
    private class FakeLink(name: String, var isUp: Boolean = true) : VpnLink(name, { null }) {
        override val available: Boolean get() = isUp
    }

    private fun pickMany(b: Balancer, n: Int, holdConnections: Boolean): Map<String, Int> {
        val counts = HashMap<String, Int>()
        repeat(n) {
            val first = b.order("host$it").first()
            if (holdConnections) first.active.incrementAndGet()
            counts[first.name] = (counts[first.name] ?: 0) + 1
        }
        return counts
    }

    @Test fun equalLinksShareEvenly() {
        val b = Balancer(listOf(FakeLink("wifi"), FakeLink("cell")))
        val c = pickMany(b, 100, holdConnections = true)
        assertEquals(50, c["wifi"])
        assertEquals(50, c["cell"])
    }

    @Test fun fasterLinkGetsMoreButSlowerIsNeverStarved() {
        val wifi = FakeLink("wifi").apply { capacityBps = 10_000_000.0 }
        val cell = FakeLink("cell").apply { capacityBps = 500_000.0 }
        val c = pickMany(Balancer(listOf(wifi, cell)), 300, holdConnections = true)
        val cellShare = (c["cell"] ?: 0) / 300.0
        // Weight floor is 50% of the best link => cell gets about a third.
        assertTrue("cell share $cellShare", cellShare in 0.28..0.40)
    }

    @Test fun downLinkIsSkipped() {
        val cell = FakeLink("cell", isUp = false)
        val b = Balancer(listOf(FakeLink("wifi"), cell))
        assertEquals(listOf("wifi"), b.order("x").map { it.name })
        cell.isUp = true
        assertEquals(2, b.order("x").size)
    }

    @Test fun stickyKeepsHostOnOneLink() {
        val b = Balancer(listOf(FakeLink("wifi"), FakeLink("cell"))).apply { sticky = true }
        val first = b.order("bank.example").first().name
        repeat(20) { assertEquals(first, b.order("bank.example").first().name) }
        val used = (0 until 50).map { b.order("site$it.example").first().name }.toSet()
        assertEquals(setOf("wifi", "cell"), used)
    }

    @Test fun threeLinkFailuresBenchTheLink() {
        val wifi = FakeLink("wifi")
        val cell = FakeLink("cell")
        val b = Balancer(listOf(wifi, cell))
        repeat(3) { b.reportFailure(cell) }
        // Benched cell is still offered, but last.
        repeat(10) { assertEquals("wifi", b.order("h$it").first().name) }
        assertEquals("cell", b.order("h").last().name)
    }

    @Test fun successResetsFailureStreak() {
        val cell = FakeLink("cell")
        val b = Balancer(listOf(FakeLink("wifi"), cell))
        b.reportFailure(cell); b.reportFailure(cell)
        b.reportSuccess(cell)
        b.reportFailure(cell); b.reportFailure(cell)
        assertEquals(0L, cell.badUntil)
    }

    @Test fun weightedRoundRobinWhenConnectionsAreNotCounted() {
        val b = Balancer(listOf(FakeLink("wifi"), FakeLink("cell")), countsActive = false)
        val c = pickMany(b, 100, holdConnections = false)
        assertEquals(50, c["wifi"])
        assertEquals(50, c["cell"])
    }
}
