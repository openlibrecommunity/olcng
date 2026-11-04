package io.nekohasekai.sagernet.migration

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ProfileFingerprint
import io.nekohasekai.sagernet.group.BuiltinPool
import io.nekohasekai.sagernet.group.BuiltinPoolSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** originals are never opened by mmkv, even on versions without read-only mode. */
object LegacyOlcngMigration {
    data class MigrationResult(
        val success: Boolean,
        val alreadyMigrated: Boolean = false,
        val importedProfiles: Int = 0,
        val importedSubscriptions: Int = 0,
        val backupPath: String? = null,
        val warnings: List<String> = emptyList(),
        val error: String? = null,
        val databaseCommitted: Boolean = false,
    )

    private val mutex = Mutex()
    private val ids = listOf("MAIN", "PROFILE_FULL_CONFIG", "SERVER_RAW", "SERVER_AFF", "SUB", "SETTING")
    private const val PREFS = "olcng-migration-v1"
    private const val DONE = "completed"

    fun hasLegacyData(context: Context): Boolean = ids.any {
        File(context.filesDir, "mmkv/$it").let { file -> file.isFile && file.length() > 0 }
    }

    fun shouldShowIntro(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("intro-seen", false)

    fun markIntroSeen(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("intro-seen", true).commit())
    }

