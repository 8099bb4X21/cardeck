package com.lizhihua.carplaysender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS 服务发现：找到接收端（车机/xcertplay）广播的 _airplay._tcp，
 * 解析出 AirPlay 端点与 TXT 记录（deviceid/features/pi/pk/model…）。
 *
 * 真实时序中这一步发生在加入车机热点之后、发起 AirPlay 主会话之前。
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovering = false

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: 已在扫描中")
            return
        }
        log("$TAG: 开始发现 $serviceType")
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        discovering = true
    }

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
        log("$TAG: 停止发现")
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            log("$TAG: 发现启动失败 code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            log("$TAG: 停止失败 code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
            resolveWithRetry(serviceInfo, attempts = 3)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            log("$TAG: 服务消失 ${serviceInfo.serviceName}")
        }
    }

    private fun resolveWithRetry(serviceInfo: NsdServiceInfo, attempts: Int) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                    Thread {
                        Thread.sleep(200)
                        resolveWithRetry(info, attempts - 1)
                    }.start()
                } else {
                    log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: "no-host"
                val txt = info.attributes.entries.joinToString(", ") { (key, value) ->
                    val text = value
                        ?.toString(Charsets.UTF_8)
                        ?.trimEnd { it.code == 0 }
                        .orEmpty()
                    "$key=$text"
                }
                log("$TAG: OK ${info.serviceName} -> $host:${info.port}")
                if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                onEndpoint(info)
            }
        }
        nsdManager.resolveService(serviceInfo, listener)
    }
}
