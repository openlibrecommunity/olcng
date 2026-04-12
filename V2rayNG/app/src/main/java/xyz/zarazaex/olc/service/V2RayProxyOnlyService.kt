package xyz.zarazaex.olc.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.contracts.ServiceControl
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.V2RayServiceManager
import xyz.zarazaex.olc.util.MyContextWrapper
import java.lang.ref.SoftReference

class V2RayProxyOnlyService : Service(), ServiceControl {
    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConfig.TAG, "StartCore-Proxy: Service command received")
        V2RayServiceManager.startCoreLoop(null)
        return START_NOT_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        super.onDestroy()
        V2RayServiceManager.stopCoreLoop()
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        stopSelf()
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
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
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}
