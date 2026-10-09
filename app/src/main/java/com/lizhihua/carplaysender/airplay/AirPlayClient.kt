package com.lizhihua.carplaysender.airplay

import android.content.Context
import com.lizhihua.carplaysender.iap2.Iap2SenderSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID

/**
 * AirPlay 客户端（Phase 2）：
 *  - /info 能力清单拉取与 bplist 解析
 *  - /pair-setup + /pair-verify 完整配对（持久 RTSP 连接上顺序 POST TLV8）
 * 控制器长期身份（ed25519 + 配对 ID）首次生成后存入 SharedPreferences。
 * 后续在此叠加 SETUP/RECORD 流媒体会话。
 */
class AirPlayClient(
    private val endpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
) {
    private var socket: Socket? = null
    private var cSeq = 0

    /** pair-verify 之后启用：所有后续 RTSP 报文整体加密封装。 */
    private var cipher: ControlCipher? = null
    private var cipherPending = ByteArray(0)

    // ---- 对外流程 ----

    /**
     * 完整配对：pair-setup（注册长期公钥）+ pair-verify（派生控制密钥）。
     * DiPlay 的无线栈按轮重建 RTSP 监听，且 ctrl 探测（约 +1s）先于其就绪——
     * 因此延迟启动，且每次重试都换全新 TCP 连接。
     */
    fun runPairing(): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: 控制器身份 pairingId=${identity.pairingId}")
        for (attempt in 1..4) {
            close()
            if (!ensureConnected(2_000)) continue
            // 前哨：快速判定 RTSP 是否处于服务态（不服务就立刻放弃本轮，避免干等）
            val canary = try {
                val (statusLine, _) = request("GET", "/info", ByteArray(0), 2_000)
                log("AIRPLAY: 前哨 /info [$statusLine] —— RTSP 在线，立即配对")
                true
            } catch (error: IOException) {
                log("AIRPLAY: 前哨 /info 无应答（第 $attempt 轮放弃，等下一轮 BT 会话）")
                false
            }
            if (!canary) {
                close()
                return null
            }
            val transport = TlvTransport { path, body -> post(path, body) }
            try {
                PairSetupClient(identity, transport, log).run()
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // pair-verify 之后双方立即启用加密（接收端在 M4 应答后开启）
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: 控制通道加密已启用（后续 RTSP 报文全部加密）")
                return keys
            } catch (error: IOException) {
                log("AIRPLAY: 配对第 $attempt 次尝试失败 - ${error.message}")
                Thread.sleep(500)
            }
        }
        log("AIRPLAY: 配对最终失败（接收端 RTSP 无应答）")
        return null
    }

    /** 拉取并解析 /info（注意：接收端在活跃会话期间可能不应答）。 */
    fun fetchInfo(timeoutMillis: Int = 5_000): Map<*, *>? {
        if (!ensureConnected()) return null
        return try {
            val (statusLine, body) = request("GET", "/info", ByteArray(0), timeoutMillis)
            log("AIRPLAY: ✓ /info [$statusLine] body=${body.size} 字节")
            if (body.isEmpty()) return null
            val root = BinaryPlist.parse(body)
            if (root is Map<*, *>) {
                logInfoSummary(root)
                root
            } else {
                null
            }
        } catch (error: IOException) {
            log("AIRPLAY: /info 失败 - ${error.message}")
            null
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        leftover = ByteArray(0)
        cipherPending = ByteArray(0)
    }

    /** SETUP #1：发送会话信息，接收 eventPort/timingPort/enabledFeatures。 */
    fun runSetup(): SetupInfo? {
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val body = BinaryPlist.encode(
            linkedMapOf(
                "deviceID" to "02:00:00:00:00:00",
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "sourceVersion" to (endpoint.sourceVersion ?: "950.7.1"),
                "timingPort" to 0L,
                "features" to listOf("videoPlayback"),
            ),
        )
        val (statusLine, responseBody) = try {
            request(
                "SETUP",
                "rtsp://$host:${endpoint.airPlayPort}/",
                body,
                contentType = "application/x-apple-binary-plist",
            )
        } catch (error: IOException) {
            log("AIRPLAY: SETUP 失败 - ${error.message}")
            return null
        }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: SETUP 应答 [$statusLine]")
            return null
        }
        val root = try {
            BinaryPlist.parse(responseBody)
        } catch (error: IllegalArgumentException) {
            log("AIRPLAY: SETUP 应答 plist 解析失败（${responseBody.size} 字节）")
            null
        } as? Map<*, *>
        if (root == null) {
            log("AIRPLAY: SETUP 应答不是字典")
            return null
        }
        val info = SetupInfo(
            eventPort = portOf(root["eventPort"]),
            timingPort = portOf(root["timingPort"]),
            keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
            enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
        )
        log(
            "AIRPLAY: ✓ SETUP 完成 eventPort=${info.eventPort} timingPort=${info.timingPort} " +
                "keepAlive=${info.keepAlivePort ?: "无"} features=${info.enabledFeatures}",
        )
        return info
    }

    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
    )

    /** 屏幕流协商结果。 */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val streamKey: ByteArray,
    )

    /** plist 端口是 u16 但以 2 字节有符号整数编码（>32767 读出为负），按位掩出真实值。 */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    private companion object {
        /** 主屏视频流类型（对照接收端 STREAM_TYPE_MAIN_SCREEN；100 是音频、130 是数据/iAP 隧道）。 */
        const val SCREEN_STREAM_TYPE = 110L
    }

    /**
     * SETUP #2：协商主屏视频流（type 100）。streamConnectionID 由客户端指定，
     * 两端各自用 HKDF(共享密钥, "DataStream-Salt<id>", "DataStream-Output-Encryption-Key")
     * 推导同一把流密钥；接收端开 TCP 数据端口并在应答里返回。
     */
    fun runScreenStream(sharedSecret: ByteArray): ScreenStreamInfo? {
        val connectionId = System.currentTimeMillis() % 100_000L + 1L
        val body = BinaryPlist.encode(
            linkedMapOf(
                "streams" to listOf(
                    linkedMapOf<String, Any?>(
                        "type" to SCREEN_STREAM_TYPE, // 110 = 主屏（100 是音频，DiPlay 会开 UDP 端口）
                        "streamConnectionID" to connectionId,
                        "uuid" to java.util.UUID.randomUUID().toString(),
                    ),
                ),
            ),
        )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val (statusLine, responseBody) = try {
            request(
                "SETUP",
                "rtsp://$host:${endpoint.airPlayPort}/",
                body,
                contentType = "application/x-apple-binary-plist",
            )
        } catch (error: IOException) {
            log("AIRPLAY: 流协商失败 - ${error.message}")
            return null
        }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: 流协商应答 [$statusLine]")
            return null
        }
        val root = try {
            BinaryPlist.parse(responseBody) as? Map<*, *>
        } catch (error: IllegalArgumentException) {
            null
        } ?: run {
            log("AIRPLAY: 流协商应答解析失败")
            return null
        }
        val streams = root["streams"] as? List<*> ?: run {
            log("AIRPLAY: 流协商应答缺少 streams")
            return null
        }
        val screen = streams.firstOrNull() as? Map<*, *>
        val dataPort = portOf(screen?.get("dataPort"))
        if (screen == null || dataPort == 0) {
            log("AIRPLAY: 主屏流被拒绝（streams=$streams）")
            return null
        }
        val streamKey = AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )
        log(
            "AIRPLAY: ✓ 主屏流协商成功 dataPort=$dataPort connId=$connectionId " +
                "streamKey=${streamKey.joinToString("") { "%02x".format(it) }.take(16)}…",
        )
        return ScreenStreamInfo(connectionId, dataPort, streamKey)
    }

    /** RECORD：激活会话（接收端 onSessionActive）。 */
    fun record(): Boolean = try {
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val (statusLine, _) = request(
            "RECORD",
            "rtsp://$host:${endpoint.airPlayPort}/",
            BinaryPlist.encode(linkedMapOf<String, Any?>()),
            contentType = "application/x-apple-binary-plist",
        )
        val ok = statusLine.contains("200")
        log(if (ok) "AIRPLAY: ✓ RECORD 完成——会话已激活" else "AIRPLAY: RECORD 应答 [$statusLine]")
        ok
    } catch (error: IOException) {
        log("AIRPLAY: RECORD 失败 - ${error.message}")
        false
    }

    /**
     * 连接事件通道（接收端在 SETUP 应答的 eventPort 上等我们）。事件通道用
     * Events 密钥加密（注意方向标签与直觉相反：接收端用 Events-Read 读我们的数据）。
     * 读取线程把解密明文按 RTSP 报文重组、解析 plist 命令（如 hidSendReport 触摸）
     * 并回调 [onCommand]。
     */
    fun connectEventChannel(
        sharedSecret: ByteArray,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ) {
        if (setup.eventPort == 0) {
            log("EVENT: SETUP 未提供 eventPort，跳过")
            return
        }
        val host = ipv4Host() ?: return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, setup.eventPort), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            log("EVENT: ✓ 已连接事件通道 $host:${setup.eventPort}")
            val phoneWriteKey = AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "Events-Salt".toByteArray(),
                "Events-Read-Encryption-Key".toByteArray(),
            )
            val phoneReadKey = AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "Events-Salt".toByteArray(),
                "Events-Write-Encryption-Key".toByteArray(),
            )
            val cipher = ControlCipher(phoneReadKey, phoneWriteKey)
            Thread({
                val input = socket.getInputStream()
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            log("EVENT: 通道关闭（EOF）")
                            break
                        }
                        pending += buffer.copyOf(count)
                        val decrypted = cipher.decrypt(pending)
                        pending = decrypted.rest
                        for ((firstLine, body) in accumulator.offer(decrypted.data)) {
                            if (!firstLine.contains("/command")) continue
                            val command = try {
                                BinaryPlist.parse(body) as? Map<*, *>
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                            if (command == null) {
                                log("EVENT: 命令 plist 解析失败 [$firstLine] body=${body.size}B")
                                continue
                            }
                            try {
                                onCommand(command)
                            } catch (error: Exception) {
                                log("EVENT: 命令处理异常 - ${error.message}")
                            }
                        }
                    }
                } catch (error: IOException) {
                    log("EVENT: 读取结束 - ${error.message}")
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("EVENT: 连接失败 - ${error.message}")
        }
    }

    /** 流式 RTSP 报文重组器：喂入任意分片明文，弹出完整消息（首行 + body）。 */
    private class RtspAccumulator {
        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Pair<String, ByteArray>> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Pair<String, ByteArray>>()
            val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            while (true) {
                val index = indexOf(buffer, separator)
                if (index < 0) break
                val headText = String(buffer, 0, index, Charsets.ISO_8859_1)
                val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bodyStart = index + separator.size
                if (buffer.size - bodyStart < contentLength) break
                val firstLine = headText.lineSequence().firstOrNull() ?: ""
                messages += firstLine to buffer.copyOfRange(bodyStart, bodyStart + contentLength)
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }

        private fun indexOf(source: ByteArray, target: ByteArray): Int {
            outer@ for (index in 0..source.size - target.size) {
                for (offset in target.indices) {
                    if (source[index + offset] != target[offset]) continue@outer
                }
                return index
            }
            return -1
        }
    }

    /** 跨请求残留：响应头与下一个请求可能同段到达。 */
    private var leftover: ByteArray = ByteArray(0)

    // ---- RTSP 请求 ----

    private fun ensureConnected(timeoutMillis: Int = 5_000): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        val host = ipv4Host() ?: run {
            log("AIRPLAY: 0x4301 未携带 IPv4 地址，无法连接")
            return false
        }
        return try {
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, endpoint.airPlayPort), timeoutMillis)
            newSocket.soTimeout = timeoutMillis
            socket = newSocket
            log("AIRPLAY: TCP 已连通 $host:${endpoint.airPlayPort}（本机 ${newSocket.localAddress.hostAddress}）")
            true
        } catch (error: IOException) {
            log(
                "AIRPLAY: 连接失败 - ${error.message}\n" +
                    "  ↳ 若反复失败：确认手机当前连的是接收端热点 \"${endpoint.ssid}\"，" +
                    "而不是家里/公司 WiFi（绑定到的网络不对时到达不了车机网段）",
            )
            false
        }
    }

    private fun post(path: String, body: ByteArray): ByteArray {
        val (statusLine, responseBody) = request("POST", path, body)
        if (!statusLine.contains("200")) {
            throw IOException("POST $path 应答 [$statusLine]")
        }
        return responseBody
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int = 10_000,
        contentType: String = "application/octet-stream",
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("未连接")
        client.soTimeout = timeoutMillis
        val head = "$method $path RTSP/1.0\r\n" +
            "CSeq: ${++cSeq}\r\n" +
            "User-Agent: AirPlay/${endpoint.sourceVersion ?: "950.7.1"}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        val plaintext = head.toByteArray(Charsets.US_ASCII) + body
        val output = client.getOutputStream()
        output.write(cipher?.encrypt(plaintext) ?: plaintext)
        output.flush()

        val input = client.getInputStream()
        val encryption = cipher
        if (encryption == null) {
            var buffer = leftover
            leftover = ByteArray(0)
            while (true) {
                val message = parseCompleteMessage(buffer)
                if (message != null) return message
                val chunk = ByteArray(4096)
                val count = input.read(chunk)
                if (count < 0) throw IOException("响应断流（已缓冲 ${buffer.size} 字节）")
                buffer += chunk.copyOf(count)
            }
        }
        // 加密路径：读密文帧 → 解密 → 明文里找完整 RTSP 报文
        var pending = cipherPending
        cipherPending = ByteArray(0)
        var plaintextAcc = leftover
        leftover = ByteArray(0)
        while (true) {
            val message = parseCompleteMessage(plaintextAcc)
            if (message != null) return message
            val chunk = ByteArray(4096)
            val count = input.read(chunk)
            if (count < 0) throw IOException("加密响应断流（pending=${pending.size}）")
            pending += chunk.copyOf(count)
            val decrypted = encryption.decrypt(pending)
            pending = decrypted.rest
            plaintextAcc += decrypted.data
        }
    }

    /** 完整 RTSP 报文（头 + Content-Length body）才返回；残留部分写入 leftover。 */
    private fun parseCompleteMessage(buffer: ByteArray): Pair<String, ByteArray>? {
        val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val separatorIndex = indexOfSeparator(buffer, separator)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val bodyStart = separatorIndex + separator.size
        if (buffer.size - bodyStart < contentLength) return null
        val statusLine = headText.lineSequence().firstOrNull() ?: "(空)"
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        leftover = if (buffer.size > bodyStart + contentLength) {
            buffer.copyOfRange(bodyStart + contentLength, buffer.size)
        } else {
            ByteArray(0)
        }
        return statusLine to responseBody
    }

    private fun indexOfSeparator(buffer: ByteArray, separator: ByteArray): Int {
        outer@ for (index in 0..buffer.size - separator.size) {
            for (offset in separator.indices) {
                if (buffer[index + offset] != separator[offset]) continue@outer
            }
            return index
        }
        return -1
    }

    // ---- 身份持久化 ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        if (storedId != null && storedPrivate != null) {
            val privateKey = Base64.getDecoder().decode(storedPrivate)
            val publicKey = AirPlayCrypto.ed25519PublicKey(privateKey)
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val pairingId = UUID.randomUUID().toString()
        preferences?.edit()
            ?.putString("pairingId", pairingId)
            ?.putString("privateKey", Base64.getEncoder().encodeToString(keyPair.privateKey))
            ?.apply()
        return ControllerIdentity(pairingId, keyPair.privateKey, keyPair.publicKey)
    }

    // ---- 工具 ----

    private fun ipv4Host(): String? =
        endpoint.ipAddresses.firstOrNull { address ->
            address.contains('.') && !address.contains(':')
        }

    private fun logInfoSummary(root: Map<*, *>) {
        val features = root["features"] as? Long
        log(
            "AIRPLAY: /info 摘要 name=${root["name"]} model=${root["model"]} " +
                "deviceID=${root["deviceID"]}" +
                if (features != null) " features=0x${features.toString(16)}" else "",
        )
        (root["displays"] as? List<*>)?.forEachIndexed { index, display ->
            if (display is Map<*, *>) {
                log(
                    "AIRPLAY: display[$index] uuid=${display["UUID"]} " +
                        "${display["WidthPhysical"]}x${display["HeightPhysical"]}@${display["RefreshRate"]}Hz",
                )
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.trim()
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
