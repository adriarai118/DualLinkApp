package com.duallink.downloader

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turns a share link (vless:// vmess:// trojan:// ss://) into a full Xray config:
 *   socks inbound on 127.0.0.1:port  <- hev-socks5-tunnel
 *   outbound "proxy"                 -> your server (every socket bound to a link by us)
 *   outbound "direct"                -> .ir sites go straight out (still balanced)
 * Mux is never enabled: it would squeeze everything into one connection on one link.
 */
object ProxyConfig {

    class Parsed(val name: String, val protocol: String, val host: String, val port: Int, val outbound: JSONObject)

    fun parse(link: String): Parsed {
        val s = link.trim()
        return when {
            s.startsWith("vless://", true) -> parseVlessTrojan(s, "vless")
            s.startsWith("trojan://", true) -> parseVlessTrojan(s, "trojan")
            s.startsWith("vmess://", true) -> parseVmess(s)
            s.startsWith("ss://", true) -> parseShadowsocks(s)
            else -> throw IllegalArgumentException("فقط لینک‌های vless، vmess، trojan و ss پشتیبانی می‌شود")
        }
    }

    /**
     * @param serverIp the server's address resolved on the Android side, or null to keep the name.
     * @param dnsServers DNS servers for Xray itself (needed for direct .ir sites). Android has no
     *   /etc/resolv.conf, so Xray's built-in resolver must be given servers explicitly.
     */
    fun buildConfig(p: Parsed, socksPort: Int, directIr: Boolean, serverIp: String?, dnsServers: List<String>, logPath: String): String {
        val outbound = JSONObject(p.outbound.toString())
        if (serverIp != null) outbound.getJSONObject("settings").put("address", serverIp)

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))

        val rules = JSONArray()
        if (directIr) {
            rules.put(JSONObject()
                .put("type", "field")
                .put("domain", JSONArray().put("regexp:\\.ir$"))
                .put("outboundTag", "direct"))
        }
        val servers = JSONArray()
        (dnsServers + listOf("1.1.1.1", "8.8.8.8")).distinct().forEach { servers.put(it) }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning").put("error", logPath).put("access", "none"))
            .put("dns", JSONObject().put("servers", servers).put("queryStrategy", "UseIPv4"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")
                    .put("settings", JSONObject().put("domainStrategy", "UseIPv4"))))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
            .toString()
    }

    // ------------------------------------------------------------ protocols

    private fun parseVlessTrojan(link: String, proto: String): Parsed {
        val u = Uri.parse(link)
        val host = u.host ?: throw IllegalArgumentException("آدرس سرور در لینک نیست")
        val port = if (u.port > 0) u.port else 443
        val secret = decode(u.encodedUserInfo ?: throw IllegalArgumentException("UUID/رمز در لینک نیست"))
        val q = params(u)

        val settings = JSONObject().put("address", host).put("port", port)
        if (proto == "vless") {
            settings.put("id", secret)
            settings.put("encryption", q["encryption"]?.ifEmpty { null } ?: "none")
            q["flow"]?.takeIf { it.isNotEmpty() }?.let { settings.put("flow", it) }
        } else {
            settings.put("password", secret)
        }
        val defaultSecurity = if (proto == "trojan") "tls" else "none"
        val out = JSONObject()
            .put("tag", "proxy")
            .put("protocol", proto)
            .put("settings", settings)
            .put("streamSettings", stream(q, host, defaultSecurity))
        return Parsed(fragmentName(u, host), proto, host, port, out)
    }

    private fun parseVmess(link: String): Parsed {
        val j = JSONObject(String(b64(link.substring(8)), Charsets.UTF_8))
        val host = j.optString("add").ifEmpty { throw IllegalArgumentException("آدرس سرور در لینک نیست") }
        val port = j.optString("port").toIntOrNull() ?: 443
        val q = HashMap<String, String>()
        q["type"] = j.optString("net", "tcp")
        q["headerType"] = j.optString("type", "none")
        q["host"] = j.optString("host")
        q["path"] = j.optString("path")
        q["security"] = j.optString("tls").let { if (it == "tls" || it == "reality") it else "none" }
        q["sni"] = j.optString("sni")
        q["alpn"] = j.optString("alpn")
        q["fp"] = j.optString("fp")
        q["serviceName"] = j.optString("path")
        q["mode"] = j.optString("type")

        val settings = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("id", j.optString("id"))
            .put("security", j.optString("scy").ifEmpty { "auto" })
        val out = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", settings)
            .put("streamSettings", stream(q, host, "none"))
        return Parsed(j.optString("ps").ifEmpty { host }, "vmess", host, port, out)
    }

    private fun parseShadowsocks(link: String): Parsed {
        val body = link.substring(5)
        val name = body.substringAfter('#', "").let { decode(it) }
        val main = body.substringBefore('#').substringBefore('?').substringBefore("/?")
        val (userInfo, hostPort) = if (main.contains('@')) {
            val ui = main.substringBeforeLast('@')
            val decoded = decode(ui)
            (if (decoded.contains(':')) decoded else String(b64(ui), Charsets.UTF_8)) to main.substringAfterLast('@')
        } else {
            // Legacy: whole thing base64("method:password@host:port")
            val all = String(b64(main), Charsets.UTF_8)
            all.substringBeforeLast('@') to all.substringAfterLast('@')
        }
        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':')
        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: throw IllegalArgumentException("پورت در لینک ss نیست")
        val out = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject()
                .put("address", host).put("port", port)
                .put("method", method).put("password", password))
        return Parsed(name.ifEmpty { host }, "shadowsocks", host, port, out)
    }

    // ------------------------------------------------------------ transport

    private fun stream(q: Map<String, String>, host: String, defaultSecurity: String): JSONObject {
        var network = (q["type"] ?: "tcp").lowercase().ifEmpty { "tcp" }
        if (network == "raw") network = "tcp"
        if (network == "splithttp") network = "xhttp"
        if (network == "h2") network = "http"
        val security = (q["security"] ?: defaultSecurity).lowercase().ifEmpty { defaultSecurity }
        val sni = q["sni"]?.ifEmpty { null } ?: q["peer"]?.ifEmpty { null }
        val hostHeader = q["host"].orEmpty()
        val path = q["path"].orEmpty()

        val ss = JSONObject().put("network", network).put("security", security)
        when (network) {
            "ws" -> ss.put("wsSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("host", hostHeader.ifEmpty { sni ?: host }))
            "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("host", hostHeader.ifEmpty { sni ?: host }))
            "xhttp" -> ss.put("xhttpSettings", JSONObject()
                .put("path", path.ifEmpty { "/" })
                .put("host", hostHeader.ifEmpty { sni ?: host })
                .put("mode", q["mode"]?.ifEmpty { null } ?: "auto"))
            "grpc" -> ss.put("grpcSettings", JSONObject()
                .put("serviceName", q["serviceName"].orEmpty())
                .put("multiMode", q["mode"] == "multi"))
            "tcp" -> if (q["headerType"] == "http") {
                val req = JSONObject()
                    .put("path", JSONArray().put(path.ifEmpty { "/" }))
                if (hostHeader.isNotEmpty()) {
                    req.put("headers", JSONObject().put("Host", JSONArray(hostHeader.split(',').map { it.trim() })))
                }
                ss.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", req)))
            }
        }
        val fp = q["fp"]?.ifEmpty { null } ?: "chrome"
        when (security) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", sni ?: hostHeader.ifEmpty { host })
                    .put("fingerprint", fp)
                q["alpn"]?.takeIf { it.isNotEmpty() }?.let { a ->
                    tls.put("alpn", JSONArray(a.split(',').map { it.trim() }))
                }
                q["pcs"]?.takeIf { it.isNotEmpty() }?.let { tls.put("pinnedPeerCertSha256", it) }
                q["vcn"]?.takeIf { it.isNotEmpty() }?.let { tls.put("verifyPeerCertByName", it) }
                q["ech"]?.takeIf { it.isNotEmpty() }?.let { tls.put("echConfigList", it) }
                ss.put("tlsSettings", tls)
            }
            "reality" -> {
                val r = JSONObject()
                    .put("serverName", sni ?: host)
                    .put("fingerprint", fp)
                    .put("publicKey", q["pbk"].orEmpty())
                    .put("shortId", q["sid"].orEmpty())
                q["spx"]?.takeIf { it.isNotEmpty() }?.let { r.put("spiderX", it) }
                q["pqv"]?.takeIf { it.isNotEmpty() }?.let { r.put("mldsa65Verify", it) }
                ss.put("realitySettings", r)
            }
        }
        return ss
    }

    // -------------------------------------------------------------- helpers

    private fun params(u: Uri): Map<String, String> {
        val m = HashMap<String, String>()
        for (k in u.queryParameterNames) m[k] = u.getQueryParameter(k).orEmpty()
        return m
    }

    private fun fragmentName(u: Uri, fallback: String) = u.fragment?.ifEmpty { null } ?: fallback

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun b64(s: String): ByteArray {
        val clean = s.trim().replace('-', '+').replace('_', '/').replace("\n", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
