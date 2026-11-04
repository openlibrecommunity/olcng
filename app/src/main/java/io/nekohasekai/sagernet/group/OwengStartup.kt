package io.nekohasekai.sagernet.group

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.migration.LegacyOlcngMigration
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object OwengStartup {
    data class StartupState(
        val running: Boolean = false,
        val migration: LegacyOlcngMigration.MigrationResult? = null,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(StartupState())
    val state: StateFlow<StartupState> = mutableState.asStateFlow()
    private var task: Deferred<LegacyOlcngMigration.MigrationResult>? = null
    private var listening = false
    private val networkOwner = Any()

    fun start(context: Context, retryMigration: Boolean = false) {
        startupTask(context.applicationContext, retryMigration)
    }

    suspend fun initialize(context: Context): LegacyOlcngMigration.MigrationResult =
        startupTask(context.applicationContext, false).await()

    @Synchronized
    private fun startupTask(context: Context, retry: Boolean): Deferred<LegacyOlcngMigration.MigrationResult> {
        task?.let { if (!retry || !it.isCompleted) return it }
        val initialGroup = DataStore.selectedGroup
        return scope.async {
            mutableState.value = mutableState.value.copy(running = true, error = null)
            var result: LegacyOlcngMigration.MigrationResult? = null
            try {
                result = LegacyOlcngMigration.migrate(context, retry)
                mutableState.value = StartupState(running = true, migration = result)
                androidx.work.multiprocess.RemoteWorkManager.getInstance(context).cancelUniqueWork("subscription_updater")
                val groupId = BuiltinPool.initialize()
                val selectedGroup = SagerDatabase.groupDao.getById(DataStore.selectedGroup)
                if (selectedGroup == null || (initialGroup == 0L && selectedGroup.ungrouped && DataStore.selectedProxy == 0L)) {
                    DataStore.selectedGroup = groupId
                    GroupManager.postReload(groupId)
                }
                if (!listening) {
                    startNetworkListener()
                    listening = true
                }
                BuiltinPool.requestRefresh("startup")
                mutableState.value = StartupState(migration = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                if (result == null) result = LegacyOlcngMigration.MigrationResult(
                    success = false, error = e.message ?: "Не удалось перенести данные OLCNG",
                )
                mutableState.value = StartupState(migration = result, error = e.message ?: "Не удалось подготовить список")
            }
            checkNotNull(result)
        }.also { task = it }
    }

    fun acknowledgeMigration(context: Context) {
        val application = context.applicationContext
        scope.launch {
            try {
                LegacyOlcngMigration.acknowledge(application)
                synchronized(this@OwengStartup) {
                    val current = mutableState.value
                    mutableState.value = current.copy(migration = current.migration?.copy(deferred = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    private suspend fun startNetworkListener() {
        var lastNetworkState: String? = null
        DefaultNetworkListener.start(networkOwner) { network ->
            val capabilities = network?.let { io.nekohasekai.sagernet.SagerNet.connectivity.getNetworkCapabilities(it) }
            val state = network?.let {
                "$it:${capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
            }
            if (state != lastNetworkState) {
                lastNetworkState = state
                if (network != null) BuiltinPool.onConnectivityChanged()
            }
        }
    }
}
