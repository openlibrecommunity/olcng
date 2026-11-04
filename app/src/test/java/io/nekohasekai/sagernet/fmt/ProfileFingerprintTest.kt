package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.fmt.v2ray.VLESSBean
import org.junit.Assert.*
import org.junit.Test

class ProfileFingerprintTest {
    private fun vless() = VLESSBean().apply {
        initializeDefaultValues()
        serverAddress = "example.org"
        serverPort = 443
        uuid = "11111111-1111-4111-8111-111111111111"
        security = "tls"
        sni = "example.org"
    }

    @Test
    fun ignoresNamesAndSourceMetadata() {
        val a = vless().apply { name = "one"; profileId = "remote-a"; extraType = 1 }
        val b = vless().apply { name = "two"; profileId = "remote-b"; extraType = 0 }
        assertEquals(ProfileFingerprint.of(a), ProfileFingerprint.of(b))
        assertEquals("one", a.name)
        assertEquals("remote-a", a.profileId)
        assertEquals(1, a.extraType)
    }

    @Test
    fun defaultsAreNormalizedWithoutMutatingOriginal() {
        val a = VLESSBean()
        val b = VLESSBean().apply { initializeDefaultValues() }
        assertEquals(ProfileFingerprint.of(a), ProfileFingerprint.of(b))
        assertNull(a.serverAddress)
        assertNull(a.serverPort)
        assertNull(a.name)
        assertNull(a.encryption)
    }

    @Test
    fun fullSettingsMatterAtSameEndpoint() {
        val baseline = ProfileFingerprint.of(vless())
        val variants = listOf(
            vless().apply { uuid = "22222222-2222-4222-8222-222222222222" },
            vless().apply { sni = "other.org" },
            vless().apply { realityPublicKey = "different-key" },
            vless().apply { type = "ws"; path = "/other" },
            vless().apply { allowInsecure = true },
            vless().apply { flow = "xtls-rprx-vision" },
        )
        variants.forEach { assertNotEquals(baseline, ProfileFingerprint.of(it)) }
    }

    @Test
    fun canonicalConfigSortsObjectsButPreservesArrayOrder() {
        fun config(content: String) = ConfigBean().apply { this.content = content }
        val a = config("""{"b":2,"a":{"d":4,"c":3},"x":[1,2]}""")
        val b = config("""{ "x": [1, 2], "a": {"c": 3, "d": 4}, "b": 2 }""")
        assertEquals(ProfileFingerprint.of(a), ProfileFingerprint.of(b))
        assertNotEquals(ProfileFingerprint.of(a), ProfileFingerprint.of(config("""{"b":2,"a":{"d":4,"c":3},"x":[2,1]}""")))
        assertNotEquals(ProfileFingerprint.of(a), ProfileFingerprint.of(config("""{"b":9,"a":{"d":4,"c":3},"x":[1,2]}""")))
    }

    @Test
    fun classIsPartOfFingerprint() {
        val config = ConfigBean().apply { initializeDefaultValues() }
        assertNotEquals(ProfileFingerprint.of(config), ProfileFingerprint.of(VLESSBean()))
        assertTrue(ProfileFingerprint.of(config).matches(Regex("[0-9a-f]{64}")))
    }
}
