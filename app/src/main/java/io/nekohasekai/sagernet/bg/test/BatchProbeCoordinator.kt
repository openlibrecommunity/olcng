package io.nekohasekai.sagernet.bg.test

import android.os.SystemClock
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val mutableProgress = MutableStateFlow<ProbeProgress?>(null)
    val progress: StateFlow<ProbeProgress?> = mutableProgress.asStateFlow()
    private val mutableTestingIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingIds: StateFlow<Set<Long>> = mutableTestingIds.asStateFlow()

    private val mutableOutcome = MutableStateFlow<ProbeOutcome?>(null)
    val outcome: StateFlow<ProbeOutcome?> = mutableOutcome.asStateFlow()

    fun start(groupId: Long) {
        replace { runProbes(groupId, SagerDatabase.proxyDao.getByGroup(groupId)) }
    }

    fun cancel() = synchronized(control) {
        job?.cancel()
        Unit
    }

    suspend fun testOne(profile: ProxyEntity) {
        replace { runProbes(profile.groupId, listOf(profile)) }.join()
    }

    private fun replace(block: suspend () -> Unit): Job = synchronized(control) {
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
        val protectPath = if (DataStore.tunImplementation == TunImplementation.SYSTEM &&
            DataStore.serviceMode == Key.MODE_VPN && SagerNet.started && DataStore.startedProfile > 0
        ) app.cacheDir.toString() + "/protect_path" else ""
        val selected = DataStore.selectedProxy
        Logs.i("probe batch group=$groupId, selected=$selected, total=${profiles.size}")
        val ordered = profiles.sortedWith(compareBy<ProxyEntity> { it.id != selected }.thenBy { it.status != 1 })
        val queue = Channel<ProxyEntity>(64)
        val link = DataStore.connectionTestURL
        val timeout = DataStore.connectionTestTimeout.coerceIn(100, 120000)
        runProbePipeline<Event>(produce = { events ->
            launch {
                try { ordered.forEach { queue.send(it) } } finally { queue.close() }
            }
            repeat(minOf(DataStore.connectionTestConcurrency.coerceIn(1, 20), profiles.size)) {
                launch {
                    for (profile in queue) {
                        events.send(Event(profile.id))
                        val result = try {
                            val ping = V2RayTestInstance(profile, link, timeout, protectPath).use { instance ->
                                withTimeout(30_000L) { instance.doTest() }
                            }
                            if (profile.id == selected || profiles.size == 1) Logs.i("probe profile=${profile.id}, ping=${ping}ms")
                            Result(profile.id, 1, ping, null)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            ensureActive()
                            Logs.w("probe profile=${profile.id}, type=${profile.type}: ${error.javaClass.simpleName}: ${error.message}")
                            Result(profile.id, if (error is PluginManager.PluginNotFoundException) -1 else 3, 0, error.message)
                        }
                        events.trySend(Event(profile.id, result)).getOrThrow()
                    }
                }
            }
        }, collect = { events -> collectResults(groupId, profiles.size, events) })
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
