package com.duallink.downloader

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Integration tests against a local HTTP server that can:
 * support Range or not, cut responses short, and change the file mid-download.
 */
class DualLinkEngineTest {

    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private lateinit var dir: File

    // Server behaviour, set per test
    @Volatile private var content = Random(1).nextBytes(3 * 1024 * 1024 + 123)
    @Volatile private var etag = "\"v1\""
    @Volatile private var supportRange = true
    @Volatile private var truncateFirst = 0          // cut this many range responses in half
    @Volatile private var changeAfterRequests = -1   // swap file + ETag after N range requests
    private val rangeRequests = AtomicInteger(0)
    private val truncated = AtomicInteger(0)

    /** Both "links" are plain local connections. */
    private val direct = object : Dialer {
        override fun available() = true
        override fun open(u: URL) = u.openConnection() as HttpURLConnection
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
        server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        pool.execute {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                pool.execute { s.use { handle(it) } }
            }
        }
    }

    @After fun tearDown() {
        server.close()
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    /** Minimal HTTP/1.1: one request per connection, then close. */
    private class Req(val headers: Map<String, String>)

    private fun readRequest(s: Socket): Req {
        val inp = s.getInputStream()
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) break
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
            } else {
                sb.append(b.toChar())
            }
        }
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        return Req(h)
    }

    private fun writeHead(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun handle(sock: Socket) {
        try {
            val req = readRequest(sock)
            val out = sock.getOutputStream()
            val range = req.headers["range"]
            val ifRange = req.headers["if-range"]
            var body = content
            val m = range?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
            val partial = supportRange && m != null && (ifRange == null || ifRange == etag)
            if (!partial) {
                writeHead(out, "200 OK", mapOf(
                    "ETag" to etag, "Content-Type" to "application/octet-stream",
                    "Content-Length" to body.size.toString(),
                ))
                out.write(body)
                out.flush()
                return
            }
            val n = rangeRequests.incrementAndGet()
            if (changeAfterRequests in 0 until n) {
                // The file is replaced on the server.
                content = Random(2).nextBytes(content.size)
                etag = "\"v2\""
                body = content
            }
            val start = m!!.groupValues[1].toInt()
            val end = minOf(m.groupValues[2].toInt(), body.size - 1)
            val len = end - start + 1
            writeHead(out, "206 Partial Content", mapOf(
                "ETag" to etag, "Content-Type" to "application/octet-stream",
                "Content-Range" to "bytes $start-$end/${body.size}",
                "Content-Length" to len.toString(),
            ))
            if (len > 1 && truncated.get() < truncateFirst && truncated.incrementAndGet() <= truncateFirst) {
                out.write(body, start, len / 2) // then drop the connection
                out.flush()
                return
            }
            out.write(body, start, len)
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun engine(links: Int = 2, threads: Int = 3) = DualLinkEngine(
        "http://127.0.0.1:${server.localPort}/file.bin",
        dir,
        List(links) { LinkStats("link$it", direct) },
        threads,
    )

    @Test fun segmentedDownloadIsByteExact() {
        val e = engine()
        val f = e.run()
        assertTrue(e.rangeSupported)
        assertEquals("\"v1\"", e.etag)
        assertArrayEquals(content, f.readBytes())
        assertEquals(content.size.toLong(), e.written.get())
        assertTrue("both links used", e.links.all { it.bytes.get() > 0 })
        assertTrue("used many range requests", rangeRequests.get() > 4)
    }

    @Test fun serverWithoutRangeFallsBackToSingleConnection() {
        supportRange = false
        val e = engine()
        val f = e.run()
        assertFalse(e.rangeSupported)
        assertArrayEquals(content, f.readBytes())
    }

    @Test fun cutConnectionsAreRetried() {
        truncateFirst = 4
        val e = engine()
        val f = e.run()
        assertEquals(4, truncated.get())
        assertArrayEquals(content, f.readBytes())
        assertTrue(e.links.sumOf { it.errors.get() } >= 4)
    }

    @Test fun fileChangedMidDownloadFailsInsteadOfMixingVersions() {
        changeAfterRequests = 3
        try {
            engine().run()
            fail("must not report success for a file that changed")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e is FileChangedException || e.message!!.contains("عوض"))
        }
    }

    @Test fun smallFileAndSingleLinkWork() {
        content = Random(3).nextBytes(1000)
        val e = engine(links = 1, threads = 1)
        assertArrayEquals(content, e.run().readBytes())
    }

    @Test fun downLinksAreNotUsed() {
        val down = object : Dialer {
            override fun available() = false
            override fun open(u: URL): HttpURLConnection = throw AssertionError("must not dial a down link")
        }
        val e = DualLinkEngine(
            "http://127.0.0.1:${server.localPort}/file.bin", dir,
            listOf(LinkStats("up", direct), LinkStats("down", down)), 2,
        )
        assertArrayEquals(content, e.run().readBytes())
    }
}
