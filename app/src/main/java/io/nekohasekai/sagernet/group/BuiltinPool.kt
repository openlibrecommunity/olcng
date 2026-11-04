package io.nekohasekai.sagernet.group

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ProfileFingerprint
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import libexclavecore.Libexclavecore
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

data class PoolRefreshState(
    val isRefreshing: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 18,
    val lastError: String? = null,
)

object BuiltinPool {
    private const val MIN_INTERVAL = 15 * 60 * 1000L
    private const val MAX_CONTENT = 32 * 1024 * 1024
    private const val WORK_NAME = "builtin-pool-refresh"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PoolRefreshState())
    val state: StateFlow<PoolRefreshState> = mutableState.asStateFlow()

    private suspend fun <T> withPoolLock(block: suspend () -> T): T = mutex.withLock {
        RandomAccessFile(File(app.filesDir, "builtin-pool.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    suspend fun initialize(): Long = withContext(Dispatchers.IO) {
        withPoolLock { initializeLocked() }
    }

    private suspend fun initializeLocked(): Long {
        val db = SagerDatabase.database
        val existing = db.poolDao().sources().associateBy { it.id }
        val seeds = if (BuiltinPoolSources.urls.keys.any { existing[it]?.content == null }) {
            loadSeeds()
        } else emptyMap()
        val sources = BuiltinPoolSources.urls.map { (id, url) ->
            val old = existing[id]?.takeIf { it.url == url } ?: PoolSource(id, url)
            if (old.content == null) seeds[id]?.let { old.copy(content = it) } ?: old else old
        }
        val groupId = db.withTransaction {
            val registry = db.poolDao().registry()
            val group = registry?.let { db.groupDao().getById(it.groupId) }
            val id = if (group == null) {
                db.groupDao().createGroup(ProxyGroup(
                    name = "Общий список", type = GroupType.BASIC, order = GroupOrder.BY_DELAY,
                    userOrder = db.groupDao().nextOrder() ?: 1L,
                ))
            } else {
                if (group.name != "Общий список" || group.type != GroupType.BASIC || group.order != GroupOrder.BY_DELAY) {
                    group.name = "Общий список"
                    group.type = GroupType.BASIC
                    group.order = GroupOrder.BY_DELAY
                    group.subscription = null
                    db.groupDao().updateGroup(group)
                }
                group.id
            }
            db.poolDao().putRegistry(PoolRegistry(groupId = id, lastAttempt = registry?.lastAttempt ?: 0L))
            db.poolDao().putSources(sources)
            merge(id, sources.associate { it.id to parseSnapshot(it.content) })
            id
        }
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "$WORK_NAME-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(BuiltinPoolWorker::class.java, 30, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
        mutableState.update { state ->
            if (state.isRefreshing) state else state.copy(
                completedSources = sources.count { it.lastSuccess > 0 },
                lastError = sources.mapNotNull { it.lastError }.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        }
        return groupId
    }

    fun isPool(groupId: Long): Boolean = groupId > 0 &&
        SagerDatabase.poolDao.registry()?.groupId == groupId

    fun requestRefresh(reason: String = "manual", force: Boolean = false) {
        // no CONNECTED constraint: workmanager may require validated internet on android
        val request = OneTimeWorkRequest.Builder(BuiltinPoolWorker::class.java)
            .setInputData(workDataOf("reason" to reason, "force" to force))
            .setInitialDelay(if (force) 0 else 3, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val name = if (reason == "connectivity" || reason == "vpn-connected") "$WORK_NAME-network" else WORK_NAME
        RemoteWorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    fun onVpnConnected() = requestRefresh("vpn-connected", force = true)
    fun onConnectivityChanged() = requestRefresh("connectivity")

    suspend fun refreshNow() = refresh(force = true)

    internal suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        withPoolLock {
            val groupId = initializeLocked()
            val db = SagerDatabase.database
            val registry = requireNotNull(db.poolDao().registry())
            val now = System.currentTimeMillis()
            val sourcesHealthy = db.poolDao().sources().all { it.lastSuccess > 0 && it.lastError == null }
            if (!force && sourcesHealthy && now >= registry.lastAttempt && now - registry.lastAttempt < MIN_INTERVAL) return@withPoolLock
            db.poolDao().putRegistry(registry.copy(lastAttempt = now))
            mutableState.value = PoolRefreshState(isRefreshing = true)
            try {
                val semaphore = Semaphore(4)
                val results = coroutineScope {
                    val sources = db.poolDao().sources().associateBy { it.id }
                    BuiltinPoolSources.urls.keys.map { id -> async {
                        semaphore.withPermit {
                            try {
                                fetch(requireNotNull(sources[id]))
                            } finally {
                                mutableState.update { it.copy(completedSources = it.completedSources + 1) }
                            }
                        }
                    } }.awaitAll()
                }
                db.withTransaction {
                    db.poolDao().putSources(results.map { it.first })
                    merge(groupId, results.associate { it.first.id to it.second })
                }
                val errors = results.mapNotNull { (source, _) -> source.lastError?.let { "${source.id}: $it" } }
                mutableState.update { it.copy(lastError = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
                GroupManager.postReload(groupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
                throw e
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun loadSeeds(): Map<String, String> {
        return try {
            val json = app.assets.open("builtin-pool/sources.json").bufferedReader().use { it.readText() }
            JsonParser.parseString(json).asJsonArray.mapNotNull { entry ->
                runCatching {
                    val item = entry.asJsonObject
                    val id = item["id"].asString
                    val content = item["content"].asString
                    if (BuiltinPoolSources.urls[id] == item["url"].asString && parseSnapshot(content).isNotEmpty()) {
                        id to content
                    } else null
                }.getOrNull()
            }.toMap()
        } catch (e: Exception) {
            mutableState.update { it.copy(lastError = "offline seed: ${e.message}") }
            emptyMap()
        }
    }

    private fun parseSnapshot(content: String?): List<AbstractBean> {
        if (content.isNullOrBlank()) return emptyList()
        val start = content.trimStart().take(512).lowercase()
        require(!start.startsWith("<") && "<html" !in start) { "HTML response" }
        return RawUpdater.parseRaw(content).orEmpty().onEach { it.initializeDefaultValues() }
    }

    private data class Download(val content: String?, val etag: String?, val modified: String?)

    private fun direct(source: PoolSource): Download {
        val url = URL(source.url)
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 23) connectivity.activeNetwork?.let { add(it) }
            addAll(connectivity.allNetworks)
        }
        val network = candidates.firstOrNull { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        val connection = when {
            network == null -> url.openConnection(Proxy.NO_PROXY)
            Build.VERSION.SDK_INT >= 23 -> network.openConnection(url, Proxy.NO_PROXY)
            else -> network.openConnection(url)
        } as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            source.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            source.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val status = connection.responseCode
            if (status == 304 && source.content != null) return Download(null, source.etag, source.lastModified)
            check(status == 200) { "HTTP $status" }
            val bytes = connection.inputStream.use { it.readBytesBounded() }
            return Download(bytes.toString(Charsets.UTF_8), connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_CONTENT) { "source too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun viaVpn(source: PoolSource): Download {
        val client = Libexclavecore.newHttpClient()
        try {
            client.useUDS(app.cacheDir.toString() + "/ipc.sock")
            // native HTTP API accepts only 200, so conditional requests stay on the direct path
            val response = client.newRequest().apply { setURL(source.url) }.execute()
            val content = response.contentString
            check(content.length <= MAX_CONTENT) { "source too large" }
            return Download(content, response.getHeader("ETag").takeIf { it.isNotEmpty() },
                response.getHeader("Last-Modified").takeIf { it.isNotEmpty() })
        } finally {
            client.close()
        }
    }

    private fun fetch(source: PoolSource): Pair<PoolSource, List<AbstractBean>> {
        fun accept(download: Download): Pair<PoolSource, List<AbstractBean>> {
            val content = download.content ?: requireNotNull(source.content)
            val beans = parseSnapshot(content)
            check(beans.isNotEmpty()) { "empty or invalid subscription" }
            return source.copy(content = content, etag = download.etag, lastModified = download.modified,
                lastSuccess = System.currentTimeMillis(), lastError = null) to beans
        }
        return try {
            try {
                accept(direct(source))
            } catch (e: Exception) {
                if (!(SagerNet.started && DataStore.startedProfile > 0)) throw e
                accept(viaVpn(source))
            }
        } catch (e: Exception) {
            source.copy(lastError = (e.message ?: e.javaClass.simpleName).take(240)) to parseSnapshot(source.content)
        }
    }

    // caller holds the room transaction; retained entities are never rewritten
    private fun merge(groupId: Long, snapshots: Map<String, List<AbstractBean>>) {
        val db = SagerDatabase.database
        val beans = linkedMapOf<String, AbstractBean>()
        val fingerprints = snapshots.mapValues { (_, sourceBeans) ->
            sourceBeans.mapTo(linkedSetOf()) { bean ->
                ProfileFingerprint.of(bean).also { beans.putIfAbsent(it, bean) }
            }
        }
        val existing = db.proxyDao().getByGroup(groupId)
        val plan = BuiltinPoolMerge.plan(existing.map { it.id to ProfileFingerprint.of(it.requireBean()) }, fingerprints)
        val ids = plan.retained.toMutableMap()
        var order = (existing.maxOfOrNull { it.userOrder } ?: 0L) + 1
        plan.added.forEach { fingerprint ->
            ids[fingerprint] = db.proxyDao().addProxy(ProxyEntity(groupId = groupId, userOrder = order++)
                .apply { putBean(requireNotNull(beans[fingerprint])) })
        }
        val protected = setOf(DataStore.selectedProxy, DataStore.startedProfile)
        db.proxyDao().deleteProxy(existing.filter { it.id in plan.deleted && it.id !in protected })
        db.poolDao().clearMemberships()
        db.poolDao().putMemberships(plan.owners.flatMap { (fingerprint, owners) ->
            owners.map { PoolMembership(it, fingerprint, requireNotNull(ids[fingerprint])) }
        })
    }
}
