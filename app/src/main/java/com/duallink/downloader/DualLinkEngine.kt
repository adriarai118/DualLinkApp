package com.duallink.downloader

import android.net.Network
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One internet link (Wi-Fi or mobile data) and its live statistics. */
class LinkStats(val name: String, private val networkProvider: () -> Network?) {
    val bytes = AtomicLong(0)
    val activeWorkers = AtomicInteger(0)
    val errors = AtomicInteger(0)
    @Volatile var speedBps = 0.0
    @Volatile var lastError: String? = null
    internal var lastBytes = 0L

    val network: Network? get() = networkProvider()
}

/** A byte range [start..end] of the file. `end` can shrink when another worker steals the tail. */
class Segment(val start: Long, @Volatile var end: Long) {
    @Volatile var pos: Long = start
    fun remaining(): Long = end - pos + 1
}

/**
 * Downloads one file over several links at once.
 *
 * The file is cut into segments; every link runs N worker threads, and each worker
 * pulls the next segment and fetches it with an HTTP Range request over its own
 * network. Faster links simply finish more segments. When the queue runs dry, an idle
 * worker steals the second half of the largest unfinished segment, so a slow link
 * never holds up the end of the download.
 */
class DualLinkEngine(
    private val url: String,
    private val dir: File,
    val links: List<LinkStats>,
    private val threadsPerLink: Int,
) {
    @Volatile var cancelled = false
        private set
    @Volatile var total = -1L
        private set
    @Volatile var fileName = ""
        private set
    @Volatile var mime: String? = null
        private set
    @Volatile var rangeSupported = false
        private set
    @Volatile var outFile: File? = null
        private set
    @Volatile var totalSpeedBps = 0.0
        private set
    val written = AtomicLong(0)

    private lateinit var finalUrl: URL
    private val lock = Any()
    private val queue = ArrayDeque<Segment>()
    private val inFlight = mutableListOf<Segment>()
    private val openConns = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun cancel() {
        cancelled = true
        val snapshot = synchronized(openConns) { openConns.toList() }
        for (c in snapshot) {
            try {
                c.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Runs the whole download on the calling thread and returns the finished file. */
    fun run(): File {
        probe()
        val file = uniqueFile(dir, fileName)
        outFile = file

        val meter = Thread({ meterLoop() }, "dl-meter").apply { isDaemon = true; start() }
        try {
            if (!rangeSupported || total <= 0) {
                singleStream(file)
            } else {
                segmented(file)
            }
        } finally {
            meter.interrupt()
        }
        if (cancelled) throw IOException("لغو شد")
        return file
    }

    // ---------------------------------------------------------------- probing

    private fun probe() {
        var last: Exception? = null
        for (link in links) {
            val net = link.network ?: continue
            try {
                probeOn(net)
                return
            } catch (e: Exception) {
                last = e
                link.lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IOException("دسترسی به لینک ممکن نشد: ${last?.message ?: "شبکه‌ای وصل نیست"}")
    }

    private fun probeOn(net: Network) {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val c = open(net, current)
            c.instanceFollowRedirects = false
            c.setRequestProperty("Range", "bytes=0-0")
            try {
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("ریدایرکت بدون مقصد")
                    current = URL(current, loc)
                    if (++redirects > 10) throw IOException("ریدایرکت‌های بیش از حد")
                    continue
                }
                finalUrl = current
                mime = c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                fileName = fileNameFrom(c.getHeaderField("Content-Disposition"), current)
                when (code) {
                    206 -> {
                        val t = c.getHeaderField("Content-Range")
                            ?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                        total = t
                        rangeSupported = t > 0
                    }
                    200 -> {
                        total = c.contentLengthLong
                        rangeSupported = false
                    }
                    else -> throw IOException("پاسخ سرور: HTTP $code")
                }
                return
            } finally {
                c.disconnect()
            }
        }
    }

    // --------------------------------------------------------- segmented mode

    private fun segmented(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(total)
            val ch = raf.channel

            val workers = links.size * threadsPerLink
            val chunk = (total / (workers * 4L)).coerceIn(MIN_CHUNK, MAX_CHUNK)
            synchronized(lock) {
                var s = 0L
                while (s < total) {
                    val e = minOf(s + chunk - 1, total - 1)
                    queue.addLast(Segment(s, e))
                    s = e + 1
                }
            }

            val threads = mutableListOf<Thread>()
            for (link in links) {
                repeat(threadsPerLink) { i ->
                    threads += Thread({ worker(link, ch) }, "dl-${link.name}-$i").apply { start() }
                }
            }
            threads.forEach { it.join() }

            if (cancelled) return
            if (!isFinished()) {
                val why = links.mapNotNull { l -> l.lastError?.let { "${l.name}: $it" } }.joinToString(" | ")
                throw IOException("دانلود ناقص ماند؛ همه اتصال‌ها خطا دادند. $why")
            }
            ch.force(false)
        }
    }

    private fun worker(link: LinkStats, ch: FileChannel) {
        link.activeWorkers.incrementAndGet()
        val buf = ByteArray(BUF_SIZE)
        var fails = 0
        var waitedForNetwork = 0
        try {
            while (!cancelled) {
                val net = link.network
                if (net == null) {
                    // The link dropped; wait for it to come back for a while.
                    if (isFinished() || ++waitedForNetwork > NETWORK_WAIT_SECONDS) return
                    Thread.sleep(1000)
                    continue
                }
                waitedForNetwork = 0

                val seg = nextSegment()
                if (seg == null) {
                    if (isFinished()) return
                    Thread.sleep(250)
                    continue
                }
                try {
                    fetch(net, link, seg, ch, buf)
                    release(seg, requeue = false)
                    fails = 0
                } catch (e: Exception) {
                    release(seg, requeue = true)
                    if (cancelled) return
                    link.errors.incrementAndGet()
                    link.lastError = e.message ?: e.javaClass.simpleName
                    fails++
                    if (fails >= MAX_CONSECUTIVE_FAILS) return
                    Thread.sleep(minOf(1000L * fails, 5000L))
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            link.activeWorkers.decrementAndGet()
        }
    }

    private fun nextSegment(): Segment? {
        synchronized(lock) {
            val queued = queue.removeFirstOrNull()
            if (queued != null) {
                inFlight.add(queued)
                return queued
            }
            // Nothing queued: steal the second half of the biggest unfinished segment.
            val victim = inFlight.maxByOrNull { it.remaining() } ?: return null
            val rem = victim.remaining()
            if (rem < MIN_STEAL) return null
            val mid = victim.pos + rem / 2
            val stolen = Segment(mid, victim.end)
            victim.end = mid - 1
            inFlight.add(stolen)
            return stolen
        }
    }

    private fun release(seg: Segment, requeue: Boolean) {
        synchronized(lock) {
            inFlight.remove(seg)
            if (requeue && seg.pos <= seg.end) queue.addFirst(Segment(seg.pos, seg.end))
        }
    }

    private fun isFinished(): Boolean {
        synchronized(lock) {
            return queue.isEmpty() && inFlight.isEmpty()
        }
    }

    private fun fetch(net: Network, link: LinkStats, seg: Segment, ch: FileChannel, buf: ByteArray) {
        val c = open(net, finalUrl)
        c.setRequestProperty("Range", "bytes=${seg.pos}-${seg.end}")
        openConns.add(c)
        try {
            val code = c.responseCode
            if (code != 206) throw IOException("HTTP $code (انتظار 206)")
            val cr = c.getHeaderField("Content-Range") ?: throw IOException("Content-Range ندارد")
            val start = cr.substringAfter("bytes", "").trim().substringBefore('-').trim().toLongOrNull()
            if (start != seg.pos) throw IOException("بازه اشتباه از سرور: $cr")

            c.inputStream.use { input ->
                while (!cancelled) {
                    val end = seg.end // may shrink if a worker stole our tail
                    if (seg.pos > end) break
                    val want = minOf(buf.size.toLong(), end - seg.pos + 1).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) {
                        if (seg.pos <= seg.end) throw IOException("اتصال زودتر از موعد بسته شد")
                        break
                    }
                    writeAt(ch, buf, n, seg.pos)
                    seg.pos += n
                    link.bytes.addAndGet(n.toLong())
                    written.addAndGet(n.toLong())
                }
            }
        } finally {
            openConns.remove(c)
            c.disconnect()
        }
    }

    // ------------------------------------------------- server without Range

    private fun singleStream(file: File) {
        var last: Exception? = null
        for (link in links) {
            val net = link.network ?: continue
            val c = open(net, finalUrl)
            openConns.add(c)
            try {
                written.set(0)
                val code = c.responseCode
                if (code != 200) throw IOException("HTTP $code")
                if (total <= 0) total = c.contentLengthLong
                link.activeWorkers.incrementAndGet()
                try {
                    c.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(BUF_SIZE)
                            while (!cancelled) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                link.bytes.addAndGet(n.toLong())
                                written.addAndGet(n.toLong())
                            }
                        }
                    }
                } finally {
                    link.activeWorkers.decrementAndGet()
                }
                return
            } catch (e: Exception) {
                if (cancelled) return
                last = e
                link.lastError = e.message ?: e.javaClass.simpleName
            } finally {
                openConns.remove(c)
                c.disconnect()
            }
        }
        throw IOException("دانلود ناموفق: ${last?.message}")
    }

    // ------------------------------------------------------------- helpers

    private fun meterLoop() {
        try {
            while (true) {
                Thread.sleep(1000)
                var sum = 0.0
                for (l in links) {
                    val now = l.bytes.get()
                    val delta = (now - l.lastBytes).toDouble()
                    l.lastBytes = now
                    l.speedBps = if (l.speedBps == 0.0) delta else l.speedBps * 0.4 + delta * 0.6
                    sum += l.speedBps
                }
                totalSpeedBps = sum
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun open(net: Network, u: URL): HttpURLConnection {
        val c = net.openConnection(u) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        // Without this Android may transparently gzip, which breaks byte ranges.
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("User-Agent", USER_AGENT)
        return c
    }

    private fun writeAt(ch: FileChannel, buf: ByteArray, n: Int, position: Long) {
        val bb = ByteBuffer.wrap(buf, 0, n)
        var p = position
        while (bb.hasRemaining()) p += ch.write(bb, p) // positional write is thread-safe
    }

    companion object {
        private const val BUF_SIZE = 64 * 1024
        private const val MIN_CHUNK = 512L * 1024
        private const val MAX_CHUNK = 8L * 1024 * 1024
        private const val MIN_STEAL = 512L * 1024
        private const val MAX_CONSECUTIVE_FAILS = 6
        private const val NETWORK_WAIT_SECONDS = 60
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun fileNameFrom(disposition: String?, u: URL): String {
            var name: String? = null
            if (disposition != null) {
                val star = Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)
                name = if (star != null) {
                    runCatching {
                        URLDecoder.decode(star.groupValues[2].trim(), star.groupValues[1].ifBlank { "UTF-8" })
                    }.getOrNull()
                } else {
                    Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                        .find(disposition)?.groupValues?.get(1)?.trim()
                }
            }
            if (name.isNullOrBlank()) {
                val last = u.path.substringAfterLast('/')
                name = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            }
            if (name.isNullOrBlank()) name = "download.bin"
            return name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(150)
        }

        fun uniqueFile(dir: File, name: String): File {
            dir.mkdirs()
            var f = File(dir, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            var i = 1
            while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
            return f
        }
    }
}
