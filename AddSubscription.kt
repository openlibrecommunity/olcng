import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import java.util.UUID

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    val updateInterval: Int? = null,
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null
)

class SubscriptionManager {
    private val subStorage = MMKV.mmkvWithID("SUB")
    private val gson = Gson()

    fun addSubscription(remarks: String, url: String, autoUpdate: Boolean = true): String {
        val guid = UUID.randomUUID().toString().replace("-", "")
        
        val subItem = SubscriptionItem(
            remarks = remarks,
            url = url,
            enabled = true,
            addedTime = System.currentTimeMillis(),
            lastUpdated = -1,
            autoUpdate = autoUpdate,
            filter = "",
            allowInsecureUrl = false,
            userAgent = ""
        )
        
        encodeSubscription(guid, subItem)
        return guid
    }

    private fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val json = gson.toJson(subItem)
        subStorage.encode(guid, json)
        
        val subsList = decodeSubsList()
        if (!subsList.contains(guid)) {
            subsList.add(guid)
            encodeSubsList(subsList)
        }
    }

    private fun decodeSubsList(): MutableList<String> {
        val json = subStorage.decodeString("SUBS_LIST") ?: return mutableListOf()
        return gson.fromJson(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
    }

    private fun encodeSubsList(subsList: List<String>) {
        subStorage.encode("SUBS_LIST", gson.toJson(subsList))
    }
}

fun main() {
    MMKV.initialize("/home/zarazaex/Projects/olcng/V2rayNG/app/src/main/assets/mmkv")
    
    val manager = SubscriptionManager()
    
    manager.addSubscription(
        remarks = "БЕЛЫЕ Z",
        url = "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt",
        autoUpdate = true
    )
    
    manager.addSubscription(
        remarks = "БЕЛЫЕ W",
        url = "https://raw.githubusercontent.com/whoahaow/rjsxrd/refs/heads/main/githubmirror/bypass/bypass-all.txt",
        autoUpdate = true
    )
    
    println("Подписки успешно добавлены")
}
