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

    @Volatile
    private var registered = false

    /** 注册时的 WiFi SSID：换网（2.4G→5G 或车机热点切换）后旧注册不可见，需重新注册。 */
    private var registeredSsid: String? = null

    /** 当前生效的注册监听器（每次 start 换新实例，避免 already-in-use）。 */
    private var activeListener: NsdManager.RegistrationListener? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * 确保广播在指定 SSID 的网络上可见（每次会话拿到 0x5703 时调用）。
     * 真车实测（2026-10-11）：手机从 2.4G 换到 5G 热点后，旧 mDNS 注册
     * 不再被车机看到（车机不再发 ctrl-int 探测 → 不开 AirPlay 窗口）。
     * SSID 变化时先注销再重新注册，让广播落到当前网络。
     *
     * @param force true = 无条件重新注册（用于「绑定进程网络后」的时机修正：
     *   mDNS 组播跟随 bindProcessToNetwork 的网络，注册必须发生在绑定之后）。
     */
    fun ensureForNetwork(ssid: String, force: Boolean = false): Boolean {
        if (registered && registeredSsid == ssid && !force) return true
        if (registered) {
            log("$TAG: 重新注册 mDNS 广播（${registeredSsid ?: "?"}${if (force) "，绑定后时机修正" else " → $ssid"}）")
            stop()
        }
        registeredSsid = ssid
        var ok = start()
        if (!ok) {
            // 注销是异步的：极端情况下 registerService 会遇到 "listener already in use"，
            // 稍候重试一次（真车实测 10-11：300ms 不够，需要更长）
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

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
            // 每次注册用全新 listener：unregisterService 是异步的，复用同一个 listener
            // 会撞上 "listener already in use"（真车实测 10-11 卡住 ctrl 广播）
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
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
        registeredSsid = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: 已停止")
    }

    /** 每次注册新建 listener（见 start() 注释；复用同一实例会触发 already in use）。 */
    private fun createListener(): NsdManager.RegistrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // 重置状态：下一次 ensureForNetwork（新会话的 0x5703）会重新注册
            registered = false
            log("$TAG: mDNS 注册失败 code=$errorCode（下次会话自动重试）")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }
}
