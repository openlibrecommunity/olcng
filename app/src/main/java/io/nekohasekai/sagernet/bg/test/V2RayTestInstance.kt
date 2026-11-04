/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.test

import android.net.Network
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.LocalResolver
import io.nekohasekai.sagernet.bg.proto.V2RayInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import owenbatch.Session
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.delay
import libexclavecore.Libexclavecore
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

class V2RayTestInstance(profile: ProxyEntity, val link: String, val timeout: Int, val protectPath: String = "") : V2RayInstance(
    profile,
), LocalResolver {
    private var batchConfig: V2rayBuildResult? = null
    private var batchPort = 0

    suspend fun prepareBatch(config: V2rayBuildResult) {
        batchConfig = config
        // the listener is loopback-only; native HTTP owns timeout and cancellation.
        batchPort = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        init()
        launch()
        if (pluginConfigs.isNotEmpty() || config.requireWs || config.requireSh) delay(500L)
    }

    fun probeBatch(session: Session): Int = session.probeProxy("127.0.0.1:$batchPort", link, timeout)

    lateinit var continuation: Continuation<Int>
    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            continuation = c
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    if (pluginConfigs.isNotEmpty()) {
                        delay(500L)
                    }
                    c.tryResume(Libexclavecore.urlTest(v2rayPoint, "", link, timeout))
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    @Volatile
    override var underlyingNetwork: Network? = null

    override suspend fun init() {
        super.init()
        if (batchConfig != null) return
        v2rayPoint.withLocalResolver(this)
        v2rayPoint.withAlternativeSystemDialer(protectPath)
        DefaultNetworkListener.start(this) {
            underlyingNetwork = it
        }
    }

    override fun close() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.stop(this)
        }
        super.close()
    }

    override fun buildConfig() {
        config = batchConfig ?: buildV2RayConfig(profile, forTest = true)
        if (batchConfig != null) {
            val json = JSONObject(config.config)
            val inbounds = json.optJSONArray("inbounds") ?: JSONArray()
            inbounds.put(JSONObject().put("tag", "oweng-probe").put("listen", "127.0.0.1")
                .put("port", batchPort).put("protocol", "http").put("settings", JSONObject()))
            json.put("inbounds", inbounds)
            config.config = json.toString()
            processes = GuardedProcessPool { Logs.w(it) }
        }
    }
}