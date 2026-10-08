package com.lizhihua.carplaysender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.lizhihua.carplaysender.util.HexDumpObserver
import java.net.ServerSocket

/**
 * 广播手机侧（iPhone 角色）的 _carplay-ctrl._tcp 控制服务。
 *
 * 真实无线 CarPlay 中，加入同一网络后 iPhone 广播该服务、车机反连此端口
 * 建立控制通道（对照 xcertplay CarPlayBonjour：它发现并 resolve 这个服务）。
 *
 * Phase 0：注册服务 + 接受连接后十六进制转储，验证「接收端能找到并连上我」。
 * Phase 1：接入 iAP2 链路状态机。
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "CTRL"
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registered = false

    val port: Int get() = serverSocket?.localPort ?: -1

    fun start(): Boolean {
        if (registered) return true
        return try {
            val socket = ServerSocket(0)
            serverSocket = socket
            val info = NsdServiceInfo().apply {
                serviceName = "sender"
                serviceType = SERVICE_TYPE
                setPort(socket.localPort)
                // TXT id = 蓝牙 MAC（Android 11+ 拿不到自身 MAC，Phase 1 再想办法，
                // xcertplay 仅将其用于日志/重连参考）
                setAttribute("id", "000000000000")
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            registered = true
            log("$TAG: 已监听 :${socket.localPort}，广播 $SERVICE_TYPE")
            Thread(::acceptLoop, "ctrl-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("$TAG: 启动失败 - ${error.message}")
            false
        }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (registered) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            log("$TAG: 接收端连入 ${client.inetAddress?.hostAddress}:${client.port}，开始转储")
            HexDumpObserver(
                client.getInputStream(),
                "CTRL",
                onLine = { line -> log(line) },
            ).start()
        }
    }

    fun stop() {
        registered = false
        runCatching { nsdManager.unregisterService(registrationListener) }
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: 已停止")
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            log("$TAG: mDNS 注册失败 code=$errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }
}
