package com.duallink.downloader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Vectors produced by an independent implementation (Python `cryptography`). */
class LegacyShadowsocksTest {

    private val password = "14fFPrbezE3HDZzsMOr6"
    private val plain = "HTTP/1.1 200 OK\r\n\r\nhello from server".toByteArray()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun readAll(r: LegacyShadowsocks.Reader): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(7) // small buffer: exercises partial reads
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun keyDerivationMatchesOpenSsl() {
        assertEquals("f254e2f018d745bcc491844f84c38610",
            LegacyShadowsocks.bytesToKey(password.toByteArray(), 16).hex())
        assertEquals("f254e2f018d745bcc491844f84c386104931905583afee8d3a8cd62f2865caa7",
            LegacyShadowsocks.bytesToKey(password.toByteArray(), 32).hex())
    }

    @Test fun rc4Md5DecryptsServerStream() {
        val ss = LegacyShadowsocks("rc4-md5", password)
        val stream = hex("000102030405060708090a0b0c0d0e0f3c3eb0e6a9af53ccca47e7807227f45024603cee273f65fc0a6e1cc85d3b2fa5105c0666")
        assertArrayEquals(plain, readAll(ss.Reader(ByteArrayInputStream(stream))))
    }

    @Test fun aesCtrDecryptsServerStream() {
        val ss = LegacyShadowsocks("aes-128-ctr", password)
        val stream = hex("000102030405060708090a0b0c0d0e0fd2d130ec44f5a178e94509f17bdc8ba8fade2a9b4c4cf1822724e761271ad96dc6018bd8")
        assertArrayEquals(plain, readAll(ss.Reader(ByteArrayInputStream(stream))))
    }

    @Test fun aesCfbDecryptsUdpPacket() {
        val ss = LegacyShadowsocks("aes-256-cfb", password)
        val pkt = hex("000102030405060708090a0b0c0d0e0fc6d8a2e3a169ac0d412fbb7a3fdfa39ff385")
        val expected = byteArrayOf(1, 1, 2, 3, 4, 0, 53) + "dns-payload".toByteArray()
        assertArrayEquals(expected, ss.decryptPacket(pkt, pkt.size))
    }

    @Test fun streamRoundTripForEveryCipher() {
        for (m in listOf("rc4-md5", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ctr", "aes-192-ctr", "aes-256-ctr")) {
            val ss = LegacyShadowsocks(m, password)
            val wire = ByteArrayOutputStream()
            val w = ss.Writer(wire)
            val data = ByteArray(100_000) { (it * 31).toByte() }
            var off = 0
            while (off < data.size) { // uneven chunks
                val n = minOf(1 + off % 4097, data.size - off)
                w.write(data, off, n)
                off += n
            }
            val back = readAll(ss.Reader(ByteArrayInputStream(wire.toByteArray())))
            assertArrayEquals(m, data, back)
            assertFalse(m, wire.toByteArray().copyOfRange(16, 116).contentEquals(data.copyOfRange(0, 100)))
        }
    }

    @Test fun packetRoundTripUsesFreshIv() {
        val ss = LegacyShadowsocks("rc4-md5", password)
        val p = "same payload".toByteArray()
        val a = ss.encryptPacket(p, p.size)
        val b = ss.encryptPacket(p, p.size)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(p, ss.decryptPacket(a, a.size))
        assertEquals(null, ss.decryptPacket(a, 10))
    }
}
