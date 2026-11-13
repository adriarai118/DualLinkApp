package com.duallink.downloader

import android.content.Context
import android.net.Network
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-connection speed test straight over each link (not through the VPN):
 *   download Wi-Fi only -> download mobile only -> download both -> upload both.
 * Each phase runs several parallel connections per link, so the number is what the
 * link can do, not what one connection can do.
 */
object SpeedTest {

    const val DEFAULT_DOWN = "https://speed.cloudflare.com/__down?bytes=200000000"
    const val DEFAULT_UP = "https://speed.cloudflare.com/__up"
    private const val CONNS_PER_LINK = 4
    private const val PHASE_MS = 8000L
    private const val WARMUP_MS = 1500L

    @Volatile var running = false
    @Volatile var phase = ""
    @Volatile var results: List<String> = emptyList()
    @Volatile private var stop = false
    private val open = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun cancel() {
        stop = true
        disconnectAll()
    }

    private fun disconnectAll() {
        synchronized(open) { open.toList() }.forEach { runCatching { it.disconnect() } }
    }

    fun startAsync(ctx: Context, downUrl: String, upUrl: String) {
        if (running) return
        running = true
        stop = false
        results = emptyList()
        val app = ctx.applicationContext
        Thread({
            val lm = LinkManager(app).also { it.start(useWifi = true, useCell = true) }
            val out = ArrayList<String>()
            try {
                phase = "در انتظار وصل شدن وای‌فای و دیتا…"
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (SystemClock.elapsedRealtime() < deadline && (lm.wifi == null || lm.cell == null)) Thread.sleep(150)
                val wifi = lm.wifi
                val cell = lm.cell
                wifiNet = wifi
                SpeedHistory.clear()

                fun run(title: String, nets: List<Pair<String, Network?>>, upload: Boolean) {
                    if (stop) return
                    val usable = nets.filter { it.second != null }
                    if (usable.isEmpty()) {
                        out += "$title: در دسترس نیست"
                        results = out.toList()
                        return
                    }
                    phase = "$title…"
                    SpeedHistory.label = title
                    val r = measure(usable.map { it.second!! }, if (upload) upUrl else downUrl, upload)
                    val parts = usable.mapIndexed { i, (name, _) -> "$name ${SpeedChart.fmtMbps(r[i])}" }
                    val sum = r.sum()
                    out += if (usable.size > 1) "$title: ${SpeedChart.fmtMbps(sum)}  (${parts.joinToString(" + ")})"
                           else "$title: ${SpeedChart.fmtMbps(sum)}"
                    results = out.toList()
                }

                run("دانلود فقط وای‌فای", listOf("وای‌فای" to wifi), false)
                run("دانلود فقط دیتا", listOf("دیتا" to cell), false)
                run("دانلود هر دو", listOf("وای‌فای" to wifi, "دیتا" to cell), false)
                run("آپلود هر دو", listOf("وای‌فای" to wifi, "دیتا" to cell), true)
                phase = if (stop) "متوقف شد" else "تمام شد ✓"
            } catch (e: Exception) {
                phase = "خطا: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                SpeedHistory.label = ""
                lm.stop()
                running = false
            }
        }, "speedtest").start()
    }

    /** Runs one phase; returns average bytes/s per network (after warm-up). */
    private fun measure(nets: List<Network>, url: String, upload: Boolean): DoubleArray {
        val counters = nets.map { AtomicLong(0) }
        val t0 = SystemClock.elapsedRealtime()
        val end = t0 + PHASE_MS
        val threads = ArrayList<Thread>()
        for ((i, net) in nets.withIndex()) {
            repeat(CONNS_PER_LINK) {
                threads += Thread({
                    while (!stop && SystemClock.elapsedRealtime() < end) {
                        try {
                            if (upload) uploadOnce(net, url, counters[i], end) else downloadOnce(net, url, counters[i], end)
                        } catch (_: Exception) {
                            Thread.sleep(300)
                        }
                    }
                }, "st-$i").apply { isDaemon = true; start() }
            }
        }

        // Sample once a second for the chart; take the post-warm-up average as the result.
        val last = LongArray(nets.size)
        val atWarm = LongArray(nets.size)
        var warmed = false
        while (!stop && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(1000)
            val now = LongArray(nets.size) { counters[it].get() }
            val rate = DoubleArray(nets.size) { (now[it] - last[it]).toDouble() }
            now.copyInto(last)
            // Chart: first network is Wi-Fi when both run; a single network goes to its own line.
            if (nets.size > 1) SpeedHistory.push(rate[0], rate[1])
            else if (currentIsWifi(nets[0])) SpeedHistory.push(rate[0], 0.0) else SpeedHistory.push(0.0, rate[0])
            if (!warmed && SystemClock.elapsedRealtime() - t0 >= WARMUP_MS) {
                now.copyInto(atWarm)
                warmed = true
            }
        }
        disconnectAll()
        threads.forEach { it.join(2000) }
        val secs = (PHASE_MS - WARMUP_MS) / 1000.0
        return DoubleArray(nets.size) { (counters[it].get() - atWarm[it]) / secs }
    }

    @Volatile private var wifiNet: Network? = null
    private fun currentIsWifi(n: Network) = n == wifiNet

    private fun downloadOnce(net: Network, url: String, counter: AtomicLong, end: Long) {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept-Encoding", "identity")
        c.useCaches = false
        open.add(c)
        try {
            c.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (!stop && SystemClock.elapsedRealtime() < end) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    counter.addAndGet(n.toLong())
                }
            }
        } finally {
            open.remove(c)
            c.disconnect()
        }
    }

    private fun uploadOnce(net: Network, url: String, counter: AtomicLong, end: Long) {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/octet-stream")
        val size = 25L * 1024 * 1024
        c.setFixedLengthStreamingMode(size)
        open.add(c)
        try {
            val buf = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
            c.outputStream.use { o ->
                var sent = 0L
                while (!stop && sent < size && SystemClock.elapsedRealtime() < end) {
                    val n = minOf(buf.size.toLong(), size - sent).toInt()
                    o.write(buf, 0, n)
                    sent += n
                    counter.addAndGet(n.toLong())
                }
                if (sent < size) return // time is up: drop the request
            }
            runCatching { c.responseCode }
        } finally {
            open.remove(c)
            c.disconnect()
        }
    }
}
