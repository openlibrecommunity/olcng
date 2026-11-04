package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import java.security.MessageDigest

object ProfileFingerprint {
    private val gson = Gson()

    fun of(bean: AbstractBean): String {
        // cloning through kryo requires initialized fields and can lose nonserialized settings
        val copy = gson.fromJson(gson.toJsonTree(bean), bean.javaClass)
        copy.initializeDefaultValues()
        val config = gson.toJsonTree(copy).asJsonObject.apply {
            remove("name")
            remove("profileId")
            remove("extraType")
        }
        if (copy is ConfigBean) {
            runCatching { JsonParser.parseString(copy.content) }.getOrNull()?.let {
                config.add("content", it)
            }
        }
        val canonical = bean.javaClass.name + "\n" + canonicalize(config)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonicalize(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply {
            value.asJsonObject.keySet().sorted().forEach { key ->
                add(key, canonicalize(value.asJsonObject[key]))
            }
        }
        value.isJsonArray -> JsonArray().apply {
            value.asJsonArray.forEach { add(canonicalize(it)) }
        }
        else -> value
    }
}
