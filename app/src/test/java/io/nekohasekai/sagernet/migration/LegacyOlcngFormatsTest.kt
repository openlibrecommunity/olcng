package io.nekohasekai.sagernet.migration

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.*
import org.junit.Test

class LegacyOlcngFormatsTest {
    @Test
    fun vlessRealityAndXhttp() {
        val profile = LegacyOlcngFormats.profile("""{
            "configVersion":4,"configType":"VLESS","subscriptionId":"personal",
            "remarks":"my server","server":"example.org","serverPort":"443",
            "password":"12345678-1234-1234-1234-123456789012","flow":"xtls-rprx-vision",
            "network":"xhttp","path":"/proxy","host":"cdn.example.org",
            "security":"reality","sni":"example.net","publicKey":"key","shortId":"abcd",
            "fingerPrint":"chrome","alpn":"h2,http/1.1","xhttpMode":"auto","xhttpExtra":"{}"
        }""", null)
        val bean = profile.bean as VLESSBean
        assertEquals("personal", profile.subscriptionId)
        assertEquals("my server", bean.name)
        assertEquals("example.org", bean.serverAddress)
        assertEquals(443, bean.serverPort.toInt())
        assertEquals("splithttp", bean.type)
        assertEquals("xtls-rprx-vision", bean.flow)
        assertEquals("reality", bean.security)
        assertEquals("key", bean.realityPublicKey)
        assertEquals("chrome", bean.realityFingerprint)
        assertEquals("h2\nhttp/1.1", bean.alpn)
    }

    @Test
    fun customUsesExactRawConfigNotProfileMetadata() {
        val raw = """{"inbounds":[],"outbounds":[{"protocol":"freedom"}],"routing":{"rules":[]}}"""
        val profile = LegacyOlcngFormats.profile("""{"configType":"CUSTOM","remarks":"custom"}""", raw)
        val bean = profile.bean as ConfigBean
        assertEquals(raw, bean.content)
        assertEquals("v2ray", bean.type)
        assertEquals("custom", bean.name)
    }

    @Test
    fun unsupportedAndMissingRawAreExplicitlyBackedUp() {
        val unsupported = LegacyOlcngFormats.profile("""{"configType":"POLICYGROUP"}""", null)
        val missing = LegacyOlcngFormats.profile("""{"configType":"CUSTOM"}""", null)
        assertNull(unsupported.bean)
        assertNotNull(unsupported.warning)
        assertNull(missing.bean)
        assertNotNull(missing.warning)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidEndpointDoesNotBecomeLocalhost() {
        LegacyOlcngFormats.profile("""{"configType":"VLESS","server":"example.org","serverPort":"bad"}""", null)
    }

    @Test
    fun wireguardKeysAndHysteriaObfuscation() {
        val wg = LegacyOlcngFormats.profile("""{
            "configType":"WIREGUARD","server":"example.org","serverPort":"51820",
            "secretKey":"private","publicKey":"public","preSharedKey":"shared",
            "localAddress":"10.0.0.2/32","mtu":1280,"reserved":"1,2,3"
        }""", null).bean as WireGuardBean
        assertEquals("private", wg.privateKey)
        assertEquals("public", wg.peerPublicKey)
        assertEquals("shared", wg.peerPreSharedKey)
        assertEquals(1280, wg.mtu.toInt())
        val hy = LegacyOlcngFormats.profile("""{
            "configType":"HYSTERIA2","server":"example.org","serverPort":"443",
            "password":"auth","obfsPassword":"secret","portHopping":"443,8443",
            "portHoppingInterval":"30s","insecure":true
        }""", null).bean as Hysteria2Bean
        assertEquals("auth", hy.auth)
        assertEquals("salamander", hy.obfsType)
        assertEquals("443,8443", hy.serverPorts)
        assertEquals(30L, hy.hopInterval.toLong())
        assertTrue(hy.allowInsecure)
    }

    @Test
    fun subscriptionsAndLocalGroups() {
        val sub = LegacyOlcngFormats.subscription("""{
            "remarks":"private","url":"https://example.org/token","enabled":false,
            "autoUpdate":true,"userAgent":"custom","lastUpdated":1700000000000
        }""")
        assertEquals("https://example.org/token", sub.bean.link)
        assertFalse(sub.bean.autoUpdate)
        assertFalse(sub.enabled)
        assertEquals("custom", sub.bean.customUserAgent)
        assertEquals(1700000000L, sub.bean.lastUpdated.toLong())
        assertEquals("", LegacyOlcngFormats.subscription("""{"remarks":"Ungrouped","url":""}""").bean.link)
    }

    @Test
    fun dnsAndAppSettings() {
        val json = JsonParser.parseString("""{
            "pref_remote_dns":"1.1.1.1,https://dns.example.org/dns-query",
            "pref_domestic_dns":"tls://dns.example.net",
            "pref_per_app_proxy":true,"pref_bypass_apps":false
        }""").asJsonObject
        val settings = LegacyOlcngFormats.settings(json, setOf("org.example.app"))
        assertEquals("1.1.1.1\nhttps://dns.example.org/dns-query", settings.remoteDns)
        assertEquals("tls://dns.example.net", settings.directDns)
        assertEquals(true, settings.proxyApps)
        assertEquals(false, settings.bypass)
        assertEquals(setOf("org.example.app"), settings.packages)
        val unsupported = JsonParser.parseString("""{"pref_remote_dns":"localhost:bad port"}""").asJsonObject
        assertNull(LegacyOlcngFormats.settings(unsupported, null).remoteDns)
        val empty = LegacyOlcngFormats.settings(JsonParser.parseString("{}").asJsonObject, null)
        assertNull(empty.proxyApps)
        assertNull(empty.remoteDns)
    }

    @Test
    fun planIsIdempotentAfterCommitBeforeMarker() {
        val first = LegacyOlcngFormats.pending(listOf("a", "a", "b"), emptySet())
        assertEquals(listOf("a", "b"), first)
        val existing = first.mapTo(hashSetOf()) { LegacyOlcngFormats.stableKey("profile", it) }
        assertTrue(LegacyOlcngFormats.pending(listOf("b", "a"), existing).isEmpty())
        assertEquals(listOf("c"), LegacyOlcngFormats.pending(listOf("a", "c"), existing))
        assertNotEquals(LegacyOlcngFormats.stableKey("profile", "a"), LegacyOlcngFormats.stableKey("sub", "a"))
    }

    @Test
    fun recognizeOnlyExactRegistryUrlsWithEquivalentGithubPaths() {
        val url = "https://raw.githubusercontent.com/igareck/repo/refs/heads/main/BLACK_SS%2BAll.txt"
        val alias = "https://raw.githubusercontent.com/igareck/repo/main/BLACK_SS+All.txt"
        assertEquals(LegacyOlcngFormats.normalizedUrl(url), LegacyOlcngFormats.normalizedUrl(alias))
        assertNotEquals(LegacyOlcngFormats.normalizedUrl(url), LegacyOlcngFormats.normalizedUrl("$alias?token=private"))
    }
}
