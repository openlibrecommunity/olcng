package io.nekohasekai.sagernet.group

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.migration.LegacyOlcngMigration
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OwengStartup {
    private val mutex = Mutex()
    private var ready = false
    private var result = LegacyOlcngMigration.MigrationResult(success = true)
    private val networkOwner = Any()

    suspend fun initialize(context: Context): LegacyOlcngMigration.MigrationResult = mutex.withLock {
        if (ready && result.success) return@withLock result
        result = LegacyOlcngMigration.migrate(context)
        androidx.work.multiprocess.RemoteWorkManager.getInstance(context).cancelUniqueWork("subscription_updater")
        val groupId = BuiltinPool.initialize()
        val migratedNow = LegacyOlcngMigration.hasLegacyData(context) && !result.alreadyMigrated
        if (migratedNow || SagerDatabase.groupDao.getById(DataStore.selectedGroup) == null) {
            DataStore.selectedGroup = groupId
        }
        if (!ready) {
            startNetworkListener()
        }
        BuiltinPool.requestRefresh("startup")
        ready = true
        result
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
