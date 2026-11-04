package io.nekohasekai.sagernet.group

internal object BuiltinPoolSources {
    val urls: Map<String, String> = linkedMapOf(
        "igareck-cidr" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/WHITE-CIDR-RU-all.txt",
        "igareck-sni" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/WHITE-SNI-RU-all.txt",
        "igareck-black-mixed" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_SS%2BAll_RUS.txt",
        "igareck-black-vless" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt",
        "igareck-black-mobile" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
        "all-subs" to "https://raw.githubusercontent.com/solovyov-jenya2004/all_subs/refs/heads/main/final_sorted",
        "zieng2" to "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "rkp-white" to "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt",
        "rkp-black" to "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/blacklist.txt",
    ) + (1..9).associate { index ->
        "bypass-$index" to "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-$index.txt"
    }
}
