package io.nekohasekai.sagernet.bg.test

import android.net.Network
import android.os.SystemClock
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.LocalResolver
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import owenbatch.Owenbatch
import owenbatch.Session

data class ProbeProgress(
    val completed: Int,
    val total: Int,
    val groupId: Long,
    val succeeded: Int = 0,
    val failed: Int = 0,
)

data class ProbeOutcome(val progress: ProbeProgress, val cancelled: Boolean, val error: String? = null)

object BatchProbeCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> Logs.w(error) })
    private val control = Any()
    private var job: Job? = null
    private var session: Session? = null
    private val mutableProgress = MutableStateFlow<ProbeProgress?>(null)
    val progress: StateFlow<ProbeProgress?> = mutableProgress.asStateFlow()
    private val mutableTestingIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingIds: StateFlow<Set<Long>> = mutableTestingIds.asStateFlow()

    private val mutableOutcome = MutableStateFlow<ProbeOutcome?>(null)
    val outcome: StateFlow<ProbeOutcome?> = mutableOutcome.asStateFlow()

    // upstream caches its dialer and resolver process-wide, so keep this bridge alive.
    private object Resolver : LocalResolver {
        @Volatile override var underlyingNetwork: Network? = null
    }
    private var listening = false

    fun start(groupId: Long) {
        replace { runProbes(groupId, SagerDatabase.proxyDao.getByGroup(groupId)) }
    }

    fun cancel() = synchronized(control) {
        session?.cancel()
        job?.cancel()
        Unit
    }

    suspend fun testOne(profile: ProxyEntity) {
        replace { runProbes(profile.groupId, listOf(profile)) }.join()
    }

    private fun replace(block: suspend () -> Unit): Job = synchronized(control) {
        session?.cancel()
        val previous = job
        previous?.cancel()
        scope.launch {
            previous?.join()
            ensureActive()
            block()
        }.also { job = it }
    }

    private data class Result(val id: Long, val status: Int, val ping: Int, val error: String?)
    private data class Event(val id: Long, val result: Result? = null)

    private suspend fun runProbes(groupId: Long, profiles: List<ProxyEntity>) {
        mutableOutcome.value = null
        mutableProgress.value = ProbeProgress(0, profiles.size, groupId)
        var cancelled = false
        var failure: String? = null
        try {
            executeProbes(groupId, profiles)
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } catch (error: Exception) {
            failure = error.message ?: error.toString()
            Logs.w(error)
        } finally {
            mutableProgress.value?.let { mutableOutcome.value = ProbeOutcome(it, cancelled, failure) }
            mutableTestingIds.value = emptySet()
            mutableProgress.value = null
        }
    }

    private suspend fun executeProbes(groupId: Long, profiles: List<ProxyEntity>) = coroutineScope {
        if (!listening) {
            DefaultNetworkListener.start(Resolver) { Resolver.underlyingNetwork = it }
            listening = true
        }
        val protectPath = if (DataStore.tunImplementation == TunImplementation.SYSTEM &&
            DataStore.serviceMode == Key.MODE_VPN && SagerNet.started && DataStore.startedProfile > 0
        ) app.cacheDir.toString() + "/protect_path" else ""
        Resolver.underlyingNetwork = null
        withTimeoutOrNull(DataStore.connectionTestTimeout.coerceIn(100, 120000).toLong()) {
            Resolver.underlyingNetwork = DefaultNetworkListener.get()
        }
        ensureActive()
        check(Resolver.underlyingNetwork != null) { "Нет доступной сети" }
        Owenbatch.configureNetwork(Resolver, protectPath)
        val native = Owenbatch.newSession()
        synchronized(control) { session = native }
        val selected = DataStore.selectedProxy
        val ordered = profiles.sortedWith(compareBy<ProxyEntity> { it.id != selected }.thenBy { it.status != 1 })
        val queue = Channel<ProxyEntity>(64)
        val fallback = Semaphore(4)
        val link = DataStore.connectionTestURL
        val timeout = DataStore.connectionTestTimeout.coerceIn(100, 120000)
        try {
            runProbePipeline<Event>(produce = { events ->
                launch {
                    try { ordered.forEach { queue.send(it) } } finally { queue.close() }
                }
                repeat(minOf(DataStore.connectionTestConcurrency.coerceIn(1, 128), profiles.size)) {
                    launch {
                        for (profile in queue) {
                            events.send(Event(profile.id))
                            val result = try {
                                val config = buildV2RayConfig(profile, forTest = true)
                                val needsFallback = profile.type == ProxyEntity.TYPE_CONFIG ||
                                    profile.type == ProxyEntity.TYPE_BALANCER || config.requireWs || config.requireSh ||
                                    config.index.any { index -> index.isBalancer || index.chain.values.any { it.requireBean() is NaiveBean } } ||
                                    config.config.contains("\"plugin\"")
                                val ping = if (needsFallback) fallback.withPermit {
                                    V2RayTestInstance(profile, link, timeout, protectPath).use { instance ->
                                        instance.prepareBatch(config)
                                        nativeCall(native) { instance.probeBatch(native) }
                                    }
                                } else nativeCall(native) { native.probe(config.config, link, timeout) }
                                Result(profile.id, 1, ping, null)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                ensureActive()
                                Result(profile.id, if (error is PluginManager.PluginNotFoundException) -1 else 3, 0, error.message)
                            }
                            events.trySend(Event(profile.id, result)).getOrThrow()
                        }
                    }
                }
            }, collect = { events -> collectResults(groupId, profiles.size, events) })
        } finally {
            native.cancel()
            synchronized(control) { if (session === native) session = null }
        }
    }

    private suspend fun nativeCall(native: Session, call: () -> Int): Int {
        val task = scope.async { call() }
        try {
            return task.await()
        } finally {
            // cancel Go before waiting for JNI, and don't close fallback resources early.
            if (!currentCoroutineContext().isActive) native.cancel()
            withContext(NonCancellable) { task.join() }
        }
    }

    private suspend fun collectResults(groupId: Long, total: Int, events: ReceiveChannel<Event>) {
        val active = mutableSetOf<Long>()
        val pending = ArrayList<Result>(32)
        var completed = 0
        var succeeded = 0
        var failed = 0
        var lastFlush = SystemClock.elapsedRealtime()
        fun flush() {
            if (pending.isNotEmpty()) {
                SagerDatabase.database.runInTransaction {
                    pending.forEach { SagerDatabase.proxyDao.updateTestResult(it.id, it.status, it.ping, it.error) }
                }
                pending.clear()
            }
            mutableTestingIds.value = active.toSet()
            mutableProgress.value = ProbeProgress(completed, total, groupId, succeeded, failed)
            lastFlush = SystemClock.elapsedRealtime()
        }
        try {
            while (true) {
                val wait = (200 - (SystemClock.elapsedRealtime() - lastFlush)).coerceAtLeast(1)
                val event = receiveProbeEvent(events, wait)
                if (event != null && event.isClosed) break
                event?.getOrNull()?.let {
                    if (it.result == null) active.add(it.id) else {
                        active.remove(it.id)
                        pending.add(it.result)
                        completed++
                        if (it.result.status == 1) succeeded++ else failed++
                    }
                }
                if (pending.size >= 32 || SystemClock.elapsedRealtime() - lastFlush >= 200) flush()
            }
        } finally {
            // only completed results are persisted; cancelled probes keep their old state.
            flush()
            Logs.i("probe batch: $completed/$total, succeeded=$succeeded, failed=$failed")
        }
    }
}
