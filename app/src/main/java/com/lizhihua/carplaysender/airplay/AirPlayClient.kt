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

    /**
     * 计时服务（初始 SETUP 通告端口；车机 NTP ping 它同步媒体时钟）。
     * 懒启动、跨重试常驻（close() 不停它——UDP socket 无会话状态）。
     */
    private val timingServer = TimingServer(log)

    /** pair-verify 之后启用：所有后续 RTSP 报文整体加密封装。 */
    private var cipher: ControlCipher? = null
    private var cipherPending = ByteArray(0)

    // ---- 对外流程 ----

    /** pair-setup 是否被 403 拒绝（真车特征）；主控据此决定是否使用 BT 退役 hack。 */
    @Volatile
    var pairSetupRejected: Boolean = false
        private set

    /** 本轮是否真正开始过配对（TCP 连上过接收端）。DiPlay 断链 hack 的启用条件。 */
    @Volatile
    var attemptedPairing: Boolean = false
        private set

    /**
     * 配对：先试 pair-setup（注册长期公钥）→ pair-verify（派生控制密钥）；
     * 若 pair-setup 被 **403** 拒绝（真车 Audi MIB3 实测：iAP2 阶段已完成 MFi 信任，
     * 不接受匿名 SRP 配对窗口），则改走「已配对设备快路径」直接 pair-verify。
     * 每次重试都换全新 TCP 连接（部分接收端按连接维护状态机）。
     *
     * 不预热 /info：真车实测（2026-10-11）车机对未配对连接上的 /info 应答 455
     * 且此后同连接上的 pair-setup 被 403；Apple 客户端与 catplay 均直接进配对。
     * 配对请求头按 catplay PairingHelperTx 逐字对齐（X-Apple-HKP / X-Apple-PD）。
     */
    fun runPairing(): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: 控制器身份 pairingId=${identity.pairingId}")
        // 车机 AirPlay 窗口是「用户点车机上的手机图标 / 收到 ctrl 邀请后才开」的
        // （Audi 实测：端口从 Connection refused 变为开放）。因此重试采用时限制：
        // 默认 120s 内每 2s 试一次——用户在车机上点一下的等待不会被提前放弃。
        val deadline = System.currentTimeMillis() + PAIRING_WINDOW_MILLIS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            close()
            if (!ensureConnected(2_000)) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log("AIRPLAY: 车机 AirPlay 端口未开（等待你点车机上的手机图标 / 车机发 ctrl 邀请）…第 ${attempt} 次重试")
                }
                Thread.sleep(2_000)
                continue
            }
            attemptedPairing = true
            val transport = TlvTransport { path, body -> postPairing(path, body) }
            try {
                val setupRejected = try {
                    PairSetupClient(identity, transport, log).run()
                    false
                } catch (error: IOException) {
                    val forbidden = error.message?.contains("403") == true
                    if (forbidden) pairSetupRejected = true
                    log(
                        "AIRPLAY: pair-setup " +
                            if (forbidden) {
                                "被 403 拒绝（车机不接受匿名配对——iAP2 已完成信任）→ 转已配对快路径"
                            } else {
                                "失败（${error.message}）"
                            },
                    )
                    forbidden
                }
                // 403 后旧连接的状态机不可用：换全新连接走快路径
                if (setupRejected) {
                    close()
                    if (!ensureConnected(2_000)) continue
                    val fastPathTransport = TlvTransport { path, body -> postPairing(path, body) }
                    val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                    val keys = PairVerifyClient(identity, fastPathTransport, log).run(receiverStaticKey)
                    cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                    log("AIRPLAY: 控制通道加密已启用（已配对快路径）")
                    return keys
                }
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // pair-verify 之后双方立即启用加密（接收端在 M4 应答后开启）
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: 控制通道加密已启用")
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

    /**
     * KeepAlive UDP 心跳（对照 catplay KeepAliveClient）：向车机 keepAlivePort 每 10s
     * 发 4 字节随机 UDP；协议无应答。**缺失时车机 10s 看门狗拆整轮会话**
     * （2026-10-11 实测：视频推流 10.6s 后被拆，时序严丝合缝）。
     * socket 关闭（会话结束）后线程自行退出。
     */
    fun startKeepAlive(host: String, port: Int) {
        if (port <= 0) return
        Thread({
            var udp: java.net.DatagramSocket? = null
            try {
                udp = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName(host)
                var sent = 0
                while (isSessionAlive() && udp?.isClosed == false) {
                    val payload = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                    udp.send(java.net.DatagramPacket(payload, payload.size, address, port))
                    sent++
                    if (sent <= 3 || sent % 30 == 0) log("AIRPLAY: keepAlive ping #$sent → $host:$port")
                    Thread.sleep(10_000)
                }
            } catch (_: InterruptedException) {
            } catch (error: IOException) {
                log("AIRPLAY: keepAlive 结束 - ${error.message}")
            } finally {
                udp?.close()
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * RTSP /feedback 心跳（对照 catplay FEEDBACK_INTERVAL=1000ms）：每秒 POST 空体
     * /feedback——接收端的活动看门狗。RTSP 事务由 [rtspLock] 串行化。
     */
    fun startFeedbackLoop() {
        Thread({
            var sent = 0
            while (isSessionAlive()) {
                try {
                    val (status, body) = request("POST", "/feedback", ByteArray(0))
                    sent++
                    if (sent <= 3 || sent % 30 == 0) {
                        log("AIRPLAY: feedback #$sent [$status]${if (body.isNotEmpty()) " body=${body.size}B" else ""}")
                    }
                } catch (_: IOException) {
                    break // 连接已断（会话结束）
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "airplay-feedback").apply {
            isDaemon = true
            start()
        }
    }

    /** 心跳线程的存活判据：RTSP socket 仍连着。 */
    private fun isSessionAlive(): Boolean = socket?.isClosed == false

    fun close() {
        runCatching { socket?.close() }
        socket = null
        leftover = ByteArray(0)
        cipherPending = ByteArray(0)
        runCatching { iapTunnel?.socket?.close() }
        iapTunnel = null
    }

    /**
     * 声明 HID 输入模式（hidSetInputMode，FromDevice 方向——手机→车机的触摸启用命令）。
     * 对照 catplay CommandHidSetInputMode {hidInputMode u8, uuid string}；
     * uuid 为 /info hidDevices[].uuid 的十六进制串（DiPlay sendHidReport 同款 uid.toString(16)）。
     * 奥迪 display features=0x08（高保真触摸）+ hidDevices 齐备，触摸无报文的最大嫌疑
     * 是缺这一步武装。
     */
    fun sendHidSetInputMode(deviceUuidHex: String, mode: Int = 0): Boolean {
        val sent = postEventCommand(
            linkedMapOf(
                "type" to "hidSetInputMode",
                "params" to linkedMapOf<String, Any?>(
                    "hidInputMode" to mode.toLong(),
                    "uuid" to deviceUuidHex,
                ),
            ),
        )
        log("AIRPLAY: hidSetInputMode(事件通道) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    /**
     * 声明资源归属（modesChanged）：手机持有主屏 + 主音频（对照 catplay assert_modes）。
     * 真车验证的顺序：modesChanged → 屏幕流 SETUP。跳过此声明时 Audi MIB3 对
     * 屏幕流 SETUP 直接 400（资源仲裁未通过）。appStates：话机/导航归 None（0）、
     * 语音 None（-1）；resources：主屏(1)/主音频(2)，entity=permanent=Controller(1)。
     */
    // ---- 事件通道命令发送（对照 DiPlay sendCommandLocked）----
    // 车机命令双向都走事件通道 socket 上的 POST /command（不是 RTSP 控制连接！）：
    // iAPSendMessage（虚拟链路字节）、hidSetInputMode、modesChanged 等。
    // 异步 fire-and-forget：不等应答（DiPlay 同款写法），独立 CSeq 计数。

    @Volatile
    private var eventCommandSocket: Socket? = null

    @Volatile
    private var eventCommandCipher: ControlCipher? = null

    private val eventCommandLock = Any()
    private var eventCseq = 0

    /** 未知请求（非 /command 的合法请求）累计数：前 10 条留痕后静默。 */
    @Volatile
    private var unknownEventRequests = 0

    /** 事件通道建立后由 connectEventChannel 调用。 */
    private fun registerEventCommandChannel(socket: Socket, cipher: ControlCipher) {
        eventCommandSocket = socket
        eventCommandCipher = cipher
    }

    /** 在事件通道上发 POST /command（加密）。通道未就绪时返回 false。 */
    fun postEventCommand(command: Map<String, Any?>): Boolean {
        val socket = eventCommandSocket ?: return false
        val cipher = eventCommandCipher ?: return false
        return try {
            synchronized(eventCommandLock) {
                eventCseq++
                val body = BinaryPlist.encode(command)
                val head = "POST /command RTSP/1.0\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: \${body.size}\r\n" +
                    "CSeq: \$eventCseq\r\n\r\n"
                val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            true
        } catch (error: IOException) {
            log("EVENT: 命令发送失败 - \${error.message}")
            false
        }
    }

    /**
     * 事件通道请求应答：只对**车机的请求**回 RTSP/1.0 200 OK，并 echo 其 CSeq
     * （DiPlay buildResponse 同款，对照 RtspMessage.kt:76）。踩坑记录（2026-10-11）：
     * 此前对一切入向消息都回 200——对"应答"回应答与 DiPlay 形成乒乓：DiPlay 把我们的
     * 多余应答解析成请求（"RTSP/1.0" 当 method 剥掉后 protocol 取到第三 token），
     * 回出畸形行 "OK 200 OK"；我们再回 → 无限回声，日志雪崩且空耗加密通道。
     * 通道写与 postEventCommand 共用一把锁，避免两线程加密帧交叉。
     */
    private fun respondEventRequest(cseq: String?) {
        val socket = eventCommandSocket ?: return
        val cipher = eventCommandCipher ?: return
        try {
            synchronized(eventCommandLock) {
                val head = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                    (cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                socket.getOutputStream().apply {
                    write(cipher.encrypt(head.toByteArray(Charsets.US_ASCII)))
                    flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    fun assertModes(): Boolean {
        val body = BinaryPlist.encode(
            linkedMapOf(
                "type" to "modesChanged",
                "params" to linkedMapOf(
                    "appStates" to listOf(
                        linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                        linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                        linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                    ),
                    "resources" to listOf(
                        linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                        linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                    ),
                ),
            ),
        )
        return try {
            val (statusLine, _) = request(
                "POST",
                "/command",
                body,
                contentType = "application/x-apple-binary-plist",
            )
            log("AIRPLAY: modesChanged（声明持有主屏/主音频资源）[$statusLine]")
            statusLine.contains("200")
        } catch (error: IOException) {
            log("AIRPLAY: modesChanged 失败 - ${error.message}")
            false
        }
    }

    /**
     * SETUP #1：发送会话信息，接收 eventPort/timingPort/enabledFeatures。
     *
     * features 必须发「完整 iPhone 能力清单」：接收端计算
     * enabledFeatures = 车机支持 ∩ 手机提议，**不在提议里的能力一律不启用**。
     * 2026-10-11 奥迪实测教训：只提议 ["videoPlayback"] 时车机返回
     * enabledFeatures=[]（视频管线未启用），随后的屏幕流 SETUP 被 400。
     * 清单对照 catplay（真车验证的发送端，含 viewAreas/uiContext 等视频必需项）。
     */
    fun runSetup(): SetupInfo? {
        // 计时端口：catplay 真车实现始终绑定真实 UDP timing server 通告给车机
        // （媒体时钟同步源）；timingPort=0 曾是我们的屏幕流 400 头号嫌疑。
        if (!timingServer.start()) {
            log("AIRPLAY: 计时服务未启动（继续，但媒体时钟无法同步）")
        }
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val body = BinaryPlist.encode(
            linkedMapOf(
                "deviceID" to "ff:ee:dd:cc:bb:aa", // catplay 同款假值（车机不校验）
                "macAddress" to "aa:bb:cc:dd:ee:ff",
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osBuildVersion" to "19A404",
                "osVersion" to "15.0.2",
                // iPhone 自己的 AirPlay 版本（catplay 实证值）；此前误用车机的 320.17.7
                "sourceVersion" to SENDER_SOURCE_VERSION,
                "timingPort" to timingServer.port.toLong(),
                "sessionUUID" to java.util.UUID.randomUUID().toString().uppercase(),
                "sessionCorrelationUUID" to java.util.UUID.randomUUID().toString().uppercase(),
                "statsCollectionEnabled" to false,
                "keepAliveLowPower" to true,
                "features" to CONTROLLER_FEATURES,
            ),
        )
        log("AIRPLAY: 初始 SETUP body=${body.size}B timingPort=${timingServer.port}")
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
        // 拉取主显示屏参数（跟随接收端屏幕方向；横屏车机应推横屏画面，触摸坐标也按此对齐）
        var infoRoot: Map<*, *>? = null
        val display = try {
            val (_, infoBody) = request("GET", "/info", ByteArray(0))
            val info = BinaryPlist.parse(infoBody) as? Map<*, *>
            infoRoot = info
            val displays = info?.get("displays") as? List<*>
            displays?.firstOrNull() as? Map<*, *>
        } catch (_: Exception) {
            null
        }
        // /info 全量转储：找奥迪私有的数据流 UUID（type-130 的 clientTypeUUID 候选）、
        // HID 设备描述（触摸）、以及任何我们没实现的能力声明
        // uuid 原样保留（奥迪发字符串 "0"/"1"，DiPlay 用整数——hidSetInputMode 原样回传）
        val parsedHidUuids = ArrayList<String>()
        infoRoot?.let { root ->
            val skipped = setOf("displays")
            val summary = root.entries.joinToString("; ") { (key, value) ->
                if (key in skipped) "$key=[${(value as? List<*>)?.size ?: "?"}项]" else "$key=${value.toString().take(200)}"
            }
            log("AIRPLAY: /info 字段: $summary")
            (root["displays"] as? List<*>)?.forEachIndexed { index, d ->
                log("AIRPLAY: /info displays[$index]=$d")
            }
            for (hidKey in listOf("hidDevices", "hids", "hid")) {
                (root[hidKey] as? List<*>)?.forEachIndexed { index, h ->
                    log("AIRPLAY: /info $hidKey[$index]=$h")
                    if (h is Map<*, *>) {
                        // 奥迪实测：uuid 是字符串 "0"/"1"（13:38 诊断 uuidClass=String）；
                        // DiPlay 是整数。统一转字符串，hidSetInputMode/hidSendReport 原样回传
                        val uuid = when (val raw = h["uuid"]) {
                            is Number -> raw.toString()
                            is String -> raw
                            else -> null
                        }
                        if (uuid != null) parsedHidUuids += uuid
                        (h["hidDescriptor"] as? ByteArray)?.let { descriptor ->
                            log(
                                "AIRPLAY: hidDescriptor[$index] ${descriptor.size}B hex=" +
                                    descriptor.take(96).joinToString(" ") { "%02x".format(it) },
                            )
                        }
                    }
                }
            }
        }
        log("AIRPLAY: hidDevices 解析结果 uuids=$parsedHidUuids")
        val info = SetupInfo(
            eventPort = portOf(root["eventPort"]),
            timingPort = portOf(root["timingPort"]),
            keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
            enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
            displayWidth = ((display?.get("widthPixels") as? Long) ?: 1080L).toInt(),
            displayHeight = ((display?.get("heightPixels") as? Long) ?: 2320L).toInt(),
            // /info displays[0] 的显示屏 UUID（真实 iPhone 会把它回填进屏幕流 SETUP；
            // 键名以小写 "uuid" 为准，大写 "UUID" 兜底）
            displayUuid = (display?.get("uuid") ?: display?.get("UUID"))?.toString(),
            maxFps = ((display?.get("maxFPS") as? Long) ?: 30L).toInt(),
            hidDeviceUuids = parsedHidUuids,
        )
        log(
            "AIRPLAY: ✓ SETUP 完成 eventPort=${info.eventPort} timingPort=${info.timingPort} " +
                "keepAlive=${info.keepAlivePort ?: "无"} features=${info.enabledFeatures} " +
                "display=${info.displayWidth}x${info.displayHeight}@${info.maxFps} " +
                "displayUuid=${info.displayUuid}",
        )
        return info
    }

    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
        /** 主显示屏参数（/info displays[0]，跟随接收端当前屏幕方向）。 */
        val displayWidth: Int,
        val displayHeight: Int,
        val displayUuid: String?,
        val maxFps: Int,
        /** /info hidDevices[].uuid 列表（0=触摸、1=旋钮；DiPlay 同款编号）。 */
        val hidDeviceUuids: List<String> = emptyList(),
    )

    /** 屏幕流协商结果。 */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val streamKey: ByteArray,
    )

    /**
     * iAP 隧道（type-130 DataStream，对照 DiPlay IapTunnel）：现代 CarPlay 的 iAP2
     * EA 数据通道——**车机→手机的 iAP2 消息（含触摸 HID）走这条加密 TCP**，
     * 手机→车机走事件通道 iAPSendMessage 命令（[sendIap]）。
     * 线格式：ChaCha 帧（2B LE 长度作 AAD + 密文 + 16B tag，nonce=8B LE 计数器，
     * 密钥 = HKDF(shared, "DataStream-Salt$seed", "DataStream-Output-Encryption-Key")）
     * ⊃ APTransportPackage（4B BE 总长，32B 头，偏移 16 处 type="comm"）→ body = iAP2 消息。
     */
    inner class IapTunnelClient(
        val socket: Socket,
        private val readKey: ByteArray,
        private val logPrefix: String = "TUNNEL",
        private val writeKey: ByteArray = readKey,
    ) {
        @Volatile
        var messageCount = 0
            private set

        /** 车机→手机 iAP2 消息回调（CSM 帧字节）。 */
        var onIapMessage: ((com.lizhihua.carplaysender.iap2.Iap2Csm.Frame) -> Unit)? = null

        private val parser = com.lizhihua.carplaysender.iap2.Iap2Csm.Parser()

        private val writeLock = Any()
        private var writeCounter = 0L
        private var writeFailureLogged = false

        /**
         * 手机→接收端方向（隧道）写入：DiPlay 的隧道链路引擎**只从 TCP socket 读**
         * （AirPlayIapTunnelStream 的 inbound=IapTunnel/TCP，outbound=事件通道），
         * 因此发送端必须把 iAP2 链路字节以「ChaCha 帧（2B LE 长度作 AAD + 密文 + 16B tag）
         * ⊃ APTransportPackage（4B BE 总长、偏移 16 处 type="comm"）」写进该 socket。
         * 密钥与读取方向同源（OUTPUT 标签 = 手机→接收端方向，2026-08 对照源码确认）。
         * 返回是否写出（socket 关闭/异常返回 false，调用方仅记录一次）。
         */
        fun writeIapBytes(bytes: ByteArray): Boolean {
            if (socket.isClosed || !socket.isConnected || bytes.isEmpty()) return false
            return try {
                val size = TUNNEL_PACKAGE_HEADER_BYTES + bytes.size
                val plain = ByteArray(size)
                plain[0] = (size ushr 24).toByte()
                plain[1] = (size ushr 16).toByte()
                plain[2] = (size ushr 8).toByte()
                plain[3] = size.toByte()
                plain[16] = 'c'.code.toByte()
                plain[17] = 'o'.code.toByte()
                plain[18] = 'm'.code.toByte()
                plain[19] = 'm'.code.toByte()
                bytes.copyInto(plain, TUNNEL_PACKAGE_HEADER_BYTES)
                val aad = byteArrayOf(
                    (size and 0xFF).toByte(),
                    ((size ushr 8) and 0xFF).toByte(),
                )
                // 计数器自增必须与写 socket 同锁：ChaCha nonce 乱序 = 对端解密失败
                synchronized(writeLock) {
                    val sealed = AirPlayCrypto.chachaSeal(
                        writeKey,
                        AirPlayCrypto.nonce64(writeCounter++),
                        plain,
                        aad,
                    )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                writeFailureLogged = false
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: 写出失败 - ${error.message}")
                }
                false
            }
        }

        fun startReading() {
            Thread({
                val input = socket.getInputStream()
                var pending = ByteArray(0)
                var plaintext = ByteArray(0)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (socket.isClosed == false) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        pending += buffer.copyOf(count)
                        val decrypted = decryptFrames(pending)
                        pending = decrypted.second
                        plaintext += decrypted.first
                        plaintext = parsePackages(plaintext)
                    }
                    log("$logPrefix: 通道关闭（EOF），共 $messageCount 条 iAP2 消息")
                } catch (error: IOException) {
                    log("$logPrefix: 读取结束 - ${error.message}")
                }
            }, "iap-tunnel-reader").apply {
                isDaemon = true
                start()
            }
        }

        /** ChaCha 帧解密（与 ControlCipher 同帧格式，独立计数器/密钥）。 */
        private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (buffer.size - offset >= 2) {
                val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
                val frameLength = 2 + length + 16
                if (buffer.size - offset < frameLength) break
                val aad = buffer.copyOfRange(offset, offset + 2)
                val sealed = buffer.copyOfRange(offset + 2, offset + frameLength)
                val plain = AirPlayCrypto.chachaOpen(
                    readKey,
                    AirPlayCrypto.nonce64(tunnelReadCounter++),
                    sealed,
                    aad,
                )
                output += plain
                offset += frameLength
            }
            val rest = buffer.copyOfRange(offset, buffer.size)
            return if (output.isEmpty()) ByteArray(0) to rest else output.reduce { a, b -> a + b } to rest
        }

        /** APTransportPackage 解析：4B BE 总长，32B 头，偏移 16 type="comm" → body=iAP2。 */
        private fun parsePackages(buffer: ByteArray): ByteArray {
            var offset = 0
            while (buffer.size - offset >= 32) {
                val size = ((buffer[offset].toInt() and 0xFF) shl 24) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                    (buffer[offset + 3].toInt() and 0xFF)
                if (size < 32 || buffer.size - offset < size) break
                val messageType = String(buffer, offset + 16, 4, Charsets.US_ASCII)
                if (messageType == "comm") {
                    val body = buffer.copyOfRange(offset + 32, offset + size)
                    onIapBytes(body)
                }
                offset += size
            }
            return buffer.copyOfRange(offset, buffer.size)
        }

        private fun onIapBytes(bytes: ByteArray) {
            messageCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                val hex = bytes.joinToString(" ") { "%02x".format(it) }
                if (messageCount <= 20) {
                    log(
                        "$logPrefix: iAP2 msg #${messageCount} id=0x${frame.messageId.toString(16)} " +
                            "(${com.lizhihua.carplaysender.iap2.Iap2MessageIds.nameOf(frame.messageId)}) body=${frame.body.size}B " +
                            "hex=${hex.take(160)}",
                    )
                } else if (messageCount % 100 == 0) {
                    log("$logPrefix: iAP2 msg #${messageCount} id=0x${frame.messageId.toString(16)} body=${frame.body.size}B")
                }
                onIapMessage?.invoke(frame)
            }
        }

        private var tunnelReadCounter = 0L
    }

    /**
     * SETUP type-130 iAP 隧道并连接车机 dataPort。失败返回 null（会话继续，触摸不可用）。
     * 手机→车机方向用 [sendIap]（事件通道 iAPSendMessage）。
     */
    fun runIapTunnel(sharedSecret: ByteArray): IapTunnelClient? {
        val host = ipv4Host() ?: return null
        val seed = (System.currentTimeMillis() % 0x7FFF_FFFFL) + 1L
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val body = BinaryPlist.encode(
            linkedMapOf(
                "streams" to listOf(
                    linkedMapOf<String, Any?>(
                        "type" to 130L,
                        "clientTypeUUID" to IAP_DATASTREAM_UUID,
                        "seed" to seed,
                        "streamConnectionID" to connectionId,
                    ),
                ),
            ),
        )
        val (statusLine, responseBody) = try {
            request("SETUP", "/", body, contentType = "application/x-apple-binary-plist")
        } catch (error: IOException) {
            log("TUNNEL: SETUP 失败 - ${error.message}")
            return null
        }
        if (!statusLine.contains("200")) {
            log("TUNNEL: SETUP 应答 [$statusLine]（响应头：${lastResponseHead.replace("\r\n", " | ")}）")
            return null
        }
        val root = try {
            BinaryPlist.parse(responseBody) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: run {
            log("TUNNEL: SETUP 应答解析失败")
            return null
        }
        val streams = root["streams"] as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        val dataPort = ((stream?.get("dataPort") as? Long) ?: 0L).toInt() and 0xFFFF
        if (dataPort == 0) {
            log("TUNNEL: SETUP 应答无 dataPort（streams=$streams）")
            return null
        }
        val key = AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )
        val tcp = try {
            Socket()
        } catch (error: IOException) {
            return null
        }
        return try {
            tcp.tcpNoDelay = true
            tcp.connect(java.net.InetSocketAddress(host, dataPort), 5_000)
            log("TUNNEL: ✓ iAP 隧道已建立 $host:$dataPort（seed=$seed）")
            val client = IapTunnelClient(tcp, key)
            client.startReading()
            iapTunnel = client
            client
        } catch (error: IOException) {
            log("TUNNEL: TCP 连接失败 - ${error.message}")
            runCatching { tcp.close() }
            null
        }
    }

    /**
     * 隧道客户端引用（DiPlay 形态）：事件通道虚拟链路（[IapLinkOverEvent]）的**出站方向**
     * 必须写进 type-130 TCP（DiPlay 的隧道链路引擎只从 TCP 读；其出站走事件通道，
     * 与我们被动接收一致）。无隧道（Audi 事件通道形态）时出站继续走事件通道 [sendIap]。
     */
    @Volatile
    private var iapTunnel: IapTunnelClient? = null

    private var sendIapCount = 0

    /**
     * 手机→车机 iAP2 消息：事件通道 iAPSendMessage 命令（对照 DiPlay sendIapMessage，
     * params 信封 + data 字节，catplay CommandIApSendMessage 同款）。
     */
    fun sendIap(data: ByteArray): Boolean {
        val sent = postEventCommand(
            linkedMapOf(
                "type" to "iAPSendMessage",
                "params" to linkedMapOf<String, Any?>("data" to data),
            ),
        )
        sendIapCount++
        if (sendIapCount <= 10) {
            log(
                "IAPLINK: tx(事件通道) ${data.size}B ok=$sent hex=" +
                    data.joinToString(" ") { "%02x".format(it) }.take(120),
            )
        }
        return sent
    }

    // ---- 事件通道上的虚拟 iAP2 链路 ----
    //
    // 奥迪实测（2026-10-11）：车机通过 iAPSendMessage 发来 iAP2 链路层 marker
    // （ff 55 02 00 ee 10）——它在事件通道上开了一条虚拟 iAP2 链路（type-130 TCP
    // 被车机拒绝：200 但无 dataPort）。EA 数据（触摸 HID）走这条链路。
    // 复用 [com.lizhihua.carplaysender.iap2.Iap2LinkEngine] 完成握手与帧收发。

    @Volatile
    private var iapLink: IapLinkOverEvent? = null

    /** 车机 iAPSendMessage 字节入口（MainActivity 事件命令处理器调用）。 */
    fun onIapEventBytes(data: ByteArray) {
        val link = iapLink ?: synchronized(this) {
            iapLink ?: IapLinkOverEvent().also {
                it.begin()
                iapLink = it
            }
        }
        link.onCarBytes(data)
    }

    inner class IapLinkOverEvent {
        private val lock = Any()
        private val engine = com.lizhihua.carplaysender.iap2.Iap2LinkEngine()
        private val parser = com.lizhihua.carplaysender.iap2.Iap2Csm.Parser()
        private var frameCount = 0
        private var eaCount = 0
        private var started = false
        private var detectReplies = 0

        /** 隧道发送端 CSM 握手进度（仅在隧道存在时推进；Audi 事件通道形态不参与）。 */
        private var tunnelCsmStarted = false
        private var tunnelIdentified = false

        /** 同步回显待投递（隧道门闸：回显真正写进隧道 TCP 后才允许发 0x1D00）。 */
        private var pendingEchoFlush = false
        private var tunnelEchoDelivered = false

        init {
            engine.onEaData = { bytes -> handleEaData(bytes) }
            engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
            // 节拍线程：驱动引擎计时器（重传等）
            Thread({
                while (socket?.isClosed == false) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(System.nanoTime() / 1_000_000L)
                        drainLocked()
                    }
                }
            }, "iap-link-pacer").apply {
                isDaemon = true
                start()
            }
        }

        fun begin() {
            synchronized(lock) {
                if (started) return
                started = true
                // DETECT 语义（catplay LinkLayer server 角色）：静默进协商，
                // 车机（客户端）DETECT 一次我们回一次，绝不主动重发
                engine.enterNegotiating()
                log("IAPLINK: 车机发来链路 marker（DETECT）——服务端模式待命，等车机 SYN")
            }
        }

        fun onCarBytes(bytes: ByteArray) {
            synchronized(lock) {
                // 隧道存在时切换「回显先行」协商语义（DiPlay 对端引擎硬性期望，详见 Iap2LinkEngine）
                engine.echoFirst = iapTunnel != null
                // 车机 DETECT：一问一答（catplay server 角色：收到即回、绝不主动）。
                // 引擎处于 NEGOTIATING（尚未见 SYN）时的裸 marker 都按 DETECT 对待
                if (bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() && bytes[1] == 0x55.toByte() &&
                    engine.state == com.lizhihua.carplaysender.iap2.Iap2LinkEngine.State.NEGOTIATING
                ) {
                    detectReplies++
                    log("IAPLINK: 回 DETECT #$detectReplies")
                    sendLinkBytes(com.lizhihua.carplaysender.iap2.Iap2LinkEngine.MARKER)
                }
                engine.feed(bytes, System.nanoTime() / 1_000_000L)
                drainLocked()
            }
        }

        /** 链路字节出站：有隧道走隧道 TCP（DiPlay），否则走事件通道（Audi）。 */
        private fun sendLinkBytes(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val tunnel = iapTunnel
            if (tunnel != null) {
                tunnel.writeIapBytes(bytes)
            } else {
                sendIap(bytes)
            }
        }

        /**
         * 引擎出站字节落盘：有隧道写隧道 TCP（成功且内容含同步回显时标记回显已送达），
         * 否则走事件通道（回显发往错误通道时作废投递标记，等对端 SYN 重发重新回显）。
         */
        private fun flushOutputLocked() {
            val out = engine.takeOutput()
            if (out.isEmpty()) return
            val tunnel = iapTunnel
            if (tunnel != null) {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ 同步回显已写入隧道（链路协商完成，发送端 CSM 就绪）")
                    }
                }
            } else {
                sendIap(out)
                pendingEchoFlush = false
            }
        }

        private fun drainLocked() {
            while (true) {
                when (val event = engine.pollEvent()) {
                    null -> break
                    is com.lizhihua.carplaysender.iap2.Iap2LinkEngine.Event.Control ->
                        handleFrameLocked(event.bytes)

                    is com.lizhihua.carplaysender.iap2.Iap2LinkEngine.Event.Dead -> {
                        log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                        return
                    }
                }
            }
            // 先冲刷（回显必须先行送达对端），再启动 CSM，最后再冲刷 CSM 字节
            flushOutputLocked()
            maybeStartTunnelCsmLocked()
            flushOutputLocked()
        }

        /**
         * 隧道发送端握手启动：链路协商完成（NORMAL）且同步回显已送达后，主动发
         * 0x1D00 StartIdentification。DiPlay 的隧道控制端（Iap2WirelessControlClient）
         * 在 awaitReady 返回后等待我们发 0x1D00；缺此步其 5 分钟窗口超时即拆掉
         * 整条链路（2026-10-11 实测根因）。回显送达门闸保证对端引擎先进入 NORMAL
         * （其对端在未收到 SYN 前收到任何裸 ACK 会直接 die）。
         */
        private fun maybeStartTunnelCsmLocked() {
            if (tunnelCsmStarted || iapTunnel == null || !tunnelEchoDelivered) return
            if (engine.state != com.lizhihua.carplaysender.iap2.Iap2LinkEngine.State.NORMAL) return
            tunnelCsmStarted = true
            sendCsmLocked(
                com.lizhihua.carplaysender.iap2.Iap2MessageIds.START_IDENTIFICATION,
            )
        }

        /** 隧道上的 CSM 发送（计入发送窗口由引擎 flush 到隧道 TCP）。 */
        private fun sendCsmLocked(messageId: Int, body: ByteArray = ByteArray(0)) {
            val frame = com.lizhihua.carplaysender.iap2.Iap2Csm.encode(messageId, body)
            val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
            log(
                "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                    "(${com.lizhihua.carplaysender.iap2.Iap2MessageIds.nameOf(messageId)}) " +
                    "${frame.size}B ok=$ok",
            )
        }

        /**
         * 隧道发送端握手（对照 DiPlay Iap2WirelessControlClient 的对端期望）：
         *   0x1D01 → 回 0x1D02（识别完成）→ 随发 0xAA00
         *   0xAA01 → 发 0xAA02 挑战（32B，DiPlay CARLINKIT 身份只接受精确 32B）
         *   0xAA03 → 发 0xAA05（自报成功）
         * 完成后对方发出订阅并视为隧道就绪，5 分钟拆链不再发生。
         */
        private fun maybeRespondTunnelCsmLocked(frame: com.lizhihua.carplaysender.iap2.Iap2Csm.Frame) {
            if (iapTunnel == null) return
            val ids = com.lizhihua.carplaysender.iap2.Iap2MessageIds
            when (frame.messageId) {
                ids.IDENTIFICATION_INFORMATION -> {
                    if (tunnelIdentified) return
                    tunnelIdentified = true
                    val params = com.lizhihua.carplaysender.iap2.Iap2Csm.parseParams(frame.body)
                        .associateBy { it.id }
                    log(
                        "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                            "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                            "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                    )
                    sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                    sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                }

                ids.MFI_CERTIFICATE -> {
                    val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, com.lizhihua.carplaysender.iap2.Iap2Csm.param(0, challenge))
                }

                ids.MFI_RESPONSE -> {
                    sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                    log("IAPLINK: 隧道 MFi 完成——等待对端订阅（随后其标记隧道就绪）")
                }
            }
        }

        private fun handleFrameLocked(bytes: ByteArray) {
            frameCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                if (frameCount <= 30 || frameCount % 100 == 0) {
                    log(
                        "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                            "(${com.lizhihua.carplaysender.iap2.Iap2MessageIds.nameOf(frame.messageId)}) " +
                            "${frame.body.size}B hex=${hex.take(160)}",
                    )
                }
                maybeRespondTunnelCsmLocked(frame)
            }
            if (frames.isEmpty() && frameCount <= 10) {
                log("IAPLINK: rx ${bytes.size}B（非 CSM）hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
            }
        }

        /** 引擎回调：EA 会话（2 号）数据帧——触摸 HID 的高概率载体。 */
        var onEaData: ((ByteArray) -> Unit)? = null

        fun handleEaData(bytes: ByteArray) {
            eaCount++
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            if (eaCount <= 30 || eaCount % 100 == 0) {
                log("IAPLINK: EA 数据 #$eaCount ${bytes.size}B hex=${hex.take(200)}")
            }
            onEaData?.invoke(bytes)
        }
    }

    /** 音频流协商结果（RTP over UDP；密钥同视频流规则，盐用音频连接 ID）。 */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
    )

    /** plist 端口是 u16 但以 2 字节有符号整数编码（>32767 读出为负），按位掩出真实值。 */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    private companion object {
        /** 主屏视频流类型（对照接收端 STREAM_TYPE_MAIN_SCREEN；100 是音频、130 是数据/iAP 隧道）。 */
        const val SCREEN_STREAM_TYPE = 110L

        /** 主音频流类型（DiPlay STREAM_TYPE_MAIN_AUDIO；媒体/导航音频走这条）。 */
        const val AUDIO_STREAM_TYPE = 100L

        /** 音频格式位：PCM 48000Hz 16bit 立体声（catplay AudioFormat 定义 1<<15）。 */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /** AAC-LC 48000Hz 立体声（1<<23），/info 偏好时使用；当前仅记录。 */
        const val AUDIO_FORMAT_AAC_48K_STEREO = 1L shl 23

        /**
         * SETUP 里的 iPhone 能力清单（接收端按「车机支持 ∩ 提议」计算 enabledFeatures，
         * 缺失的能力整个功能被关——2026-10-11 奥迪实测：videoPlayback 单独提议时
         * 视频管线未启用、屏幕流 400）。清单对照 catplay 真车发送端。
         */
        val CONTROLLER_FEATURES = listOf(
            "uiContext",
            "viewAreas",
            "cornerMasks",
            "focusTransfer",
            "h.264Level5.1",
            "mainBuffered",
            "altScreen",
            "enhancedSiri",
            "hevc",
            "sessionManagement",
            "logTransfer",
            "iAPChannel",
            "vehicleStateProtocol",
            "videoPlayback",
        )

        /** 等车机打开 AirPlay 窗口的总时长（用户在车机上点手机图标的等待窗口）。 */
        private const val PAIRING_WINDOW_MILLIS = 120_000L

        /** 发送端自报的 AirPlay 版本（catplay 真车验证值，iOS 15.0.2 时代；勿用车机的版本）。 */
        private const val SENDER_SOURCE_VERSION = "566.25.21"

        /** 屏幕流延迟（catplay c2a car_manager 实测值）。 */
        private const val SCREEN_LATENCY_MS = 25L

        /** iAP DataStream 的 clientTypeUUID（DiPlay IAP_DATASTREAM_UUID，真 iPhone 同款）。 */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** 隧道 APTransportPackage 头长（4B BE 总长 + 12B 保留 + 4B type="comm" + 12B 保留）。 */
        private const val TUNNEL_PACKAGE_HEADER_BYTES = 32
    }

    /**
     * SETUP：协商主音频流（type 100，RTP over UDP）。
     *
     * 线格式与视频同族但传输不同：接收端在应答里开 **UDP** dataPort/controlPort，
     * 手机把 PCM 帧按 [RtpAudioSender] 的格式发过去。密钥 = HKDF(shared,
     * "DataStream-Salt<音频 connId>", "DataStream-Output-Encryption-Key")。
     *
     * 格式选择：优先看 /info 的 audioFormats（真车按能力取舍），
     * 拿不到则默认 PCM 48k 立体声（协议基线，接收端普遍支持）。
     */
    fun runAudioStream(sharedSecret: ByteArray): AudioStreamInfo? {
        val connectionId = System.currentTimeMillis() % 100_000L + 4242L
        // /info 里车机声明支持的音频格式（仅日志/选择参考；失败不影响）
        val advertisedFormats = fetchAudioFormats()
        val wantPcm = advertisedFormats.isEmpty() ||
            advertisedFormats.any { (it and AUDIO_FORMAT_PCM_48K_STEREO) != 0L }
        if (!wantPcm) {
            log(
                "AIRPLAY: 车机未声明 PCM 48k 立体声（audioFormats=" +
                    advertisedFormats.joinToString { "0x%x".format(it) } +
                    "），暂用 PCM 尝试（AAC 编码待实现）",
            )
        }
        val body = BinaryPlist.encode(
            linkedMapOf(
                "streams" to listOf(
                    linkedMapOf<String, Any?>(
                        "type" to AUDIO_STREAM_TYPE,
                        "streamConnectionID" to connectionId,
                        "audioFormat" to AUDIO_FORMAT_PCM_48K_STEREO,
                        "audioType" to "media",
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
            log("AIRPLAY: 音频流协商失败 - ${error.message}")
            return null
        }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: 音频流协商应答 [$statusLine]")
            return null
        }
        val root = try {
            BinaryPlist.parse(responseBody) as? Map<*, *>
        } catch (error: IllegalArgumentException) {
            null
        } ?: run {
            log("AIRPLAY: 音频流协商应答解析失败")
            return null
        }
        val streams = root["streams"] as? List<*> ?: run {
            log("AIRPLAY: 音频流协商应答缺少 streams")
            return null
        }
        val audio = streams.firstOrNull { entry ->
            ((entry as? Map<*, *>)?.get("type") as? Long) == AUDIO_STREAM_TYPE
        } as? Map<*, *> ?: run {
            log("AIRPLAY: 音频流被拒绝（streams=$streams）")
            return null
        }
        val dataPort = portOf(audio["dataPort"])
        if (dataPort == 0) {
            log("AIRPLAY: 音频流未返回 dataPort（$audio）")
            return null
        }
        val streamKey = AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )
        log(
            "AIRPLAY: ✓ 音频流协商成功（type=100 PCM48k）dataPort=$dataPort " +
                "controlPort=${portOf(audio["controlPort"])} connId=$connectionId",
        )
        return AudioStreamInfo(
            streamConnectionId = connectionId,
            dataPort = dataPort,
            controlPort = portOf(audio["controlPort"]),
            streamKey = streamKey,
        )
    }

    /** 读 /info 的 audioFormats（车机声明的可接受音频编码位图）；失败返回空表。 */
    private fun fetchAudioFormats(): List<Long> = try {
        val (statusLine, body) = request("GET", "/info", ByteArray(0), 3_000)
        if (!statusLine.contains("200") || body.isEmpty()) {
            emptyList()
        } else {
            val root = BinaryPlist.parse(body) as? Map<*, *>
            (root?.get("audioFormats") as? List<*>)
                ?.mapNotNull { (it as? Long) }
                .orEmpty()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * SETUP #2：协商主屏视频流（type 100）。streamConnectionID 由客户端指定，
     * 两端各自用 HKDF(共享密钥, "DataStream-Salt<id>", "DataStream-Output-Encryption-Key")
     * 推导同一把流密钥；接收端开 TCP 数据端口并在应答里返回。
     */
    fun runScreenStream(sharedSecret: ByteArray, displayUuid: String? = null): ScreenStreamInfo? {
        // 对照 catplay（真车验证）：streamConnectionID = 随机 u64（+1 保证非零）
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val body = BinaryPlist.encode(
            linkedMapOf(
                "streams" to listOf(
                    linkedMapOf<String, Any?>(
                        "type" to SCREEN_STREAM_TYPE, // 110 = 主屏（100 是音频，DiPlay 会开 UDP 端口）
                        "streamConnectionID" to connectionId,
                        // 真实 iPhone / catplay 发送端：uuid 与 latencyMs 都必带。
                        // uuid 必须是接收端 /info displays[] 声明的显示屏 UUID（真车会校验；
                        // 随机值曾致 Audi 直接 400 拆会话）。latencyMs=25 为 catplay 实测值。
                        "uuid" to (displayUuid ?: java.util.UUID.randomUUID().toString()),
                        "latencyMs" to SCREEN_LATENCY_MS,
                    ),
                ),
            ),
        )
        // 请求路径与 catplay 完全一致：流 SETUP 走 "/"（初始 SETUP 走 "/setup"）。
        // 部分接收端（Audi 固件）对路径敏感，路径不符会整包 400。
        val (statusLine, responseBody) = try {
            request(
                "SETUP",
                "/",
                body,
                contentType = "application/x-apple-binary-plist",
            )
        } catch (error: IOException) {
            log("AIRPLAY: 流协商失败 - ${error.message}")
            return null
        }
        if (!statusLine.contains("200")) {
            log(
                "AIRPLAY: 流协商应答 [$statusLine]（响应头：${lastResponseHead.replace("\r\n", " | ")}）\n" +
                    "  ↳ 请求体(${body.size}B)=" + body.joinToString("") { "%02x".format(it) },
            )
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
            registerEventCommandChannel(socket, cipher)
            Thread({
                val input = socket.getInputStream()
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = try {
                            input.read(buffer)
                        } catch (_: java.net.SocketTimeoutException) {
                            // 30s 空闲是正常状态（事件通道低频）：仅作唤醒节拍，绝不能退出——
                            // 读取线程退出会被接收端视作事件连接断开并拆除整个会话（含视频/蓝牙）
                            continue
                        }
                        if (count < 0) {
                            log("EVENT: 通道关闭（EOF）")
                            break
                        }
                        pending += buffer.copyOf(count)
                        val decrypted = cipher.decrypt(pending)
                        pending = decrypted.rest
                        var eventOtherLogged = 0
                        for (message in accumulator.offer(decrypted.data)) {
                            val firstLine = message.firstLine
                            // (a) 状态行（"RTSP/1.0 200 OK" 等）= 车机对**我们命令**的应答：
                            //     静默跳过——不回、不记录（回应答会触发乒乓回声，见 respondEventRequest）
                            if (firstLine.startsWith("RTSP/") || firstLine.startsWith("HTTP/")) continue
                            // (b) 请求判定：方法 token + "/"-开头路径。畸形行（如 "OK 200 OK"：
                            //     第 2 token 是数字）判为非请求 → 静默丢弃，不回不记
                            val tokens = firstLine.split(" ")
                            val isRequest = tokens.size >= 2 && tokens[1].startsWith("/")
                            if (!isRequest) continue
                            // 车机请求：立即回 200（echo 请求 CSeq）；/command 走分发，其余节流留痕
                            val cseq = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                .find(message.head)?.groupValues?.get(1)
                            respondEventRequest(cseq)
                            if (!firstLine.contains("/command")) {
                                unknownEventRequests++
                                if (unknownEventRequests <= 10) {
                                    log("EVENT: 未知请求 [$firstLine] body=${message.body.size}B")
                                }
                                continue
                            }
                            val command = try {
                                BinaryPlist.parse(message.body) as? Map<*, *>
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                            if (command == null) {
                                log("EVENT: 命令 plist 解析失败 [$firstLine] body=${message.body.size}B")
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
        /** 完整报文：首行（分类用）、头部全文（提取 CSeq）、body。 */
        class Message(val firstLine: String, val head: String, val body: ByteArray)

        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Message> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Message>()
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
                messages += Message(
                    firstLine,
                    headText,
                    buffer.copyOfRange(bodyStart, bodyStart + contentLength),
                )
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

    /**
     * 配对请求（pair-setup / pair-verify）：Apple 客户端与 catplay PairingHelperTx 同款头。
     * carplay 真车实测（catplay TX 方向工作于真实车机）：
     *  - Content-Type: application/pairing+tlv8
     *  - X-Apple-HKP: 2（HomeKit 配对 v2，Apple 客户端的标识头）
     *  - X-Apple-PD: 1（Pair-Derive，pair-verify 专用）
     * 2026-10-11：此前发默认 application/octet-stream 且无这两个头，奥迪对
     * pair-setup/pair-verify 全部回 403（HTTP 层拒绝，未进入 TLV 解析）。
     */
    private fun postPairing(path: String, body: ByteArray): ByteArray {
        val extra = buildList {
            add("X-Apple-HKP" to "2")
            if (path.endsWith("pair-verify")) add("X-Apple-PD" to "1")
        }
        val (statusLine, responseBody) = request(
            "POST",
            path,
            body,
            contentType = "application/pairing+tlv8",
            extraHeaders = extra,
        )
        // 诊断（真车 403 排查）：请求长度 + 应答状态行/长度/头部字节
        val headHex = responseBody.take(16).joinToString(" ") { "%02x".format(it) }
        log(
            "AIRPLAY: $path req=${body.size}B -> [$statusLine] resp=${responseBody.size}B" +
                if (responseBody.isNotEmpty()) " head=[$headHex]" else "",
        )
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
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Pair<String, ByteArray> = synchronized(rtspLock) {
        requestLocked(method, path, body, timeoutMillis, contentType, extraHeaders)
    }

    /** RTSP 事务锁：feedback 心跳线程与配对/事件线程共用同一条连接，必须串行。 */
    private val rtspLock = Any()

    private fun requestLocked(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int,
        contentType: String,
        extraHeaders: List<Pair<String, String>>,
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("未连接")
        client.soTimeout = timeoutMillis
        val head = "$method $path RTSP/1.0\r\n" +
            "CSeq: ${++cSeq}\r\n" +
            "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
            "Content-Type: $contentType\r\n" +
            extraHeaders.joinToString("") { "${it.first}: ${it.second}\r\n" } +
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

    /** 最近一次 RTSP 响应头（失败诊断：真车 400 时可能有说明字段）。 */
    private var lastResponseHead: String = ""

    /** 完整 RTSP 报文（头 + Content-Length body）才返回；残留部分写入 leftover。 */
    private fun parseCompleteMessage(buffer: ByteArray): Pair<String, ByteArray>? {
        val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val separatorIndex = indexOfSeparator(buffer, separator)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        lastResponseHead = headText
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
