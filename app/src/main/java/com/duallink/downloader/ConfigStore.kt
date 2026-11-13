package com.duallink.downloader

import android.content.Context
import android.net.Network
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Saved configs and subscriptions.
 *  - Configs come from pasted links or from subscription URLs.
 *  - "Ping" = TCP connect time to the server.
 *  - Auto mode picks the lowest ping, and the VPN service switches to the next
 *    best config when the current server stops answering.
 */
object ConfigStore {

    class Entry(val link: String, val name: String, val protocol: String, val host: String, val port: Int, val sub: String?) {
        /** -2 = not tested, -1 = failed, else milliseconds. */
        @Volatile var pingMs = -2
    }

    private const val PREFS = "settings"
    private const val KEY_ENTRIES = "configs"
    private const val KEY_SUBS = "subscriptions"
    private const val KEY_SELECTED = "config_selected"
    private const val KEY_AUTO = "config_auto"

    private val lock = Any()
    private var loaded = false
    private val entries = ArrayList<Entry>()
    private val subs = ArrayList<String>()
    @Volatile var selected = -1
        private set
    @Volatile var auto = false
    @Volatile var busy = false
    @Volatile var message = ""

    fun list(): List<Entry> = synchronized(lock) { entries.toList() }
    fun subscriptions(): List<String> = synchronized(lock) { subs.toList() }

    fun load(ctx: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            runCatching {
                val arr = JSONArray(p.getString(KEY_ENTRIES, "[]"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    toEntry(o.getString("link"), o.optString("sub").ifEmpty { null })?.let { entries += it }
                }
            }
            runCatching {
                val arr = JSONArray(p.getString(KEY_SUBS, "[]"))
                for (i in 0 until arr.length()) subs += arr.getString(i)
            }
            selected = p.getInt(KEY_SELECTED, -1).takeIf { it in entries.indices } ?: if (entries.isEmpty()) -1 else 0
            auto = p.getBoolean(KEY_AUTO, false)

            // Migrate the single link from older versions.
            val old = p.getString(AggregatorVpnService.PREF_PROXY_LINK, "").orEmpty().trim()
            if (old.isNotEmpty() && entries.none { it.link == old }) {
                toEntry(old, null)?.let { entries.add(0, it); selected = 0 }
                p.edit().remove(AggregatorVpnService.PREF_PROXY_LINK).apply()
                saveLocked(ctx)
            }
        }
    }

    private fun toEntry(link: String, sub: String?): Entry? = try {
        val p = ProxyConfig.parse(link)
        Entry(link, p.name, p.protocol, p.host, p.port, sub)
    } catch (_: Exception) {
        null
    }

    private fun saveLocked(ctx: Context) {
        val arr = JSONArray()
        entries.forEach { e -> arr.put(JSONObject().put("link", e.link).put("sub", e.sub ?: "")) }
        val s = JSONArray()
        subs.forEach { s.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, arr.toString())
            .putString(KEY_SUBS, s.toString())
            .putInt(KEY_SELECTED, selected)
            .putBoolean(KEY_AUTO, auto)
            .apply()
    }

    fun save(ctx: Context) = synchronized(lock) { saveLocked(ctx) }

    fun select(ctx: Context, index: Int) = synchronized(lock) {
        if (index in entries.indices) { selected = index; saveLocked(ctx) }
    }

    fun current(): Entry? = synchronized(lock) { entries.getOrNull(selected) }

    /** Adds every valid link found in the text. Returns how many were new. */
    fun addFromText(ctx: Context, text: String, sub: String? = null): Int = synchronized(lock) {
        var added = 0
        for (line in linksIn(text)) {
            if (entries.any { it.link == line }) continue
            val e = toEntry(line, sub) ?: continue
            entries += e
            added++
        }
        if (selected == -1 && entries.isNotEmpty()) selected = 0
        saveLocked(ctx)
        added
    }

    fun remove(ctx: Context, index: Int) = synchronized(lock) {
        if (index !in entries.indices) return@synchronized
        entries.removeAt(index)
        selected = when {
            entries.isEmpty() -> -1
            selected == index -> 0
            selected > index -> selected - 1
            else -> selected
        }
        saveLocked(ctx)
    }

