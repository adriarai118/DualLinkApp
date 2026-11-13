package com.duallink.downloader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update from this app's GitHub release "latest".
 * CI writes "versionCode=N" into the release notes; we compare it with ours,
 * download the APK over both links (DualLinkEngine) and hand it to PackageInstaller.
 */
object Updater {

    private const val API = "https://api.github.com/repos/adriarai118/DualLinkApp/releases/tags/latest"
    private const val PREF_LAST_CHECK = "update_last_check"
    const val ACTION_INSTALL_STATUS = "com.duallink.downloader.INSTALL_STATUS"

    class Info(val versionCode: Long, val url: String, val size: Long, val notes: String)

    @Volatile var busy = false
    @Volatile var message = ""
    @Volatile var available: Info? = null
    @Volatile var engine: DualLinkEngine? = null

    fun currentVersion(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Checks at most every 6 hours unless forced. Runs on a background thread. */
    fun checkAsync(ctx: Context, force: Boolean) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val last = prefs.getLong(PREF_LAST_CHECK, 0)
        if (busy || (!force && System.currentTimeMillis() - last < 6 * 3600_000L)) return
        busy = true
        message = "در حال بررسی نسخه جدید…"
        Thread({
            try {
                val info = fetch()
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                if (info != null && info.versionCode > currentVersion(app)) {
                    available = info
                    message = "نسخه جدید موجود است (${fmtBytes(info.size)})"
                } else {
                    available = null
                    message = "برنامه به‌روز است ✓"
                }
            } catch (e: Exception) {
                message = "بررسی ناموفق: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }, "update-check").start()
    }

    private fun fetch(): Info? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DualLink-updater")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val body = j.optString("body")
            val code = Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    val notes = body.lines().filterNot { it.startsWith("versionCode=") }.joinToString("\n").trim()
                    return Info(code, a.getString("browser_download_url"), a.optLong("size"), notes)
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Downloads over Wi-Fi + mobile data, then asks Android to install. */
    fun downloadAndInstallAsync(ctx: Context) {
        val info = available ?: return
        if (busy) return
        val app = ctx.applicationContext
        busy = true
        message = "در حال دانلود نسخه جدید…"
        Thread({
            val lm = LinkManager(app).also { it.start(useWifi = true, useCell = true) }
            try {
                val deadline = SystemClock.elapsedRealtime() + 6000
                while (SystemClock.elapsedRealtime() < deadline && (lm.wifi == null || lm.cell == null)) Thread.sleep(150)
                val links = listOfNotNull(
                    lm.wifi?.let { LinkStats("وای‌فای") { lm.wifi } },
                    lm.cell?.let { LinkStats("داده همراه") { lm.cell } },
                )
                if (links.isEmpty()) throw IOException("اینترنت در دسترس نیست")
                val dir = File(app.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
                val eng = DualLinkEngine(info.url, dir, links, 4)
                engine = eng
                val apk = eng.run()
                message = "در حال نصب…"
                install(app, apk)
                message = "تأیید نصب را در پنجره اندروید بزنید"
            } catch (e: Exception) {
                message = "به‌روزرسانی ناموفق: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                engine = null
                lm.stop()
                busy = false
            }
        }, "update-download").start()
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
                flags
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Receives PackageInstaller's result; shows Android's "Install?" confirmation when asked to. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.message = "نصب شد ✓"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.message = "نصب انجام نشد. $msg"
            }
        }
    }
}
