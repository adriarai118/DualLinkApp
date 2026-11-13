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
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 42
    }

    private lateinit var vpnBtn: Button
    private lateinit var stickyBox: CheckBox
    private lateinit var vpnText: TextView
    private lateinit var proxyBox: CheckBox
    private lateinit var autoBox: CheckBox
    private lateinit var directIrBox: CheckBox
    private lateinit var proxyInfo: TextView
    private lateinit var configList: LinearLayout
    private lateinit var chart: SpeedChart
    private lateinit var stText: TextView
    private lateinit var stBtn: Button
    private lateinit var stUrl: EditText
    private var shownConfigsKey = ""
    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button

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

        // Read everything first: setting a checkbox fires its listener, which saves all fields.
        ConfigStore.load(this)
        val savedSticky = prefs.getBoolean("sticky", false)
        val savedProxyOn = prefs.getBoolean(AggregatorVpnService.PREF_PROXY_ON, false)
        val savedDirectIr = prefs.getBoolean(AggregatorVpnService.PREF_DIRECT_IR, true)
        stickyBox.isChecked = savedSticky
        directIrBox.isChecked = savedDirectIr
        proxyBox.isChecked = savedProxyOn
        autoBox.isChecked = ConfigStore.auto
        stUrl.setText(prefs.getString("speedtest_url", SpeedTest.DEFAULT_DOWN))
        rebuildConfigList()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Updater.checkAsync(this, force = false)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
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
            .putBoolean(AggregatorVpnService.PREF_PROXY_ON, proxyBox.isChecked)
            .putBoolean(AggregatorVpnService.PREF_DIRECT_IR, directIrBox.isChecked)
            .apply()
        ConfigStore.auto = autoBox.isChecked
        ConfigStore.save(this)
    }

    private fun startVpn() {
        if (proxyBox.isChecked && ConfigStore.list().isEmpty()) {
            toast("اول یک کانفیگ اضافه کنید")
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

    // -------------------------------------------------------------- configs

    private val anyNet get() = run {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.takeIf {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
        }
    }

    private fun addFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val trimmed = t.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            ConfigStore.addSubscription(this, trimmed)
            ConfigStore.refreshSubscriptionsAsync(this) { anyNet }
            toast("لینک اشتراک اضافه شد؛ در حال دریافت…")
            return
        }
        val n = ConfigStore.addFromText(this, t)
        toast(if (n > 0) "$n کانفیگ اضافه شد" else "کانفیگ معتبر جدیدی در کلیپ‌بورد نبود")
        if (n > 0 && !proxyBox.isChecked) proxyBox.isChecked = true
        rebuildConfigList()
    }

    private fun askSubscription() {
        val input = EditText(this).apply {
            hint = "https://…"
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("لینک اشتراک (Subscription)")
            .setView(input)
            .setPositiveButton("افزودن") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http")) {
                    ConfigStore.addSubscription(this, url)
                    ConfigStore.refreshSubscriptionsAsync(this) { anyNet }
                } else {
                    toast("لینک باید با http شروع شود")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(index: Int, name: String) {
        android.app.AlertDialog.Builder(this)
            .setMessage("کانفیگ «$name» حذف شود؟")
            .setPositiveButton("حذف") { _, _ -> ConfigStore.remove(this, index); rebuildConfigList() }
            .setNegativeButton("انصراف", null)
            .show()
    }

    /** Rebuilds the config rows (only when the list, selection or pings changed). */
    private fun rebuildConfigList(force: Boolean = true) {
        val all = ConfigStore.list()
        val key = all.joinToString("|") { it.link.hashCode().toString() + ":" + it.pingMs } + "#" + ConfigStore.selected
        if (!force && key == shownConfigsKey) return
        shownConfigsKey = key
        configList.removeAllViews()
        if (all.isEmpty()) {
            configList.addView(note("هنوز کانفیگی اضافه نشده. لینک کانفیگ یا لینک اشتراک را کپی کنید و «افزودن از کلیپ‌بورد» را بزنید."))
            return
        }
        for ((i, e) in all.withIndex()) {
            val sel = i == ConfigStore.selected
            val ping = when {
                e.pingMs == -2 -> ""
                e.pingMs < 0 -> "  ✗"
                else -> "  ${e.pingMs}ms"
            }
            configList.addView(TextView(this).apply {
                text = (if (sel) "◉ " else "○ ") + e.name + "  — " + e.protocol + ping
                textSize = 14f
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (sel) setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (e.pingMs == -1) Color.GRAY else Color.BLACK)
                setOnClickListener {
                    ConfigStore.select(this@MainActivity, i)
                    if (autoBox.isChecked) autoBox.isChecked = false
                    rebuildConfigList()
                    hintReconnect()
                }
                setOnLongClickListener { confirmDelete(i, e.name); true }
            })
        }
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
        if (VpnState.running) toast("برای اعمال تغییر، خاموش و دوباره روشن کنید")
    }

    // --------------------------------------------------------------- update

    private fun onUpdateClick() {
        if (Updater.available == null) {
            Updater.checkAsync(this, force = true)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("اجازه «نصب برنامه» را برای DualLink روشن کنید و برگردید")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        Updater.downloadAndInstallAsync(this)
    }

    // --------------------------------------------------------------- render

    private fun render() {
        val running = VpnState.running
        vpnBtn.text = if (running) "خاموش کردن" else "روشن کردن"
        val b = VpnState.balancer
        val sb = StringBuilder("وضعیت: ").append(VpnState.message)
        if (running && b != null) {
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
            if (VpnState.proxyName != null) {
                val tail = VpnState.xrayLogTail()
                if (tail.isNotEmpty()) sb.append("\n\nآخرین پیام‌های Xray:\n").append(tail)
            }
        } else if (!running) {
            sb.append("\n").append(idleNetworkSummary())
        }
        vpnText.text = sb.toString()
        chart.invalidate()
        rebuildConfigList(force = false)
        val subs = ConfigStore.subscriptions().size
        proxyInfo.text = buildString {
            append("${ConfigStore.list().size} کانفیگ")
            if (subs > 0) append("، $subs اشتراک")
            if (ConfigStore.message.isNotEmpty()) append("\n").append(ConfigStore.message)
        }

        stBtn.text = if (SpeedTest.running) "توقف تست" else "شروع تست سرعت"
        stText.text = (listOf(SpeedTest.phase).filter { it.isNotEmpty() } + SpeedTest.results).joinToString("\n")

        val u = StringBuilder("نسخه فعلی: ").append(Updater.currentVersionName(this))
        if (Updater.message.isNotEmpty()) u.append("\n").append(Updater.message)
        Updater.engine?.let { e ->
            if (e.total > 0) {
                u.append("\n").append(fmtBytes(e.written.get())).append(" از ").append(fmtBytes(e.total))
                    .append(" — ").append(fmtSpeed(e.totalSpeedBps))
            }
        }
        Updater.available?.notes?.takeIf { it.isNotBlank() }?.let { u.append("\nتغییرات: ").append(it) }
        updateText.text = u.toString()
        updateBtn.isEnabled = !Updater.busy
        updateBtn.text = if (Updater.available != null) "دانلود و نصب نسخه جدید" else "بررسی به‌روزرسانی"
    }

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
            "   —   داده همراه: " + (if (cell) "فعال ✓" else "خاموش")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.DKGRAY)
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.LTGRAY) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        root.addView(title("تجمیع وای‌فای + داده همراه (کل گوشی)"))
        root.addView(note(
            "اتصال‌های همه برنامه‌ها بین وای‌فای و دیتا پخش می‌شود (دانلود و آپلود). " +
                "هر اتصال تکی فقط از یک لینک می‌رود؛ برنامه‌هایی که چند اتصال باز می‌کنند بیشترین سود را می‌برند."
        ))

        vpnBtn = Button(this).apply { setOnClickListener { toggleVpn() } }
        root.addView(vpnBtn)
        vpnText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(vpnText)
        chart = SpeedChart(this)
        root.addView(chart)

        stickyBox = CheckBox(this).apply {
            text = "حالت سازگار: هر سایت فقط از یک لینک (اگر بانک یا سایتی مدام از حساب خارج شد)"
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("sticky", checked).apply()
                VpnState.balancer?.sticky = checked
            }
        }
        root.addView(stickyBox)

        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(title("کانفیگ VPN (اختیاری)"))
        proxyBox = CheckBox(this).apply {
            text = "از طریق کانفیگ VPN — تجمیع روی خود VPN"
            setOnCheckedChangeListener { _, _ -> saveProxyPrefs(); hintReconnect() }
        }
        root.addView(proxyBox)
        autoBox = CheckBox(this).apply {
            text = "انتخاب خودکار بهترین کانفیگ (کمترین پینگ) و جایگزینی خودکار اگر قطع شد"
            setOnCheckedChangeListener { _, _ -> saveProxyPrefs(); hintReconnect() }
        }
        root.addView(autoBox)
        directIrBox = CheckBox(this).apply {
            text = "سایت‌های ‎.ir‎ بدون کانفیگ (مستقیم، ولی باز هم تجمیع‌شده)"
            setOnCheckedChangeListener { _, _ -> saveProxyPrefs(); hintReconnect() }
        }
        root.addView(directIrBox)

        fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        row1.addView(smallButton("افزودن از کلیپ‌بورد") { addFromClipboard() }, lp())
        row1.addView(smallButton("لینک اشتراک") { askSubscription() }, lp())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton("تست پینگ همه") { ConfigStore.pingAllAsync({ anyNet }) }, lp())
        row2.addView(smallButton("به‌روزرسانی اشتراک‌ها") {
            if (ConfigStore.subscriptions().isEmpty()) toast("اشتراکی ثبت نشده")
            else ConfigStore.refreshSubscriptionsAsync(this) { anyNet }
        }, lp())
        root.addView(row2)
        proxyInfo = note("")
        root.addView(proxyInfo)
        root.addView(note("برای انتخاب روی کانفیگ بزنید؛ برای حذف نگه دارید."))
        configList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(configList)
        root.addView(smallButton("حذف همه کانفیگ‌ها و اشتراک‌ها") {
            android.app.AlertDialog.Builder(this)
                .setMessage("همه کانفیگ‌ها و اشتراک‌ها حذف شوند؟")
                .setPositiveButton("حذف همه") { _, _ -> ConfigStore.clearAll(this); rebuildConfigList() }
                .setNegativeButton("انصراف", null)
                .show()
        })

        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(title("تست سرعت (چند اتصال، هر لینک جدا)"))
        root.addView(note("مستقیم روی هر لینک تست می‌کند: فقط وای‌فای، فقط دیتا، هر دو با هم، و آپلود. حدود ۴۰ ثانیه و چند ده مگابایت دیتا مصرف می‌کند."))
        stUrl = EditText(this).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(stUrl)
        stBtn = Button(this).apply {
            setOnClickListener {
                if (SpeedTest.running) {
                    SpeedTest.cancel()
                } else {
                    val url = stUrl.text.toString().trim().ifEmpty { SpeedTest.DEFAULT_DOWN }
                    prefs.edit().putString("speedtest_url", url).apply()
                    SpeedTest.startAsync(this@MainActivity, url, SpeedTest.DEFAULT_UP)
                }
            }
        }
        root.addView(stBtn)
        stText = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(stText)

        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(title("به‌روزرسانی"))
        updateText = TextView(this).apply { textSize = 13f }
        root.addView(updateText)
        updateBtn = Button(this).apply { setOnClickListener { onUpdateClick() } }
        root.addView(updateBtn)

        return ScrollView(this).apply { addView(root) }
    }
}
