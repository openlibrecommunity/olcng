package xyz.zarazaex.olc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.handler.V2RayServiceManager

class ServiceControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppConfig.BROADCAST_ACTION_SERVICE_STOP -> {
                V2RayServiceManager.isIntentionalStop = true
                V2RayServiceManager.stopVService(context)
            }
            AppConfig.BROADCAST_ACTION_SERVICE_START -> V2RayServiceManager.startVServiceFromToggle(context)
        }
    }
}
