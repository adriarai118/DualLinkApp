package com.duallink.downloader

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 42
    }

    private lateinit var urlInput: EditText
    private lateinit var wifiBox: CheckBox
    private lateinit var cellBox: CheckBox
    private lateinit var threadsLabel: TextView
    private lateinit var threadsBar: SeekBar
    private lateinit var startBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var linksText: TextView
    private lateinit var vpnBtn: Button
    private lateinit var stickyBox: CheckBox
    private lateinit var vpnText: TextView
    private lateinit var proxyBox: CheckBox
    private lateinit var proxyInput: EditText
    private lateinit var directIrBox: CheckBox
    private lateinit var proxyInfo: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        wifiBox.isChecked = prefs.getBoolean("wifi", true)
        cellBox.isChecked = prefs.getBoolean("cell", true)
        threadsBar.progress = prefs.getInt("threads", 4) - 1
        stickyBox.isChecked = prefs.getBoolean("sticky", false)
        // Read everything first: setting a checkbox fires its listener, which saves all fields.
        val savedLink = prefs.getString(AggregatorVpnService.PREF_PROXY_LINK, "")
        val savedProxyOn = prefs.getBoolean(AggregatorVpnService.PREF_PROXY_ON, false)
        val savedDirectIr = prefs.getBoolean(AggregatorVpnService.PREF_DIRECT_IR, true)
        proxyInput.setText(savedLink)
        directIrBox.isChecked = savedDirectIr
        proxyBox.isChecked = savedProxyOn
        validateProxy()
        updateThreadsLabel()
        if (urlInput.text.isEmpty()) urlInput.setText(prefs.getString("url", ""))
        handleShare(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            extractUrl(text)?.let { urlInput.setText(it) }
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '»', '"')

    // ------------------------------------------------------------- actions

    private fun startDownload() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("لینک باید با http:// یا https:// شروع شود")
            return
        }
        if (!wifiBox.isChecked && !cellBox.isChecked) {
            toast("حداقل یکی از شبکه‌ها را انتخاب کنید")
            return
        }
        if (DlState.running) {
            toast("یک دانلود در جریان است")
            return
        }
        val threads = threadsBar.progress + 1
        prefs.edit()
            .putString("url", url)
            .putBoolean("wifi", wifiBox.isChecked)
            .putBoolean("cell", cellBox.isChecked)
            .putInt("threads", threads)
            .apply()

        val i = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EX_URL, url)
            .putExtra(DownloadService.EX_THREADS, threads)
            .putExtra(DownloadService.EX_WIFI, wifiBox.isChecked)
            .putExtra(DownloadService.EX_CELL, cellBox.isChecked)
        DlState.running = true
        DlState.message = "شروع…"
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun cancelDownload() {
        if (!DlState.running) return
        startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
    }

    // ------------------------------------------------------------- VPN mode

    private fun toggleVpn() {
        if (VpnState.running) {
            startService(Intent(this, AggregatorVpnService::class.java).setAction(AggregatorVpnService.ACTION_DISCONNECT))
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN)
        } else {
            startVpn()
        }
    }

    private fun saveProxyPrefs() {
        prefs.edit()
            .putString(AggregatorVpnService.PREF_PROXY_LINK, proxyInput.text.toString().trim())
            .putBoolean(AggregatorVpnService.PREF_PROXY_ON, proxyBox.isChecked)
            .putBoolean(AggregatorVpnService.PREF_DIRECT_IR, directIrBox.isChecked)
            .apply()
    }

    /** Shows what the pasted link is, or why it can't be used. Returns false if invalid. */
    private fun validateProxy(): Boolean {
        val link = proxyInput.text.toString().trim()
        if (link.isEmpty()) {
            proxyInfo.text = "لینک کانفیگ (vless / vmess / trojan / ss) را اینجا بچسبانید."
            return !proxyBox.isChecked
        }
        return try {
            val p = ProxyConfig.parse(link)
            proxyInfo.text = "✓ ${p.name} — ${p.protocol} — ${p.host}:${p.port}"
            true
        } catch (e: Exception) {
            proxyInfo.text = "✗ لینک نامعتبر: ${e.message}"
            false
        }
    }

    private fun startVpn() {
        if (proxyBox.isChecked && !validateProxy()) {
            toast("لینک کانفیگ معتبر نیست")
            return
        }
        saveProxyPrefs()
        VpnState.message = "در حال روشن شدن…"
        startService(
            Intent(this, AggregatorVpnService::class.java)
                .setAction(AggregatorVpnService.ACTION_CONNECT)
                .putExtra(AggregatorVpnService.EX_STICKY, stickyBox.isChecked)
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn() else toast("اجازه VPN داده نشد")
        }
    }

    private fun hintReconnect() {
        if (VpnState.running) toast("برای اعمال تغییر، حالت کل گوشی را خاموش و دوباره روشن کنید")
    }

    private fun renderVpn() {
        proxyInput.isEnabled = !VpnState.running
        vpnBtn.text = if (VpnState.running) "خاموش کردن حالت کل گوشی" else "روشن کردن حالت کل گوشی"
        val b = VpnState.balancer
        val sb = StringBuilder("وضعیت: ").append(VpnState.message)
        if (VpnState.running && b != null) {
            sb.append("\nسرعت کل: ↓ ").append(fmtSpeed(b.links.sumOf { it.downBps }))
                .append("   ↑ ").append(fmtSpeed(b.links.sumOf { it.upBps }))
            for (l in b.links) {
                val state = if (l.network == null) " (قطع)" else ""
                sb.append("\n● ").append(l.name).append(state).append(":  ↓ ").append(fmtSpeed(l.downBps))
                    .append("  ↑ ").append(fmtSpeed(l.upBps))
                    .append("\n    اتصال فعال: ").append(l.active.get())
                    .append("  —  مجموع: ").append(l.total.get())
                    .append("  —  مصرف: ").append(fmtBytes(l.down.get() + l.up.get()))
            }
        }
        if (VpnState.running && VpnState.proxyName != null) {
            val tail = VpnState.xrayLogTail()
            if (tail.isNotEmpty()) sb.append("\n\nآخرین پیام‌های Xray:\n").append(tail)
        }
        vpnText.text = sb.toString()
    }

    // -------------------------------------------------------------- render

    private fun render() {
        renderVpn()
        val e = DlState.engine
        val running = DlState.running
        startBtn.isEnabled = !running
        cancelBtn.isEnabled = running
        urlInput.isEnabled = !running
        wifiBox.isEnabled = !running
        cellBox.isEnabled = !running
        threadsBar.isEnabled = !running

        val sb = StringBuilder()
        sb.append("وضعیت: ").append(DlState.message)
        DlState.warning?.let { sb.append("\n⚠ ").append(it) }
        if (e != null) {
            if (e.fileName.isNotEmpty()) sb.append("\nفایل: ").append(e.fileName)
            val done = e.written.get()
            if (e.total > 0) {
                val pct = done * 100.0 / e.total
                progress.isIndeterminate = false
                progress.progress = (pct * 10).toInt()
                sb.append("\nپیشرفت: ").append("%.1f".format(pct)).append("٪  —  ")
                    .append(fmtBytes(done)).append(" از ").append(fmtBytes(e.total))
                if (running && e.totalSpeedBps > 1) {
                    val eta = ((e.total - done) / e.totalSpeedBps).toLong()
                    sb.append("\nزمان باقی‌مانده: ").append(fmtEta(eta))
                }
            } else {
                progress.isIndeterminate = running
                sb.append("\nدریافت‌شده: ").append(fmtBytes(done))
            }
            sb.append("\nسرعت کل: ").append(fmtSpeed(e.totalSpeedBps))
            if (!e.rangeSupported && e.total != -1L && running) {
                sb.append("\n(این سرور دانلود تکه‌ای را پشتیبانی نمی‌کند؛ فقط یک لینک کار می‌کند)")
            }
        } else {
            progress.isIndeterminate = running
            if (!running) progress.progress = 0
        }
        DlState.savedTo?.let { sb.append("\nذخیره شد در: ").append(it) }
        statusText.text = sb.toString()

        linksText.text = if (e != null) {
            e.links.joinToString("\n\n") { l ->
                val share = if (e.written.get() > 0) l.bytes.get() * 100 / e.written.get() else 0L
                buildString {
                    append("● ").append(l.name).append(":  ").append(fmtSpeed(l.speedBps))
                    append("\n    ").append(fmtBytes(l.bytes.get())).append("  (").append(share).append("٪ از کل)")
                    append("  —  اتصال فعال: ").append(l.activeWorkers.get())
                    if (l.errors.get() > 0) {
                        append("\n    خطاها: ").append(l.errors.get())
                        l.lastError?.let { append(" — ").append(it) }
                    }
                }
            }
        } else {
            idleNetworkSummary()
        }
    }

    /** Shows which links the phone currently has, before a download starts. */
    private fun idleNetworkSummary(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        var wifi = false
        var cell = false
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
        }
        return "وای‌فای: " + (if (wifi) "وصل ✓" else "قطع") +
            "\nداده همراه: " + (if (cell) "فعال ✓" else "خاموش (هنگام دانلود روشن می‌شود اگر دیتا فعال باشد)") +
            "\n\nنکته: سرعت فقط وقتی جمع می‌شود که سرور «دانلود تکه‌ای» (Range) را پشتیبانی کند — تقریباً همه سایت‌های دانلود و CDNها پشتیبانی می‌کنند."
    }

    private fun fmtEta(s: Long): String = when {
        s < 60 -> "$s ثانیه"
        s < 3600 -> "${s / 60} دقیقه و ${s % 60} ثانیه"
        else -> "${s / 3600} ساعت و ${(s % 3600) / 60} دقیقه"
    }

    private fun updateThreadsLabel() {
        threadsLabel.text = "اتصال هم‌زمان روی هر لینک: ${threadsBar.progress + 1}"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "حالت کل گوشی (VPN)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "همه برنامه‌ها (مرورگر، پلی‌استور، اینستاگرام، تلگرام…) اتصال‌هایشان بین وای‌فای و دیتا پخش می‌شود. " +
                "برنامه‌هایی که چند اتصال هم‌زمان باز می‌کنند بیشترین سود را می‌برند. هم‌زمان با VPN دیگری کار نمی‌کند."
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        stickyBox = CheckBox(this).apply {
            text = "حالت سازگار: هر سایت فقط از یک لینک (اگر بانک یا سایتی مدام از حساب خارج شد)"
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("sticky", checked).apply()
                VpnState.balancer?.sticky = checked
            }
        }
        root.addView(stickyBox)

        proxyBox = CheckBox(this).apply {
            text = "از طریق کانفیگ VPN (v2ray/xray) — تجمیع روی خود VPN"
            setOnCheckedChangeListener { _, _ -> validateProxy(); saveProxyPrefs(); hintReconnect() }
        }
        root.addView(proxyBox)
        val proxyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        proxyInput = EditText(this).apply {
            hint = "vless://…  یا  vmess://…  یا  trojan://…  یا  ss://…"
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setOnFocusChangeListener { _, has -> if (!has) { validateProxy(); saveProxyPrefs() } }
        }
        proxyRow.addView(proxyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        proxyRow.addView(Button(this).apply {
            text = "چسباندن"
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim()
                val line = t?.lineSequence()?.map { it.trim() }?.firstOrNull {
                    it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://")
                }
                if (line != null) {
                    proxyInput.setText(line)
                    proxyBox.isChecked = true
                    validateProxy(); saveProxyPrefs(); hintReconnect()
                } else {
                    toast("لینک کانفیگ در کلیپ‌بورد نیست")
                }
            }
        })
        root.addView(proxyRow)
        proxyInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        root.addView(proxyInfo)
        directIrBox = CheckBox(this).apply {
            text = "سایت‌های ‎.ir‎ بدون کانفیگ (مستقیم، ولی باز هم تجمیع‌شده)"
            setOnCheckedChangeListener { _, _ -> saveProxyPrefs(); hintReconnect() }
        }
        root.addView(directIrBox)
        vpnBtn = Button(this).apply { setOnClickListener { toggleVpn() } }
        root.addView(vpnBtn)
        vpnText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(vpnText)
        root.addView(View(this).apply { setBackgroundColor(Color.LTGRAY) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        root.addView(TextView(this).apply {
            text = "دانلودر تکه‌ای (یک فایل، حداکثر سرعت)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "لینک فایل:"
            textSize = 15f
        })

        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlInput = EditText(this).apply {
            hint = "https://example.com/file.zip"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            isSingleLine = true
        }
        urlRow.addView(urlInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(Button(this).apply {
            text = "چسباندن"
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                val u = t?.let { extractUrl(it) }
                if (u != null) urlInput.setText(u) else toast("لینکی در کلیپ‌بورد نیست")
            }
        })
        root.addView(urlRow)

        val boxes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wifiBox = CheckBox(this).apply { text = "وای‌فای" }
        cellBox = CheckBox(this).apply { text = "داده همراه" }
        boxes.addView(wifiBox)
        boxes.addView(cellBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(24) })
        root.addView(boxes)

        threadsLabel = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(threadsLabel)
        threadsBar = SeekBar(this).apply {
            max = 31 // 1..32
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateThreadsLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(threadsBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        startBtn = Button(this).apply {
            text = "شروع دانلود"
            setOnClickListener { startDownload() }
        }
        cancelBtn = Button(this).apply {
            text = "لغو"
            setOnClickListener { cancelDownload() }
        }
        btnRow.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            setTextIsSelectable(true)
        }
        root.addView(statusText)

        root.addView(TextView(this).apply {
            text = "لینک‌ها"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        linksText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(linksText)

        return ScrollView(this).apply { addView(root) }
    }
}
