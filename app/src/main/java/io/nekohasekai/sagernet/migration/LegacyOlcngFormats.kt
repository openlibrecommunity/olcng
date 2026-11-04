package io.nekohasekai.sagernet.migration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria2.Hysteria2Bean
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import java.net.URI
import java.util.UUID

internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.flag(key: String, default: Boolean = false): Boolean =
    get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

internal object LegacyOlcngFormats {
    data class Profile(val subscriptionId: String, val bean: AbstractBean?, val warning: String? = null)
    data class Subscription(val name: String, val bean: SubscriptionBean, val enabled: Boolean)
    data class Settings(
        val probeUrl: String? = null,
        val remoteDns: String? = null,
        val directDns: String? = null,
        val proxyApps: Boolean? = null,
        val bypass: Boolean? = null,
        val packages: Set<String>? = null,
    )

    fun stableKey(kind: String, guid: String): String = UUID.nameUUIDFromBytes(
        "oweng:olcng:$kind:$guid".toByteArray(Charsets.UTF_8)
    ).toString()

    fun pending(keys: Collection<String>, existing: Set<String>): List<String> =
        keys.distinct().filterNot { stableKey("profile", it) in existing }

    fun subscription(json: String): Subscription {
        val p = JsonParser.parseString(json).asJsonObject
        val bean = SubscriptionBean().apply {
            initializeDefaultValues()
            link = p.text("url")
            customUserAgent = p.text("userAgent")
            autoUpdate = p.flag("enabled", true) && p.flag("autoUpdate")
            p.text("updateInterval").toIntOrNull()?.takeIf { it > 0 }?.let { autoUpdateDelay = it }
            p.text("lastUpdated").toLongOrNull()?.let { lastUpdated = (it.coerceAtLeast(0) / 1000) }
        }
        return Subscription(p.text("remarks"), bean, p.flag("enabled", true))
    }

