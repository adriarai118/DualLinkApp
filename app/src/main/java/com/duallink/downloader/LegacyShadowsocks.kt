package com.duallink.downloader

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Old-style ("stream cipher") Shadowsocks, which Xray no longer supports but many
 * free servers still use: rc4-md5, aes-128/192/256-cfb, aes-128/192/256-ctr.
 *
 * TCP wire format:
 *   client -> server: IV || E(ATYP ADDR PORT || payload...)
 *   server -> client: IV || E(payload...)
 * Key = OpenSSL EVP_BytesToKey(MD5, password). For rc4-md5 the RC4 key is MD5(key || IV).
 *
 * Note: these ciphers are weak; prefer modern configs when you have a choice.
 */
class LegacyShadowsocks(val method: String, password: String) {

    private val spec: Spec = SPECS[method.lowercase()]
        ?: throw IllegalArgumentException("روش $method پشتیبانی نمی‌شود")
    private val key: ByteArray = bytesToKey(password.toByteArray(Charsets.UTF_8), spec.keyLen)
    private val random = SecureRandom()

    private class Spec(val keyLen: Int, val ivLen: Int, val transform: String)

    /** A byte-exact stream cipher: output is always exactly as long as input (no block buffering). */
    internal interface Stream {
        fun update(b: ByteArray, off: Int, len: Int): ByteArray
    }

    /** RC4 from the platform: already a true stream cipher. */
    private class JceStream(private val c: Cipher) : Stream {
        override fun update(b: ByteArray, off: Int, len: Int): ByteArray = c.update(b, off, len) ?: ByteArray(0)
    }

    /**
     * AES-CTR / AES-CFB128 built on raw AES blocks. The platform's "AES/CFB" waits for
     * whole 16-byte blocks, which would stall short messages on a live connection.
     */
    private class AesStream(key: ByteArray, iv: ByteArray, private val cfb: Boolean, private val encrypt: Boolean) : Stream {
        private val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        private val reg = iv.copyOf()          // CTR: counter; CFB: last ciphertext block
        private var ks = ByteArray(16)         // current keystream block
        private var used = 16                  // bytes of ks already consumed

        override fun update(b: ByteArray, off: Int, len: Int): ByteArray {
            val out = ByteArray(len)
            for (i in 0 until len) {
                if (used == 16) {
                    ks = aes.doFinal(reg)
                    if (!cfb) increment(reg)
                    used = 0
                }
                val inB = b[off + i]
                val o = (inB.toInt() xor ks[used].toInt()).toByte()
                out[i] = o
                if (cfb) reg[used] = if (encrypt) o else inB // feedback = ciphertext
                used++
            }
            return out
        }

        private fun increment(c: ByteArray) {
            for (i in 15 downTo 0) {
                c[i] = (c[i] + 1).toByte()
                if (c[i].toInt() != 0) break
            }
        }
    }

    internal fun newStream(encrypt: Boolean, iv: ByteArray): Stream {
        return when (spec.transform) {
            "RC4" -> {
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(key)
                md5.update(iv)
                val c = try { Cipher.getInstance("ARC4") } catch (_: Exception) { Cipher.getInstance("RC4") }
                c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(md5.digest(), "RC4"))
                JceStream(c)
            }
            "CFB" -> AesStream(key, iv, cfb = true, encrypt = encrypt)
            else -> AesStream(key, iv, cfb = false, encrypt = encrypt)
        }
    }

    /** Encrypting writer. The IV goes out together with the first chunk. */
    inner class Writer(private val out: OutputStream) {
        private val iv = ByteArray(spec.ivLen).also { random.nextBytes(it) }
        private val cipher = newStream(true, iv)
        private var sentIv = false

        fun write(buf: ByteArray, off: Int, len: Int) {
            val enc = cipher.update(buf, off, len)
            if (!sentIv) {
                val first = ByteArray(iv.size + enc.size)
                System.arraycopy(iv, 0, first, 0, iv.size)
                System.arraycopy(enc, 0, first, iv.size, enc.size)
                out.write(first)
                sentIv = true
            } else if (enc.isNotEmpty()) {
                out.write(enc)
            }
            out.flush()
        }
    }

    /** Decrypting reader. Reads the server's IV first. */
    inner class Reader(private val inp: InputStream) {
        private var cipher: Stream? = null

        /** Returns decrypted bytes into buf, or -1 at end of stream. */
        fun read(buf: ByteArray): Int {
            var c = cipher
            if (c == null) {
                val iv = ByteArray(spec.ivLen)
                var got = 0
                while (got < iv.size) {
                    val n = inp.read(iv, got, iv.size - got)
                    if (n < 0) return -1
                    got += n
                }
                c = newStream(false, iv)
                cipher = c
            }
            while (true) {
                val n = inp.read(buf)
                if (n < 0) return -1
                if (n == 0) continue
                val dec = c.update(buf, 0, n)
                System.arraycopy(dec, 0, buf, 0, dec.size)
                if (dec.isNotEmpty()) return dec.size
            }
        }
    }

    /** UDP: every packet is IV || E(ATYP ADDR PORT || payload) with its own fresh IV. */
    fun encryptPacket(data: ByteArray, len: Int): ByteArray {
        val iv = ByteArray(spec.ivLen).also { random.nextBytes(it) }
        val enc = newStream(true, iv).update(data, 0, len)
        return iv + enc
    }

    /** Returns ATYP ADDR PORT || payload, or null if the packet is too short. */
    fun decryptPacket(data: ByteArray, len: Int): ByteArray? {
        if (len <= spec.ivLen) return null
        val iv = data.copyOfRange(0, spec.ivLen)
        return newStream(false, iv).update(data, spec.ivLen, len - spec.ivLen)
    }

    companion object {
        private val SPECS = mapOf(
            "rc4-md5" to Spec(16, 16, "RC4"),
            "aes-128-cfb" to Spec(16, 16, "CFB"),
            "aes-192-cfb" to Spec(24, 16, "CFB"),
            "aes-256-cfb" to Spec(32, 16, "CFB"),
            "aes-128-ctr" to Spec(16, 16, "CTR"),
            "aes-192-ctr" to Spec(24, 16, "CTR"),
            "aes-256-ctr" to Spec(32, 16, "CTR"),
        )

        fun supports(method: String) = method.lowercase() in SPECS

        /** OpenSSL EVP_BytesToKey with MD5, one iteration, no salt. */
        fun bytesToKey(password: ByteArray, keyLen: Int): ByteArray {
            val out = ByteArray(keyLen)
            var prev = ByteArray(0)
            var pos = 0
            while (pos < keyLen) {
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(prev)
                md5.update(password)
                prev = md5.digest()
                val n = minOf(prev.size, keyLen - pos)
                System.arraycopy(prev, 0, out, pos, n)
                pos += n
            }
            return out
        }
    }
}
