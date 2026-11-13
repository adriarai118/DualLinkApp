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
    private lateinit var proxyInput: EditText
    private lateinit var directIrBox: CheckBox
    private lateinit var proxyInfo: TextView
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
        val savedSticky = prefs.getBoolean("sticky", false)
        val savedLink = prefs.getString(AggregatorVpnService.PREF_PROXY_LINK, "")
        val savedProxyOn = prefs.getBoolean(AggregatorVpnService.PREF_PROXY_ON, false)
        val savedDirectIr = prefs.getBoolean(AggregatorVpnService.PREF_DIRECT_IR, true)
        stickyBox.isChecked = savedSticky
        proxyInput.setText(savedLink)
        directIrBox.isChecked = savedDirectIr
        proxyBox.isChecked = savedProxyOn
        validateProxy()

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
            val engine = if (ProxyConfig.legacySs(p) != null) "موتور داخلی" else "Xray"
            proxyInfo.text = "✓ ${p.name} — ${p.protocol} — ${p.host}:${p.port}  ($engine)"
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
        proxyInput.isEnabled = !running
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
            setOnCheckedChangeListener { _, _ -> validateProxy(); saveProxyPrefs(); hintReconnect() }
        }
        root.addView(proxyBox)
        val proxyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        proxyInput = EditText(this).apply {
            hint = "vless://…  vmess://…  trojan://…  ss://…"
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
        proxyInfo = note("")
        root.addView(proxyInfo)
        directIrBox = CheckBox(this).apply {
            text = "سایت‌های ‎.ir‎ بدون کانفیگ (مستقیم، ولی باز هم تجمیع‌شده)"
            setOnCheckedChangeListener { _, _ -> saveProxyPrefs(); hintReconnect() }
        }
        root.addView(directIrBox)

        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(title("به‌روزرسانی"))
        updateText = TextView(this).apply { textSize = 13f }
        root.addView(updateText)
        updateBtn = Button(this).apply { setOnClickListener { onUpdateClick() } }
        root.addView(updateBtn)

        return ScrollView(this).apply { addView(root) }
    }
}
