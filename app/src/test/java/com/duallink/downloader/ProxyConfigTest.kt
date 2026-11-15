package com.duallink.downloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProxyConfigTest {

    private fun stream(p: ProxyConfig.Parsed) = p.outbound.getJSONObject("streamSettings")
    private fun settings(p: ProxyConfig.Parsed) = p.outbound.getJSONObject("settings")

    @Test fun legacyShadowsocksLinkFromUser() {
        val p = ProxyConfig.parse("ss://cmM0LW1kNToxNGZGUHJiZXpFM0hEWnpzTU9yNg==@146.70.20.147:8080#Server%2059%20%F0%9F%87%A9%F0%9F%87%AA")
        assertEquals("shadowsocks", p.protocol)
        assertEquals("146.70.20.147", p.host)
        assertEquals(8080, p.port)
        assertEquals("Server 59 🇩🇪", p.name)
        assertEquals("rc4-md5", settings(p).getString("method"))
        assertEquals("14fFPrbezE3HDZzsMOr6", settings(p).getString("password"))
        assertNotNull(ProxyConfig.legacySs(p))
    }

    @Test fun modernShadowsocksGoesToXray() {
        val p = ProxyConfig.parse("ss://aes-256-gcm:p%40ss@1.2.3.4:443#plain")
        assertEquals("aes-256-gcm", settings(p).getString("method"))
        assertEquals("p@ss", settings(p).getString("password"))
        assertNull(ProxyConfig.legacySs(p))
    }

    @Test fun unsupportedCipherIsRejected() {
        try {
            ProxyConfig.parse("ss://" + java.util.Base64.getEncoder().encodeToString("bf-cfb:x".toByteArray()) + "@1.2.3.4:1")
            fail("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bf-cfb"))
        }
    }

    @Test fun vlessReality() {
        val p = ProxyConfig.parse(
            "vless://0f1e2d3c-aaaa-bbbb-cccc-1234567890ab@example.com:443?encryption=none&flow=xtls-rprx-vision" +
                "&security=reality&sni=www.google.com&fp=firefox&pbk=PUBKEY&sid=ab12&type=tcp#My%20Server"
        )
        assertEquals("My Server", p.name)
        assertEquals("example.com", p.host)
        assertEquals("0f1e2d3c-aaaa-bbbb-cccc-1234567890ab", settings(p).getString("id"))
        assertEquals("xtls-rprx-vision", settings(p).getString("flow"))
        val r = stream(p).getJSONObject("realitySettings")
        assertEquals("reality", stream(p).getString("security"))
        assertEquals("www.google.com", r.getString("serverName"))
        assertEquals("PUBKEY", r.getString("publicKey"))
        assertEquals("ab12", r.getString("shortId"))
        assertEquals("firefox", r.getString("fingerprint"))
    }

    @Test fun vlessWsTlsFillsHostHeader() {
        val p = ProxyConfig.parse("vless://id@cdn.example.net:443?type=ws&security=tls&sni=sub.example.net&path=%2Fws%3Fed%3D2048#ws")
        val ss = stream(p)
        assertEquals("ws", ss.getString("network"))
        assertEquals("/ws?ed=2048", ss.getJSONObject("wsSettings").getString("path"))
        assertEquals("sub.example.net", ss.getJSONObject("wsSettings").getString("host"))
        assertEquals("sub.example.net", ss.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test fun ipv6HostAndDefaultPort() {
        val p = ProxyConfig.parse("vless://id@[2001:db8::1]:8443?security=none#v6")
        assertEquals("2001:db8::1", p.host)
        assertEquals(8443, p.port)
        val q = ProxyConfig.parse("trojan://secret@example.org?sni=example.org#t")
        assertEquals(443, q.port)
        assertEquals("tls", stream(q).getString("security"))
        assertEquals("secret", settings(q).getString("password"))
    }

    @Test fun vmessBase64Json() {
        val json = JSONObject()
            .put("v", "2").put("ps", "vm").put("add", "vm.example.com").put("port", "8443")
            .put("id", "uuid-1").put("net", "grpc").put("path", "svc").put("tls", "tls").put("sni", "vm.example.com")
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val p = ProxyConfig.parse(link)
        assertEquals("vm", p.name)
        assertEquals(8443, p.port)
        assertEquals("svc", stream(p).getJSONObject("grpcSettings").getString("serviceName"))
        assertEquals("uuid-1", settings(p).getString("id"))
    }

    @Test fun buildConfigUsesResolvedIpAndKeepsSni() {
        val p = ProxyConfig.parse("vless://id@server.example.com:443?type=ws&security=tls#x")
        val cfg = JSONObject(ProxyConfig.buildConfig(p, 10808, true, "203.0.113.7", listOf("10.0.0.1"), "/tmp/x.log"))
        val out = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("203.0.113.7", out.getJSONObject("settings").getString("address"))
        assertEquals("server.example.com",
            out.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("server.example.com",
            out.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("host"))
        val dns = cfg.getJSONObject("dns").getJSONArray("servers")
        assertEquals("10.0.0.1", dns.getString(0))
        val rule = cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("regexp:\\.ir$", rule.getJSONArray("domain").getString(0))
        // The original parsed config must not be modified.
        assertEquals("server.example.com", settings(p).getString("address"))
    }

    @Test fun subscriptionBase64AndPlain() {
        val lines = "vless://a@h1:443#1\nss://YWVzLTI1Ni1nY206cHc=@h2:80#2\nnot a link"
        val b64 = java.util.Base64.getEncoder().encodeToString(lines.toByteArray())
        assertEquals(2, ConfigStore.linksIn(ConfigStore.decodeSubscription(b64)).size)
        assertEquals(2, ConfigStore.linksIn(ConfigStore.decodeSubscription(lines)).size)
        // URL-safe alphabet, no padding
        val urlSafe = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(lines.toByteArray())
        assertEquals(2, ConfigStore.linksIn(ConfigStore.decodeSubscription(urlSafe)).size)
    }
}
