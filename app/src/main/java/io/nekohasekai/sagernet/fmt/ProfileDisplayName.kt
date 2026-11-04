package io.nekohasekai.sagernet.fmt

import java.util.Locale

object ProfileDisplayName {
    private val flagCodes = Locale.getISOCountries().toSet() + setOf("EU", "UN", "XK")
    private val flags = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
    private val hosts = Regex("\\S*[./@]\\S*")
    private val codes = Regex("(?<![\\p{L}\\p{N}_])([A-Z]{2})(?![\\p{L}_]|\\s*:)")
    private val titleCodes = (
        "AE AL AM AT AU AZ BE BG BM BR BY CA CH CN CY CZ DE DK EE EG ES EU FI FR GB GE GR " +
            "HK HU ID IE IL IN IR IT JP KE KG KR KZ LT LV MD MK MX MY NL NO OM PE PH PL PT " +
            "QA RO RS RU SA SC SE SG SI TH TR TW UA UN US UZ VN ZA"
        ).split(' ').toSet()

    // country words present in the bundled subscriptions
    private val countries = """
        AE=United Arab Emirates
        AL=Албания
        AM=Армения
        AT=Австрия
        AU=Австралия
        AZ=Азербайджан
        BG=Bulgaria|Болгария
        BY=Belarus|Беларусь
        CA=Canada|Канада
        CH=Switzerland|Швейцария
        CN=China
        CY=Кипр
        CZ=Чехия
        DE=Germany|Германия
        EE=Эстония
        ES=Испания
        FI=Finland|Финляндия
        FR=France|Франция
        GB=United Kingdom|Великобритания
        GR=Греция
        HK=Hong Kong|HongKong|Гонконг
        HU=Hungary
        IE=Ирландия
        IL=Израиль
        IN=Индия
        IR=Иран
        IT=Италия
        JP=Japan|Япония
        KE=Кения
        KR=South Korea
        KZ=Казахстан
        LT=Lithuania|Литва
        LV=Латвия
        MD=Молдова
        MK=Македония
        MY=Малайзия
        NL=Netherlands|Нидерланды
        NO=Норвегия
        PL=Poland|Польша
        QA=Катар
        RO=Romania|Румыния
        RS=Serbia|Сербия
        RU=Russia|Россия
        SC=Сейшельские о-ва
        SE=Sweden|Швеция
        SG=Singapore|Сингапур
        SI=Словения
        TH=Таиланд
        TR=Турция
        UA=Украина
        US=United States|США
        UZ=Uzbekistan
        ZA=ЮАР
    """.trimIndent().lines().flatMap { line ->
        val (code, names) = line.split('=')
        names.split('|').map { it.lowercase() to code }
    }.toMap()
    private val words = Regex(
        "(?<![\\p{L}\\p{N}_])(?:" +
            countries.keys.joinToString("|") { Regex.escape(it) } +
            ")(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    fun anonymous(originalName: String, profileId: Long): String {
        val name = originalName.replace("\uFE0E", "").replace("\uFE0F", "")
        for (match in flags.findAll(name)) {
            val code = buildString {
                append(('A'.code + match.value.codePointAt(0) - 0x1F1E6).toChar())
                append(('A'.code + match.value.codePointAt(2) - 0x1F1E6).toChar())
            }
            if (code in flagCodes) return "${flag(code)} $profileId"
        }
        val title = hosts.replace(name, " ")
        val code = words.find(title)?.value?.lowercase()?.let(countries::get)
            ?: codes.findAll(title).map { it.groupValues[1] }.firstOrNull { it in titleCodes }
        return "${code?.let(::flag) ?: "🌐"} $profileId"
    }

    private fun flag(code: String): String = buildString {
        code.forEach { appendCodePoint(0x1F1E6 + it.code - 'A'.code) }
    }
}
