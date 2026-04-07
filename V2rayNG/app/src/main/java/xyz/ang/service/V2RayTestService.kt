package xyz.zarazaex.olc.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.AppConfig.MSG_MEASURE_CONFIG
import xyz.zarazaex.olc.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import xyz.zarazaex.olc.dto.TestServiceMessage
import xyz.zarazaex.olc.extension.serializable
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.util.MessageUtil
import java.util.Collections

class V2RayTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        V2RayNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content") ?: return super.onStartCommand(intent, flags, startId)
        when (message.key) {
            MSG_MEASURE_CONFIG -> {
                val guidsList = if (message.serverGuids.isNotEmpty()) {
                    message.serverGuids
                } else if (message.subscriptionId.isNotEmpty()) {
                    MmkvManager.decodeServerList(message.subscriptionId)
                } else {
                    MmkvManager.decodeAllServerList()
                }

                if (guidsList.isNotEmpty()) {
                    lateinit var worker: RealPingWorkerService
                    worker = RealPingWorkerService(this, guidsList) { status ->
                        // notify UI and remove the worker from active list when finished
                        MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                        activeWorkers.remove(worker)
                    }
                    activeWorkers.add(worker)
                    worker.start()
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                // cancel all running batch workers independently
                val snapshot = ArrayList(activeWorkers)
                snapshot.forEach { it.cancel() }
                activeWorkers.clear()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
}