package com.duallink.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File

/** Shared state for VPN mode, read by MainActivity. */
object VpnState {
    @Volatile var running = false
    @Volatile var message = "خاموش"
    @Volatile var balancer: Balancer? = null
    @Volatile var socks: SocksServer? = null
    @Volatile var startedAt = 0L
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

    private val meter = object : Runnable {
        override fun run() {
            val b = VpnState.balancer ?: return
            for (l in b.links) {
                val d = l.down.get()
                val u = l.up.get()
                l.downBps = l.downBps * 0.4 + (d - l.lastDown) * 0.6
                l.upBps = l.upBps * 0.4 + (u - l.lastUp) * 0.6
                l.lastDown = d
                l.lastUp = u
                l.capacityBps = maxOf(l.capacityBps * 0.985, l.downBps)
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
                if (tun == null) connect(intent.getBooleanExtra(EX_STICKY, false))
                else VpnState.balancer?.sticky = intent.getBooleanExtra(EX_STICKY, false)
            }
            else -> {
                // Started by the system, e.g. "Always-on VPN" after a reboot.
                if (tun == null) connect(getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sticky", false))
            }
        }
        return START_STICKY
    }

    private fun connect(sticky: Boolean) {
        try {
            val lm = LinkManager(this).also { it.start(useWifi = true, useCell = true) }
            links = lm
            val balancer = Balancer(listOf(
                VpnLink("وای‌فای") { lm.wifi },
                VpnLink("داده همراه") { lm.cell },
            )).also { it.sticky = sticky }
            val server = SocksServer(balancer).also { it.start() }
            socks = server

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
                |  port: ${server.port}
                |  address: '127.0.0.1'
                |  udp: 'tcp'
                |mapdns:
                |  address: $MAPPED_DNS
                |  port: 53
                |  network: 240.0.0.0
                |  netmask: 240.0.0.0
                |  cache-size: 10000
                |misc:
                |  task-stack-size: 81920
                |  connect-timeout: 10000
                |  log-level: warn
                |""".trimMargin()
            )
            if (!TProxyService.TProxyStartService(conf.absolutePath, fd.fd)) {
                throw IllegalStateException("موتور تونل اجرا نشد")
            }

            VpnState.balancer = balancer
            VpnState.socks = server
            VpnState.running = true
            VpnState.startedAt = System.currentTimeMillis()
            VpnState.message = "روشن — همه برنامه‌ها از هر دو لینک استفاده می‌کنند"
            handler.post(meter)
        } catch (e: Throwable) {
            disconnect("خطا: ${e.message ?: e.javaClass.simpleName}")
        }
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

    private fun disconnect(msg: String) {
        handler.removeCallbacks(meter)
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        socks?.stop()
        socks = null
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
