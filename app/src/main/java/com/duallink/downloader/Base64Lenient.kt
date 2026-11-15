package com.duallink.downloader

/**
 * Base64 decoder that accepts standard and URL-safe alphabets, missing padding and
 * whitespace (subscriptions and share links use all of these). Works on every
 * Android version and on the JVM, so it can be unit-tested.
 */
object Base64Lenient {
    private val table = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".forEachIndexed { i, c -> t[c.code] = i }
        t['+'.code] = 62; t['-'.code] = 62
        t['/'.code] = 63; t['_'.code] = 63
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=') break
            if (ch.isWhitespace()) continue
            val v = if (ch.code < 128) table[ch.code] else -1
            if (v < 0) throw IllegalArgumentException("invalid base64")
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
