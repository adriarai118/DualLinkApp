package com.duallink.downloader

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel talks to.
 *
 * Supports exactly what the tunnel sends:
 *  - CONNECT (0x01): TCP. Each connection is placed on a link by the Balancer.
 *  - FWD_UDP (0x05): hev's "UDP in TCP" mode. Each UDP flow (e.g. one QUIC
 *    connection) is framed over one TCP stream as
 *    [datlen:u16][hdrlen:u8 = 3+addrlen][ATYP ADDR PORT][payload].
 *    A whole flow stays on one link, since QUIC breaks if its packets change IP.
 */
class SocksServer(private val balancer: Balancer) {

    private val server = ServerSocket(0, 1024, InetAddress.getByName("127.0.0.1"))
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var running = true
    val port: Int get() = server.localPort
    val totalConnections = AtomicLong(0)

    fun start() {
        Thread({ acceptLoop() }, "socks-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: IOException) {}
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { closeQuietly(it) }
    }

    private fun acceptLoop() {
        while (running) {
            val c = try {
                server.accept()
            } catch (e: IOException) {
                if (!running) return
                continue
            }
            clients.add(c)
            spawn("socks-conn") {
                try {
                    handle(c)
                } catch (_: Exception) {
                } finally {
                    clients.remove(c)
                    closeQuietly(c)
                }
            }
        }
    }

    private fun handle(c: Socket) {
        c.tcpNoDelay = true
        c.soTimeout = 20_000 // only while handshaking
        val inp = DataInputStream(BufferedInputStream(c.getInputStream(), 16 * 1024))
        val out = c.getOutputStream()

        // Greeting: VER NMETHODS METHODS...
        if (inp.readUnsignedByte() != 5) return
        val n = inp.readUnsignedByte()
        inp.skipFully(n)
        out.write(byteArrayOf(5, 0)) // no authentication
        out.flush()

        // Request: VER CMD RSV ATYP DST.ADDR DST.PORT
        if (inp.readUnsignedByte() != 5) return
        val cmd = inp.readUnsignedByte()
        inp.readUnsignedByte()
        val dst = readAddress(inp)

        when (cmd) {
            1 -> tcpConnect(c, inp, out, dst)
            5 -> udpForward(c, inp, out)
            else -> reply(out, 7) // command not supported
        }
    }

    // ------------------------------------------------------------------ TCP

    private fun tcpConnect(c: Socket, inp: InputStream, out: OutputStream, dst: SocksAddr) {
        val (link, remote) = try {
            balancer.connect(dst.host, dst.port, dst.isName)
        } catch (e: Exception) {
            reply(out, 5) // connection refused
            return
        }
        clients.add(remote)
        link.active.incrementAndGet()
        link.total.incrementAndGet()
        totalConnections.incrementAndGet()
        try {
            reply(out, 0)
            c.soTimeout = 0

            val downstream = spawn("socks-down") {
                try {
                    pump(remote.getInputStream(), out, link.down)
                } catch (_: Exception) {
                } finally {
                    try { c.shutdownOutput() } catch (_: Exception) {}
                }
            }
            try {
                pump(inp, remote.getOutputStream(), link.up)
                try { remote.shutdownOutput() } catch (_: Exception) {}
            } catch (_: Exception) {
                closeQuietly(remote)
            }
            downstream.join()
        } finally {
            link.active.decrementAndGet()
            clients.remove(remote)
            closeQuietly(remote)
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun udpForward(c: Socket, inp: DataInputStream, out: OutputStream) {
        val link = balancer.order("udp").firstOrNull()
        val net = link?.network
        if (link == null || net == null) {
            reply(out, 1)
            return
        }
        val ds = DatagramSocket()
        net.bindSocket(ds)
        link.active.incrementAndGet()
        link.total.incrementAndGet()
        totalConnections.incrementAndGet()
        try {
            reply(out, 0)
            c.soTimeout = 0

            // Remote -> app
            val downstream = spawn("udp-down") {
                val buf = ByteArray(65535)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        pkt.setData(buf, 0, buf.size)
                        ds.receive(pkt)
                        val addr = encodeAddress(pkt.address, pkt.port)
                        val frame = ByteArray(3 + addr.size + pkt.length)
                        frame[0] = (pkt.length ushr 8).toByte()
                        frame[1] = pkt.length.toByte()
                        frame[2] = (3 + addr.size).toByte()
                        System.arraycopy(addr, 0, frame, 3, addr.size)
                        System.arraycopy(buf, 0, frame, 3 + addr.size, pkt.length)
                        synchronized(out) { out.write(frame); out.flush() }
                        link.down.addAndGet(pkt.length.toLong())
                    }
                } catch (_: Exception) {
                } finally {
                    closeQuietly(c)
                }
            }

            // App -> remote
            val dnsCache = HashMap<String, InetAddress>()
            val data = ByteArray(65535)
            try {
                while (true) {
                    val datlen = inp.readUnsignedShort()
                    inp.readUnsignedByte() // hdrlen; the address below tells us its size
                    val dst = readAddress(inp)
                    inp.readFully(data, 0, datlen)
                    val target = if (dst.isName) {
                        dnsCache.getOrPut(dst.host) {
                            Balancer.resolve(net, dst.host, true).first()
                        }
                    } else {
                        InetAddress.getByName(dst.host)
                    }
                    ds.send(DatagramPacket(data, datlen, InetSocketAddress(target, dst.port)))
                    link.up.addAndGet(datlen.toLong())
                }
            } catch (_: Exception) {
            } finally {
                ds.close()
                downstream.join(2000)
            }
        } finally {
            link.active.decrementAndGet()
            ds.close()
        }
    }

    // -------------------------------------------------------------- helpers

    class SocksAddr(val host: String, val port: Int, val isName: Boolean)

    private fun readAddress(inp: DataInputStream): SocksAddr {
        return when (val atyp = inp.readUnsignedByte()) {
            1 -> {
                val b = ByteArray(4); inp.readFully(b)
                SocksAddr(InetAddress.getByAddress(b).hostAddress!!, inp.readUnsignedShort(), false)
            }
            4 -> {
                val b = ByteArray(16); inp.readFully(b)
                SocksAddr(InetAddress.getByAddress(b).hostAddress!!, inp.readUnsignedShort(), false)
            }
            3 -> {
                val len = inp.readUnsignedByte()
                val b = ByteArray(len); inp.readFully(b)
                SocksAddr(String(b, Charsets.US_ASCII), inp.readUnsignedShort(), true)
            }
            else -> throw IOException("bad ATYP $atyp")
        }
    }

    private fun encodeAddress(a: InetAddress, port: Int): ByteArray {
        val raw = a.address
        val atyp: Byte = if (a is Inet6Address) 4 else 1
        val r = ByteArray(1 + raw.size + 2)
        r[0] = atyp
        System.arraycopy(raw, 0, r, 1, raw.size)
        r[r.size - 2] = (port ushr 8).toByte()
        r[r.size - 1] = port.toByte()
        return r
    }

    private fun reply(out: OutputStream, rep: Int) {
        // VER REP RSV ATYP=IPv4 0.0.0.0:0
        out.write(byteArrayOf(5, rep.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pump(src: InputStream, dst: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n < 0) return
            dst.write(buf, 0, n)
            counter.addAndGet(n.toLong())
        }
    }

    private fun DataInputStream.skipFully(n: Int) {
        var left = n
        while (left > 0) {
            if (read() < 0) throw EOFException()
            left--
        }
    }

    private fun spawn(name: String, body: () -> Unit): Thread =
        Thread(null, Runnable { body() }, name, 256 * 1024).apply { isDaemon = true; start() }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: Exception) {}
    }
}