    suspend fun migrate(context: Context): MigrationResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            // serialize different android processes as well as coroutines.
            RandomAccessFile(File(context.filesDir, "olcng-migration.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use { migrateLocked(context.applicationContext) }
            }
        }
    }

    private suspend fun migrateLocked(context: Context): MigrationResult {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(DONE, false)) return MigrationResult(
            true, alreadyMigrated = true, backupPath = preferences.getString("backup", null), databaseCommitted = true,
        )
        if (!hasLegacyData(context)) return MigrationResult(true)
        var backup: File? = null
        var work: File? = null
        var committed = false
        val warnings = mutableListOf<String>()
        val opened = mutableListOf<MMKV>()
        try {
            val root = File(context.filesDir, "olcng-migration-backups/${UUID.randomUUID()}")
            backup = File(root, "original-mmkv").apply { check(mkdirs()) }
            work = File(root, "working-mmkv").apply { check(mkdirs()) }
            val original = File(context.filesDir, "mmkv")
            val sourceFiles = ids.flatMap { listOf(File(original, it), File(original, "$it.crc")) }.filter { it.isFile }
            val stamps = sourceFiles.associateWith { it.length() to it.lastModified() }
            sourceFiles.forEach { source ->
                val target = File(backup, source.name)
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                }
                target.copyTo(File(work, source.name))
            }
            check(stamps.all { (file, stamp) -> stamp == (file.length() to file.lastModified()) }) {
                "legacy files changed while copying; stop the old VPN service and retry"
            }
            MMKV.initialize(context)
            val readOnly = runCatching { MMKV::class.java.getField("READ_ONLY_MODE").getInt(null) }.getOrDefault(0)
            val stores = ids.filter { File(work, it).isFile }.associateWith { id ->
                check(File(work, "$id.crc").isFile) { "missing $id.crc; backup preserved" }
                check(MMKV.isFileValid(id, work.absolutePath)) { "invalid $id checksum; backup preserved" }
                val store = checkNotNull(MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE or readOnly, null, work.absolutePath))
                opened.add(store)
                store
            }
            fun strings(id: String): Map<String, String> = stores[id]?.let { store ->
                store.allKeys().orEmpty().associateWith { key ->
                    checkNotNull(store.decodeString(key)) { "unreadable $id entry $key" }
                }
            }.orEmpty()
            val main = stores["MAIN"]
            val selectedGuid = main?.decodeString("SELECTED_SERVER")
            val profiles = strings("PROFILE_FULL_CONFIG")
            val raw = strings("SERVER_RAW")
            val subscriptions = strings("SUB").mapValues { (_, json) -> LegacyOlcngFormats.subscription(json) }
            val parsed = profiles.mapValues { (guid, json) -> LegacyOlcngFormats.profile(json, raw[guid]) }
            val listed = main?.allKeys().orEmpty().filter { it == "ANG_CONFIGS" || it.startsWith("SUB_SERVERS_") }
                .flatMap { key ->
                    val json = checkNotNull(main?.decodeString(key)) { "unreadable MAIN/$key" }
                    JsonParser.parseString(json).asJsonArray.map { it.asString }
                }.toSet()
            check((listed - profiles.keys).isEmpty()) {
                "MAIN references ${ (listed - profiles.keys).size } missing profiles; backup preserved, retry or manual recovery required"
            }
            // orphan raw configurations are retained on disk, not silently discarded.
            (raw.keys - profiles.keys).takeIf { it.isNotEmpty() }?.let {
                warnings.add("${it.size} orphan SERVER_RAW entries preserved in backup")
            }
            parsed.forEach { (guid, profile) -> profile.warning?.let { warnings.add("$guid: $it") } }
            val settingsJson = JsonObject()
            val settingsStore = stores["SETTING"]
            listOf("pref_remote_dns", "pref_domestic_dns", "pref_delay_test_url").forEach { key ->
                settingsStore?.decodeString(key)?.let { settingsJson.addProperty(key, it) }
            }
            listOf("pref_per_app_proxy", "pref_bypass_apps").forEach { key ->
                if (settingsStore?.containsKey(key) == true) settingsJson.addProperty(key, settingsStore.decodeBool(key))
            }
            val settings = LegacyOlcngFormats.settings(settingsJson, settingsStore?.decodeStringSet("pref_per_app_proxy_set"))
            listOf("pref_remote_dns" to settings.remoteDns, "pref_domestic_dns" to settings.directDns).forEach { (key, value) ->
                if (settingsJson.text(key).isNotBlank() && value == null) warnings.add("$key unsupported; preserved in backup")
            }
            val poolId = BuiltinPool.initialize()
            val db = SagerDatabase.database
            var imported = 0
            var importedSubs = 0
            var selectedId: Long? = null
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                // a private ledger survives a crash between room commit and preference writes.
                sql.execSQL("CREATE TABLE IF NOT EXISTS olcng_migration_v1 (key TEXT NOT NULL PRIMARY KEY, value INTEGER NOT NULL)")
                fun remembered(key: String): Long? = sql.query(
                    "SELECT value FROM olcng_migration_v1 WHERE key = ?", arrayOf(key)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                fun remember(key: String, value: Long) = sql.execSQL(
                    "INSERT OR REPLACE INTO olcng_migration_v1 (key, value) VALUES (?, ?)", arrayOf<Any>(key, value)
                )
                fun group(key: String, name: String, subscription: LegacyOlcngFormats.Subscription? = null): Long {
                    remembered(key)?.takeIf { db.groupDao().getById(it) != null }?.let { return it }
                    val id = db.groupDao().createGroup(ProxyGroup(
                        name = name, userOrder = db.groupDao().nextOrder() ?: 1L,
                        type = if (subscription == null) GroupType.BASIC else GroupType.SUBSCRIPTION,
                        subscription = subscription?.bean,
                    ))
                    remember(key, id)
                    if (subscription != null) importedSubs++
                    return id
                }
                val builtinUrls = (BuiltinPoolSources.urls.values + listOf(
                    "https://raw.githubusercontent.com/whoahaow/rjsxrd/main/githubmirror/bypass/bypass-all.txt",
                    "https://key.zarazaex.xyz/sub",
                    "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                )).mapTo(hashSetOf(), LegacyOlcngFormats::normalizedUrl)
                val builtinIds = subscriptions.filterValues {
                    LegacyOlcngFormats.normalizedUrl(it.bean.link) in builtinUrls
                }.keys
                val groups = subscriptions.filterKeys { it !in builtinIds }.mapValues { (guid, sub) ->
                    if (!sub.enabled) warnings.add("${sub.name}: disabled subscription imported without automatic updates")
                    group("sub:$guid", sub.name.ifBlank { "Профили OLCNG" }, sub.takeIf { it.bean.link.isNotBlank() })
                }
                val pool = db.proxyDao().getByGroup(poolId).associateBy { ProfileFingerprint.of(it.requireBean()) }
                val existing = db.proxyDao().getAll().associateBy { it.uuid }
                parsed.forEach { (guid, profile) ->
                    val bean = profile.bean ?: return@forEach
                    val key = LegacyOlcngFormats.stableKey("profile", guid)
                    val old = remembered(key)?.let { db.proxyDao().getById(it) } ?: existing[key]
                    val matched = if (profile.subscriptionId in builtinIds) pool[ProfileFingerprint.of(bean)] else null
                    val entityId = old?.id ?: matched?.id ?: run {
                        val subId = profile.subscriptionId.ifBlank { "__default_subscription__" }
                        val groupId = if (profile.subscriptionId in builtinIds) {
                            group("legacy-builtin-archive", "Архив OLCNG")
                        } else groups[subId] ?: group("personal", "Личные профили OLCNG")
                        val entity = ProxyEntity(groupId = groupId, uuid = key, userOrder = imported.toLong() + 1).putBean(bean)
                        db.proxyDao().addProxy(entity).also { imported++ }
                    }
                    remember(key, entityId)
                    if (guid == selectedGuid) selectedId = entityId
                }
            }
            committed = true
            // configurationStore is a separate database and cannot join the profile transaction.
            settings.probeUrl?.let { DataStore.connectionTestURL = it }
            settings.remoteDns?.let { DataStore.remoteDns = it }
            settings.directDns?.let { DataStore.directDns = it }
            settings.proxyApps?.let { DataStore.proxyApps = it && !settings.packages.isNullOrEmpty() }
            settings.bypass?.let { DataStore.bypass = it }
            settings.packages?.let { DataStore.individual = it.sorted().joinToString("\n") }
            selectedId?.let {
                DataStore.selectedProxy = it
                db.proxyDao().getById(it)?.let { profile -> DataStore.selectedGroup = profile.groupId }
            }
            if (!selectedGuid.isNullOrBlank() && selectedId == null) warnings.add("selected profile is unavailable; original GUID preserved in backup")
            warnings.add("Original settings, affiliations, favorites and unsupported fields are retained in the private MMKV backup")
            if (!preferences.edit().putBoolean(DONE, true).putString("backup", backup.absolutePath).commit()) {
                preferences.edit().remove(DONE).commit()
                error("cannot save completion marker; retry is safe")
            }
            return MigrationResult(true, importedProfiles = imported, importedSubscriptions = importedSubs,
                backupPath = backup.absolutePath, warnings = warnings, databaseCommitted = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            return MigrationResult(false, backupPath = backup?.absolutePath, warnings = warnings,
                error = "MMKV native library unavailable: ${e.message}", databaseCommitted = committed)
        } catch (e: Exception) {
            return MigrationResult(false, backupPath = backup?.absolutePath, warnings = warnings,
                error = "${e.javaClass.simpleName}: ${e.message}", databaseCommitted = committed)
        } finally {
            opened.forEach { runCatching { it.close() } }
            work?.deleteRecursively()
        }
    }
}
