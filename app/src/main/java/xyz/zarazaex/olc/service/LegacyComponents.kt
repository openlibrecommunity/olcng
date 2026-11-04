package xyz.zarazaex.olc.service

import android.content.Intent
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.group.OwengStartup
import io.nekohasekai.sagernet.migration.LegacyOlcngMigration
import kotlinx.coroutines.*

// keep component names used by always-on vpn and pinned quick settings tiles.
class V2RayVpnService : io.nekohasekai.sagernet.bg.VpnService() {
    private val migrationScope = MainScope()
    private var preparing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.selectedProxy > 0 || !LegacyOlcngMigration.hasLegacyData(this)) {
            return super.onStartCommand(intent, flags, startId)
        }
        if (!preparing) {
            preparing = true
            data.notification = createNotification("Перенос данных OLCNG")
            data.changeState(BaseService.State.Connecting)
            migrationScope.launch {
                val result = withContext(Dispatchers.IO) { OwengStartup.initialize(this@V2RayVpnService) }
                data.notification?.destroy()
                data.notification = null
                data.changeState(BaseService.State.Stopped)
                if (result.success) super.onStartCommand(intent, flags, startId)
                else stopRunner(false, "Не удалось перенести данные OLCNG")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        migrationScope.cancel()
        super.onDestroy()
    }
}

class V2RayProxyOnlyService : io.nekohasekai.sagernet.bg.ProxyService()

@androidx.annotation.RequiresApi(24)
class QSTileService : io.nekohasekai.sagernet.bg.TileService()
