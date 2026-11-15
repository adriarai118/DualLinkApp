package com.duallink.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory event log (last few hundred lines) for the diagnostics report. */
object DiagLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${time.format(Date())} $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun last(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() = lines.clear()
}

/** Builds a plain-text report of everything needed to see why aggregation does or doesn't work. */
object Diagnostics {

    fun describe(cm: ConnectivityManager, net: Network?): String {
        if (net == null) return "ندارد"
        val c = cm.getNetworkCapabilities(net) ?: return "اطلاعات ندارد (احتمالاً قطع شده)"
        val parts = ArrayList<String>()
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "wifi"
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "cellular"
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "vpn"
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "ethernet"
        parts += if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "internet" else "NO-internet"
        parts += if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "VALIDATED" else "NOT-validated"
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) parts += "metered"
        val lp = cm.getLinkProperties(net)
        lp?.interfaceName?.let { parts += "if=$it" }
        parts += "down≈${c.linkDownstreamBandwidthKbps / 1000}Mbps"
        return parts.joinToString(" ")
    }

    fun report(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("=== DualLink diagnostics — $now ===\n")
        sb.append("نسخه: ${Updater.currentVersionName(ctx)} (${Updater.currentVersion(ctx)})")
            .append("  —  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .append("  —  ${Build.MANUFACTURER} ${Build.MODEL}\n\n")

        sb.append("[VPN]\n")
        sb.append("وضعیت: ${if (VpnState.running) "روشن" else "خاموش"} — ${VpnState.message}\n")
        val b = VpnState.balancer
        val mode = when {
            VpnState.proxyName == null -> "مستقیم (بدون کانفیگ)"
            VpnState.xrayLog != null -> "کانفیگ «${VpnState.proxyName}» با Xray"
            else -> "کانفیگ «${VpnState.proxyName}» با موتور داخلی"
        }
        sb.append("حالت: $mode")
        if (b != null) sb.append(if (b.sticky) " — سازگار (هر سایت یک لینک)" else " — حداکثر سرعت")
        if (VpnState.running && VpnState.startedAt > 0) {
            sb.append(" — روشن از ${(System.currentTimeMillis() - VpnState.startedAt) / 1000} ثانیه پیش")
        }
        sb.append("\n\n")

        sb.append("[شبکه‌ها از نگاه اندروید]\n")
        sb.append("پیش‌فرض سیستم: ${describe(cm, cm.activeNetwork)}\n")
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        for (n in all) sb.append("• ${describe(cm, n)}\n")
        if (all.isEmpty()) sb.append("• هیچ شبکه‌ای\n")
        sb.append("\n")

        if (b != null) {
            sb.append("[لینک‌ها در تجمیع]\n")
            val nowMs = SystemClock.elapsedRealtime()
            for (l in b.links) {
                sb.append("● ${l.name}: ${describe(cm, l.network)}\n")
                sb.append("   دانلود ${fmtBytes(l.down.get())} (الان ${fmtSpeed(l.downBps)})")
                    .append(" — آپلود ${fmtBytes(l.up.get())} (الان ${fmtSpeed(l.upBps)})\n")
                sb.append("   اتصال: فعال ${l.active.get()}، مجموع ${l.total.get()}، ناموفق ${l.failures.get()}")
                    .append(" — ظرفیت تخمینی ${fmtSpeed(l.capacityBps)}")
                if (l.badUntil > nowMs) sb.append(" — کنار گذاشته تا ${(l.badUntil - nowMs) / 1000} ثانیه")
                sb.append("\n")
                l.lastError?.let { sb.append("   آخرین خطا: $it\n") }
            }
            val totalDown = b.links.sumOf { it.down.get() }
            if (totalDown > 0) {
                sb.append("سهم دانلود: ")
                    .append(b.links.joinToString("، ") { "${it.name} ${it.down.get() * 100 / totalDown}٪" })
                    .append("\n")
            }
            sb.append("\n")
        }

        sb.append("[شمارنده‌های سیستم (کل گوشی، از روشن شدن)]\n")
        sb.append("دیتا: دریافت ${fmtBytes(TrafficStats.getMobileRxBytes())}، ارسال ${fmtBytes(TrafficStats.getMobileTxBytes())}\n")
        sb.append("کل: دریافت ${fmtBytes(TrafficStats.getTotalRxBytes())}، ارسال ${fmtBytes(TrafficStats.getTotalTxBytes())}\n\n")

        if (SpeedTest.results.isNotEmpty()) {
            sb.append("[آخرین تست سرعت]\n")
            SpeedTest.results.forEach { sb.append(it).append("\n") }
            sb.append("\n")
        }

        sb.append("[رویدادها — آخرین ۲۰۰]\n")
        DiagLog.last(200).forEach { sb.append(it).append("\n") }
        return sb.toString()
    }
}
