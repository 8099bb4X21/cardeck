package com.lizhihua.carplaysender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * 广播手机侧（iPhone 角色）的 _carplay-ctrl._tcp 控制服务并应答接收端探测。
 *
 * 无线 CarPlay 中接收端发现该服务后会反连此端口，先发
 * `GET /ctrl-int/1/connect`（携带 AirPlay-Receiver-Device-ID）探测手机是否在线，
 * 期待 HTTP 200（对照 xcertplay CarPlayBonjour.probeOnce：只读状态行）。
 * 探测通过后，iAP2 控制会话经 AirPlay 事件通道的 type-130 隧道接管（Phase 2 后续）。
 *
 * 本类同时把每个连接的请求行打进日志——DiPlay 后续发什么会直接可见。
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024
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
            val mac = com.lizhihua.carplaysender.util.DeviceIdentity.bluetoothMac(context)
            val info = NsdServiceInfo().apply {
                serviceName = "sender"
                serviceType = SERVICE_TYPE
                setPort(socket.localPort)
                // TXT id = 本机真实蓝牙 MAC（catplay CarPlayInviter 实证：车机按此
                // 匹配待邀请的手机，占位值会导致车机永远不邀请 → 0x4301 永不到达）。
                setAttribute("id", mac)
                setAttribute("srcvers", "950.7.1")
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
            Thread({ handleClient(client) }, "ctrl-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer 连入但无请求（EOF）")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers = head.substringAfter("\r\n", "")
                .lineSequence()
                .filter { it.contains(':') }
                .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            val output = client.getOutputStream()
            val response = when {
                requestLine.startsWith("GET /ctrl-int/") -> {
                    log("$TAG: ✓ 应答 ctrl-int 探测 200 OK")
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                }

                else -> {
                    log("$TAG: 未识别的请求，按 200 空应答处理（后续实现按需细化）")
                    "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                }
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            // 对端 Connection: close 会自行断开；等待期间若还有字节（隧道/事件），转储出来
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // 读超时即停止转储
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: 应答后收到 ${rest.size()} 字节：" +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: $peer 连接结束")
        } catch (error: IOException) {
            log("$TAG: $peer 连接异常 - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count = try {
                input.read(chunk)
            } catch (_: IOException) {
                break
            }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
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
