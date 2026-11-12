package com.duallink.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Foreground service so the download keeps running when the app is in the background. */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.duallink.downloader.START"
        const val ACTION_CANCEL = "com.duallink.downloader.CANCEL"
        const val EX_URL = "url"
        const val EX_THREADS = "threads"
        const val EX_WIFI = "wifi"
        const val EX_CELL = "cell"
        private const val CHANNEL = "downloads"
        private const val NID = 7
        private const val NID_DONE = 8
    }

    private val handler = Handler(Looper.getMainLooper())
    private var links: LinkManager? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!DlState.running) return
            val e = DlState.engine
            val text: String
            var max = 0
            var progress = 0
            if (e != null && e.total > 0) {
                max = 1000
                progress = (e.written.get() * 1000 / e.total).toInt().coerceIn(0, 1000)
                text = "${fmtBytes(e.written.get())} / ${fmtBytes(e.total)} — ${fmtSpeed(e.totalSpeedBps)}"
            } else if (e != null) {
                text = "${fmtBytes(e.written.get())} — ${fmtSpeed(e.totalSpeedBps)}"
            } else {
                text = DlState.message
            }
            notify(NID, buildNotif(text, max, progress, ongoing = true))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                startFg(buildNotif("آماده‌سازی…", 0, 0, ongoing = true))
                if (worker == null) begin(intent) // ignore a second start while busy
            }
            ACTION_CANCEL -> {
                DlState.message = "در حال لغو…"
                DlState.engine?.cancel()
                if (worker == null) stopSelf()
            }
            else -> if (worker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val url = intent.getStringExtra(EX_URL).orEmpty()
        val threads = intent.getIntExtra(EX_THREADS, 4).coerceIn(1, 16)
        val useWifi = intent.getBooleanExtra(EX_WIFI, true)
        val useCell = intent.getBooleanExtra(EX_CELL, true)

        DlState.reset()
        DlState.running = true
        DlState.message = "در انتظار وصل شدن شبکه‌ها…"

        val lm = LinkManager(this).also { it.start(useWifi, useCell) }
        links = lm

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DualLink:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        handler.post(tick)

        worker = Thread({
            var engine: DualLinkEngine? = null
            val msg = try {
                // Give Android a few seconds to bring mobile data up next to Wi-Fi.
                val deadline = SystemClock.elapsedRealtime() + 12_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if ((!useWifi || lm.wifi != null) && (!useCell || lm.cell != null)) break
                    Thread.sleep(200)
                }

                val stats = mutableListOf<LinkStats>()
                val missing = mutableListOf<String>()
                if (useWifi) {
                    if (lm.wifi != null) {
                        stats.add(LinkStats("وای‌فای") { lm.wifi })
                    } else {
                        missing.add("وای‌فای")
                    }
                }
                if (useCell) {
                    if (lm.cell != null) {
                        stats.add(LinkStats("داده همراه") { lm.cell })
                    } else {
                        missing.add("داده همراه")
                    }
                }
                if (stats.isEmpty()) throw IOException("هیچ شبکه‌ای در دسترس نیست (وای‌فای/دیتا روشن است؟)")
                if (missing.isNotEmpty()) {
                    DlState.warning = "${missing.joinToString(" و ")} وصل نشد؛ فقط با ${stats.joinToString(" و ") { it.name }} ادامه می‌دهیم."
                }

                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads")
                val eng = DualLinkEngine(url, dir, stats, threads)
                engine = eng
                DlState.engine = eng
                DlState.message = "در حال دانلود…"

                val t0 = SystemClock.elapsedRealtime()
                val file = eng.run()
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val avg = if (secs > 0) file.length() / secs else 0.0

                DlState.message = "در حال انتقال فایل به پوشه دانلود…"
                val saved = publish(file, eng.mime)
                DlState.savedTo = saved
                val modeNote = if (eng.rangeSupported) "" else "\n(سرور تکه‌ای دانلود را پشتیبانی نمی‌کرد؛ فقط یک لینک استفاده شد)"
                "تمام شد ✓  ${fmtBytes(file.length())} در ${"%.1f".format(secs)} ثانیه — میانگین ${fmtSpeed(avg)}$modeNote"
            } catch (e: Exception) {
                engine?.outFile?.delete()
                if (engine?.cancelled == true) "لغو شد" else "خطا: ${e.message ?: e.javaClass.simpleName}"
            }
            handler.post { finishUp(msg) }
        }, "dl-main").also { it.start() }
    }

    /** Android 10+: copy into the public Downloads/DualLink folder. Older: keep the app folder. */
    private fun publish(file: File, mime: String?): String {
        if (Build.VERSION.SDK_INT < 29) return file.absolutePath
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DualLink")
            if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return file.absolutePath
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 20) }
            } ?: throw IOException("openOutputStream null")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return "Download/DualLink/${file.name}"
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return file.absolutePath
        }
    }

    private fun finishUp(msg: String) {
        DlState.message = msg
        DlState.running = false
        handler.removeCallbacks(tick)
        links?.stop()
        links = null
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notify(NID_DONE, buildNotif(msg.lineSequence().first(), 0, 0, ongoing = false))
        stopSelf()
    }

    override fun onDestroy() {
        DlState.engine?.cancel()
        handler.removeCallbacks(tick)
        links?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------- notifications

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "دانلودها", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotif(text: String, max: Int, progress: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            if (max > 0) b.setProgress(max, progress, false) else b.setProgress(0, 0, true)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "لغو", cancel)
        } else {
            b.setAutoCancel(true)
        }
        return b.build()
    }

    private fun notify(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // notification permission denied: the download still runs
        }
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NID, n)
        }
    }
}
