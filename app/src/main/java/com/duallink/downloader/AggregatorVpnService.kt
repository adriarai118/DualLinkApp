package com.duallink.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.ServerSocket

/** Shared state for VPN mode, read by MainActivity. */
object VpnState {
    @Volatile var running = false
    @Volatile var message = "خاموش"
    @Volatile var balancer: Balancer? = null
    @Volatile var socks: SocksServer? = null
    @Volatile var startedAt = 0L
    @Volatile var proxyName: String? = null
}

/**
 * Whole-phone mode. Captures all traffic with a VPN interface; hev-socks5-tunnel
 * converts it into SOCKS5 connections to our SocksServer, which sends each
 * connection out over Wi-Fi or mobile data. This app itself is excluded from the
 * VPN so its own sockets go straight to the chosen network.
 */
class AggregatorVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.duallink.downloader.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.duallink.downloader.VPN_DISCONNECT"
        const val EX_STICKY = "sticky"
        const val PREF_PROXY_ON = "proxy_on"
        const val PREF_PROXY_LINK = "proxy_link"
        const val PREF_DIRECT_IR = "direct_ir"
        private const val CHANNEL = "vpn"
        private const val NID = 21

        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"
        private const val MTU = 8500
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tun: ParcelFileDescriptor? = null
    private var links: LinkManager? = null
    private var socks: SocksServer? = null
    private var lastUnderlying: List<android.net.Network?> = emptyList()
    private var proxyMode = false
    private var xrayRunning = false
    private var lastMobileRx = -1L
    private var lastMobileTx = -1L
    private var lastTunRx = -1L
    private var lastTunTx = -1L

    private val meter = object : Runnable {
        override fun run() {
            val b = VpnState.balancer ?: return
            if (proxyMode) measureViaSystemCounters(b)
            for (l in b.links) {
                val d = l.down.get()
                val u = l.up.get()
                l.downBps = l.downBps * 0.4 + (d - l.lastDown) * 0.6
                l.upBps = l.upBps * 0.4 + (u - l.lastUp) * 0.6
                l.lastDown = d
                l.lastUp = u
                l.capacityBps = maxOf(l.capacityBps * 0.995, l.downBps)
            }
            updateUnderlying()
            val total = b.links.sumOf { it.downBps }
            notifyStatus("↓ ${fmtSpeed(total)}  —  " +
                b.links.joinToString("  ") { "${it.name}: ${fmtSpeed(it.downBps)}" })
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect("خاموش")
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (tun == null) connectAsync(intent.getBooleanExtra(EX_STICKY, false))
                else VpnState.balancer?.sticky = intent.getBooleanExtra(EX_STICKY, false)
            }
            else -> {
                // Started by the system, e.g. "Always-on VPN" after a reboot.
                if (tun == null) connectAsync(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sticky", false))
            }
        }
        return START_STICKY
    }

    @Volatile private var connecting = false

    /** Connecting waits for networks and does DNS, so it must not run on the main thread. */
    private fun connectAsync(sticky: Boolean) {
        if (connecting) return
        connecting = true
        VpnState.message = "در حال روشن شدن… (منتظر وای‌فای و دیتا)"
        Thread({
            try {
                connect(sticky)
            } finally {
                connecting = false
            }
        }, "vpn-connect").start()
    }

    @Synchronized
    private fun connect(sticky: Boolean) {
        try {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val proxyLink = prefs.getString(PREF_PROXY_LINK, "").orEmpty()
            proxyMode = prefs.getBoolean(PREF_PROXY_ON, false) && proxyLink.isNotBlank()
            val parsed = if (proxyMode) ProxyConfig.parse(proxyLink) else null

            val lm = LinkManager(this).also { it.start(useWifi = true, useCell = true) }
            links = lm
            val balancer = Balancer(listOf(
                VpnLink("وای‌فای") { lm.wifi },
                VpnLink("داده همراه") { lm.cell },
            ), countsActive = !proxyMode).also { it.sticky = sticky }

            waitForLinks(lm)

            val socksPort: Int
            val udpMode: String
            if (parsed != null) {
                socksPort = ServerSocket(0).use { it.localPort }
                // Resolve the server here (Android's resolver works; Go's doesn't on Android).
                val serverIp = resolveServer(lm, parsed.host)
                val dns = (lm.dnsServers(lm.wifi) + lm.dnsServers(lm.cell))
                    .filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
                xraybridge.Xraybridge.setProtector(LinkProtector(balancer) { ip ->
                    when {
                        lm.dnsServers(lm.wifi).any { it.hostAddress == ip } -> balancer.links[0]
                        lm.dnsServers(lm.cell).any { it.hostAddress == ip } -> balancer.links[1]
                        else -> null
                    }
                })
                xraybridge.Xraybridge.start(
                    ProxyConfig.buildConfig(parsed, socksPort, prefs.getBoolean(PREF_DIRECT_IR, true), serverIp, dns)
                )
                xrayRunning = true
                udpMode = "udp" // Xray's socks inbound speaks standard UDP ASSOCIATE
                VpnState.proxyName = parsed.name
            } else {
                val server = SocksServer(balancer).also { it.start() }
                socks = server
                VpnState.socks = server
                socksPort = server.port
                udpMode = "tcp" // our SocksServer speaks hev's UDP-in-TCP
                VpnState.proxyName = null
            }

            val b = Builder()
                .setSession("DualLink")
                .setMtu(MTU)
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_IPV6, 128)
                .addRoute("::", 0)
                .addDnsServer(MAPPED_DNS)
                .setBlocking(false)
            // Our own sockets must not loop back into the VPN.
            b.addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

            val fd = b.establish() ?: throw IllegalStateException("اجازه VPN داده نشده است")
            tun = fd

            val conf = File(cacheDir, "tunnel.yml")
            conf.writeText(
                """
                |tunnel:
                |  mtu: $MTU
                |  icmp: 'reply'
                |socks5:
                |  port: $socksPort
                |  address: '127.0.0.1'
                |  udp: '$udpMode'
                |mapdns:
                |  address: $MAPPED_DNS
                |  port: 53
                |  network: 240.0.0.0
                |  netmask: 240.0.0.0
                |  cache-size: 10000
                |misc:
                |  task-stack-size: 81920
                |  tcp-buffer-size: 131072
                |  connect-timeout: 10000
                |  log-level: warn
                |""".trimMargin()
            )
            if (!TProxyService.TProxyStartService(conf.absolutePath, fd.fd)) {
                throw IllegalStateException("موتور تونل اجرا نشد")
            }

            VpnState.balancer = balancer
            VpnState.running = true
            VpnState.startedAt = System.currentTimeMillis()
            VpnState.message = if (parsed != null) {
                "روشن — از طریق کانفیگ «${parsed.name}» روی هر دو لینک"
            } else {
                "روشن — همه برنامه‌ها از هر دو لینک استفاده می‌کنند"
            }
            handler.post(meter)
        } catch (e: Throwable) {
            disconnect("خطا: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Mobile data needs a few seconds to come up next to Wi-Fi; don't start without it. */
    private fun waitForLinks(lm: LinkManager) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (lm.wifi != null && lm.cell != null) return
            // Accept a single link after 4 s (the other may simply be off).
            if (System.currentTimeMillis() > deadline - 4000 && (lm.wifi != null || lm.cell != null)) return
            Thread.sleep(150)
        }
    }

    /** Server name -> IPv4 address via whichever link works. Keeps the name if it already is an IP. */
    private fun resolveServer(lm: LinkManager, host: String): String? {
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return null
        for (net in listOfNotNull(lm.wifi, lm.cell)) {
            try {
                val all = net.getAllByName(host)
                val v4 = all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull()
                if (v4 != null) return v4.hostAddress
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException("آدرس سرور کانفیگ ($host) پیدا نشد (DNS)")
    }

    /**
     * In proxy mode Xray owns the sockets, so we can't count bytes per link ourselves.
     * Mobile bytes come from Android's counters; Wi-Fi = everything that reached the
     * apps through the tunnel minus the mobile part.
     */
    private fun measureViaSystemCounters(b: Balancer) {
        val wifi = b.links[0]
        val cell = b.links[1]
        val mRx = TrafficStats.getMobileRxBytes()
        val mTx = TrafficStats.getMobileTxBytes()
        val st = try { TProxyService.TProxyGetStats() } catch (_: Throwable) { null }
        val tRx = st?.getOrNull(3) ?: 0L
        val tTx = st?.getOrNull(1) ?: 0L
        if (lastTunRx >= 0) {
            val dmRx = if (mRx >= 0 && lastMobileRx >= 0) maxOf(0L, mRx - lastMobileRx) else 0L
            val dmTx = if (mTx >= 0 && lastMobileTx >= 0) maxOf(0L, mTx - lastMobileTx) else 0L
            val dtRx = maxOf(0L, tRx - lastTunRx)
            val dtTx = maxOf(0L, tTx - lastTunTx)
            cell.down.addAndGet(minOf(dmRx, dtRx))
            cell.up.addAndGet(minOf(dmTx, dtTx))
            wifi.down.addAndGet(maxOf(0L, dtRx - dmRx))
            wifi.up.addAndGet(maxOf(0L, dtTx - dmTx))
        }
        lastMobileRx = mRx; lastMobileTx = mTx; lastTunRx = tRx; lastTunTx = tTx
    }

    /** Lets Android know which real networks we use (metered status, the key icon, etc.). */
    private fun updateUnderlying() {
        val lm = links ?: return
        val now = listOf(lm.wifi, lm.cell)
        if (now == lastUnderlying) return
        lastUnderlying = now
        val nets = now.filterNotNull()
        try {
            setUnderlyingNetworks(if (nets.isEmpty()) null else nets.toTypedArray())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun disconnect(msg: String) {
        handler.removeCallbacks(meter)
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        socks?.stop()
        socks = null
        VpnState.socks = null
        if (xrayRunning) {
            try { xraybridge.Xraybridge.stop() } catch (_: Throwable) {}
            xrayRunning = false
        }
        lastMobileRx = -1L; lastMobileTx = -1L; lastTunRx = -1L; lastTunTx = -1L
        links?.stop()
        links = null
        lastUnderlying = emptyList()
        VpnState.running = false
        VpnState.message = msg
        getSystemService(NotificationManager::class.java).cancel(NID)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user turned us off in system settings.
        disconnect("خاموش شد (VPN دیگری فعال شد یا از تنظیمات قطع شد)")
        super.onRevoke()
    }

    override fun onDestroy() {
        if (tun != null) disconnect("خاموش")
        super.onDestroy()
    }

    private fun notifyStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "حالت VPN", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, AggregatorVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("تجمیع وای‌فای + دیتا روشن است")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        @Suppress("DEPRECATION")
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "خاموش", stop)
        try {
            nm.notify(NID, b.build())
        } catch (_: SecurityException) {
        }
    }
}