    fun clearAll(ctx: Context) = synchronized(lock) {
        entries.clear(); subs.clear(); selected = -1
        saveLocked(ctx)
    }

    fun addSubscription(ctx: Context, url: String) = synchronized(lock) {
        if (url !in subs) subs += url
        saveLocked(ctx)
    }

    // -------------------------------------------------------- subscriptions

    /** Downloads every subscription and replaces its configs. Background thread. */
    fun refreshSubscriptionsAsync(ctx: Context, net: () -> Network?) {
        if (busy) return
        val app = ctx.applicationContext
        busy = true
        message = "در حال دریافت اشتراک‌ها…"
        Thread({
            var total = 0
            val errors = ArrayList<String>()
            for (url in subscriptions()) {
                try {
                    val body = fetchText(url, net())
                    val text = decodeSubscription(body)
                    synchronized(lock) {
                        val keepSelected = entries.getOrNull(selected)?.link
                        entries.removeAll { it.sub == url }
                        for (line in linksIn(text)) {
                            if (entries.any { it.link == line }) continue
                            toEntry(line, url)?.let { entries += it; total++ }
                        }
                        selected = entries.indexOfFirst { it.link == keepSelected }
                            .takeIf { it >= 0 } ?: if (entries.isEmpty()) -1 else 0
                        saveLocked(app)
                    }
                } catch (e: Exception) {
                    errors += "${URL(url).host}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            message = if (errors.isEmpty()) "$total کانفیگ از اشتراک‌ها دریافت شد"
                      else "$total کانفیگ دریافت شد؛ خطا: ${errors.joinToString(" | ")}"
            busy = false
        }, "sub-refresh").start()
    }

    private fun fetchText(url: String, net: Network?): String {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "v2rayNG/1.10") // many panels format output by client name
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Subscriptions are either plain lines or base64 of lines. */
    private fun decodeSubscription(body: String): String {
        val t = body.trim()
        if (t.contains("://")) return t
        return try {
            val clean = t.replace("\n", "").replace("\r", "").replace('-', '+').replace('_', '/')
            String(Base64.decode(clean + "=".repeat((4 - clean.length % 4) % 4), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            t
        }
    }

    private fun linksIn(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter {
            it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://")
        }.toList()

    // ----------------------------------------------------------------- ping

    /** TCP connect time to every server, in parallel. Background thread. */
    fun pingAllAsync(net: () -> Network?, onDone: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        message = "در حال تست پینگ…"
        Thread({
            pingAllBlocking(net())
            val ok = list().count { it.pingMs >= 0 }
            message = "تست پینگ تمام شد: $ok از ${list().size} پاسخ دادند"
            busy = false
            onDone?.invoke()
        }, "ping-all").start()
    }

    fun pingAllBlocking(net: Network?) {
        val all = list()
        if (all.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(16, all.size))
        for (e in all) pool.execute { e.pingMs = ping(e.host, e.port, net) }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
    }

    fun ping(host: String, port: Int, net: Network?, timeoutMs: Int = 4000): Int {
        return try {
            val s = net?.socketFactory?.createSocket() ?: Socket()
            s.use {
                val addr = net?.getByName(host) ?: java.net.InetAddress.getByName(host)
                val t0 = System.nanoTime()
                it.connect(InetSocketAddress(addr, port), timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            }
        } catch (_: Exception) {
            -1
        }
    }

    /** Index of the lowest-ping config, skipping [exclude]; -1 if none answered. */
    fun bestIndex(exclude: Set<String> = emptySet()): Int = synchronized(lock) {
        entries.withIndex()
            .filter { it.value.pingMs >= 0 && it.value.link !in exclude }
            .minByOrNull { it.value.pingMs }?.index ?: -1
    }

    fun selectBest(ctx: Context, exclude: Set<String> = emptySet()): Boolean {
        val i = bestIndex(exclude)
        if (i < 0) return false
        select(ctx, i)
        return true
    }
}
