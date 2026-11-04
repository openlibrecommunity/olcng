package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDisplayNameTest {
    @Test
    fun subscriptionFlagsWinOverOtherCountryHints() {
        val names = mapOf(
            "🇧🇬 Bulgaria | 🌐 [*CIDR] CDN" to "🇧🇬",
            "AetrisVPN White list ┃ ✅ SNI: yandex.ru ┃ 🇩🇪 Германия" to "🇩🇪",
            "🌀 CYBERPORTAL | CIDR White Checked RU | 🇺🇸 США #20 t.me/rjsxrd" to "🇺🇸",
            "🇨🇳 HongKong" to "🇨🇳",
            "🇳🇱 The Netherlands (сервер) | 🇳🇱 The Netherlands (выход 193.29.139.195) | @RaViraNet" to "🇳🇱",
            "LTE/5G Все операторы 🇪🇺" to "🇪🇺",
        )
        names.forEach { (name, flag) ->
            assertEquals(name, "$flag 42", ProfileDisplayName.anonymous(name, 42))
        }
    }

    @Test
    fun skipsJunkFlagsAndVariationSelectors() {
        assertEquals("🇫🇮 7", ProfileDisplayName.anonymous("🏳️ 🇿🇿 🇫\uFE0F🇮 Finland", 7))
        assertEquals("🇺🇳 7", ProfileDisplayName.anonymous("🏴 🇺🇳 Germany", 7))
        assertEquals("🇩🇪 7", ProfileDisplayName.anonymous("🏳️ Germany", 7))
        assertEquals("🌐 7", ProfileDisplayName.anonymous("🇿🇿 unknown", 7))
    }

    @Test
    fun recognizesClearCountryWordsAndCodes() {
        val names = mapOf(
            "🌐 [wLTE] Сейшельские о-ва 7" to "🇸🇨",
            "7 | REALITY | SNI: www.cloudflare.com | IN: LV OUT: LV | RATING: 9/10" to "🇱🇻",
            "[2448ms US US  @vlesstrojan] t.me/rjsxrd" to "🇺🇸",
            "[388ms BG BG  @vlesstrojan] t.me/rjsxrd" to "🇧🇬",
            "HK7 28.4KB/s" to "🇭🇰",
            "HK 13.4KB/s" to "🇭🇰",
            "🌀 CYBERPORTAL | SNI White All RU #12 t.me/rjsxrd" to "🇷🇺",
            "The Netherlands [*CIDR]" to "🇳🇱",
            "United States, Chicago" to "🇺🇸",
            "Германия 71 Michael Sebastian Schinzel" to "🇩🇪",
            "Финляндия" to "🇫🇮",
        )
        names.forEach { (name, flag) ->
            assertEquals(name, "$flag 19", ProfileDisplayName.anonymous(name, 19))
        }
    }

    @Test
    fun doesNotGuessFromUnknownTitlesOrHostnames() {
        listOf(
            "RUS", "RUS VPN", "trust us", "de15.h1cloud.net", "US.example.org",
            "Russia.example.org", "https://example.org/US", "@US", "IN: unknown",
            "[OpenRay] 🌐XX-221", "🌐 [wLTE] Global 6", "未知154 15.7MB/s",
            "0021950 t.me/rjsxrd", "⚠️Подписка не работает!", "",
        ).forEach { name ->
            assertEquals(name, "🌐 23", ProfileDisplayName.anonymous(name, 23))
        }
    }

    @Test
    fun usesExactPersistentIdWithoutRenumberingOrHashing() {
        assertEquals("🇵🇱 ${Long.MAX_VALUE}", ProfileDisplayName.anonymous("🇵🇱 Poland — #35", Long.MAX_VALUE))
        assertEquals("🇵🇱 0", ProfileDisplayName.anonymous("🇵🇱 different title", 0))
        assertEquals("🌐 ${Long.MIN_VALUE}", ProfileDisplayName.anonymous("unknown", Long.MIN_VALUE))
    }
}