    fun profile(json: String, raw: String?): Profile {
        val p = JsonParser.parseString(json).asJsonObject
        val sub = p.text("subscriptionId")
        val bean: AbstractBean = when (p.text("configType")) {
            "VMESS" -> VMessBean().apply { uuid = p.text("password"); encryption = p.text("method").ifBlank { "auto" } }
            "VLESS" -> VLESSBean().apply { uuid = p.text("password"); flow = p.text("flow"); encryption = "none" }
            "TROJAN" -> TrojanBean().apply { password = p.text("password") }
            "SHADOWSOCKS" -> ShadowsocksBean().apply { password = p.text("password"); method = p.text("method") }
            "SOCKS" -> SOCKSBean().apply { username = p.text("username"); password = p.text("password") }
            "HTTP" -> HttpBean().apply { username = p.text("username"); password = p.text("password") }
            "WIREGUARD" -> WireGuardBean().apply {
                privateKey = p.text("secretKey"); peerPublicKey = p.text("publicKey")
                peerPreSharedKey = p.text("preSharedKey"); localAddress = p.text("localAddress")
                reserved = p.text("reserved"); mtu = p.text("mtu").toIntOrNull()
            }
            "HYSTERIA2" -> Hysteria2Bean().apply {
                auth = p.text("password"); obfsPassword = p.text("obfsPassword")
                sni = p.text("sni"); allowInsecure = p.flag("insecure")
                serverPorts = p.text("portHopping").ifBlank { p.text("serverPort") }
                hopInterval = p.text("portHoppingInterval").removeSuffix("s").toLongOrNull()
                uploadMbps = p.text("bandwidthUp").toLongOrNull()
                downloadMbps = p.text("bandwidthDown").toLongOrNull()
                pinnedPeerCertificateSha256 = p.text("pinSHA256")
                obfsType = if (obfsPassword.isBlank()) "none" else "salamander"
            }
            "CUSTOM" -> {
                if (raw.isNullOrBlank()) return Profile(sub, null, "custom config has no SERVER_RAW; preserved in backup")
                val config = JsonParser.parseString(raw).asJsonObject
                if (!config.has("outbounds")) return Profile(sub, null, "unsupported custom config; preserved in backup")
                ConfigBean().apply { type = "v2ray"; content = raw }
            }
            else -> return Profile(sub, null, "unsupported ${p.text("configType")}; preserved in backup")
        }
        bean.name = p.text("remarks")
        bean.serverAddress = p.text("server")
        bean.serverPort = p.text("serverPort").toIntOrNull()
        if (bean !is ConfigBean) {
            require(bean.serverAddress.isNotBlank()) { "empty server" }
            val port = bean.serverPort
            require(port != null && port in 1..65535) { "invalid server port" }
        }
        if (bean is StandardV2RayBean) {
            bean.type = when (val network = p.text("network")) {
                "", "raw" -> "tcp"
                "kcp" -> "kcp"
                "xhttp" -> "splithttp"
                else -> network
            }
            bean.host = p.text("host"); bean.path = p.text("path")
            bean.headerType = p.text("headerType").ifBlank { "none" }; bean.mKcpSeed = p.text("seed")
            bean.quicSecurity = p.text("quicSecurity").ifBlank { "none" }; bean.quicKey = p.text("quicKey")
            bean.grpcServiceName = p.text("serviceName"); bean.grpcMultiMode = p.text("mode") == "multi"
            bean.splithttpMode = p.text("xhttpMode").ifBlank { "auto" }; bean.splithttpExtra = p.text("xhttpExtra")
            bean.security = p.text("security").ifBlank { if (bean is TrojanBean) "tls" else "none" }
            bean.sni = p.text("sni"); bean.alpn = p.text("alpn").replace(",", "\n")
            bean.utlsFingerprint = p.text("fingerPrint"); bean.allowInsecure = p.flag("insecure")
            bean.realityPublicKey = p.text("publicKey"); bean.realityShortId = p.text("shortId")
            bean.realityMldsa65Verify = p.text("mldsa65Verify"); bean.realityFingerprint = p.text("fingerPrint")
            bean.echConfigList = p.text("echConfigList"); bean.echEnabled = bean.echConfigList.isNotBlank()
            bean.pinnedPeerCertificateChainSha256 = p.text("pinnedCA256")
        }
        bean.initializeDefaultValues()
        val warning = listOf("finalMask", "authority", "echForceQuery", "spiderX").filter { p.text(it).isNotBlank() }
            .takeIf { it.isNotEmpty() }?.let { "fields only preserved in backup: ${it.joinToString()}" }
        return Profile(sub, bean, warning)
    }

    fun settings(p: JsonObject, packages: Set<String>?): Settings = Settings(
        probeUrl = p.text("pref_delay_test_url").trim().takeIf { value ->
            runCatching { URI(value).let { it.scheme in setOf("http", "https") && it.host != null } }.getOrDefault(false)
        },
        remoteDns = dns(p.text("pref_remote_dns")),
        directDns = dns(p.text("pref_domestic_dns")),
        proxyApps = if (p.has("pref_per_app_proxy")) p.flag("pref_per_app_proxy") else null,
        bypass = if (p.has("pref_bypass_apps")) p.flag("pref_bypass_apps") else null,
        packages = packages,
    )

    fun normalizedUrl(value: String): String = runCatching {
        val uri = URI(value.trim())
        val path = uri.path.replace("/refs/heads/", "/")
        val githubRaw = if (uri.host.equals("github.com", true) && "/blob/" in path) {
            "https://raw.githubusercontent.com" + path.replaceFirst("/blob/", "/")
        } else {
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}${if (uri.port == -1) "" else ":${uri.port}"}$path"
        }
        githubRaw + (uri.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(value.trim())

    private fun dns(value: String): String? {
        if (value.isBlank()) return null
        val servers = value.split(',').map { it.trim() }
        if (servers.any { it.isBlank() || it.contains(' ') || runCatching {
            val uri = URI(if ("://" in it) it else "udp://$it")
            uri.host == null || ("://" in it && uri.scheme !in setOf("https", "tls", "tcp", "udp", "quic"))
        }.getOrDefault(true) }) return null
        return servers.joinToString("\n")
    }
}
