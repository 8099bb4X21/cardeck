package com.lizhihua.carplaysender.iap2

import com.lizhihua.carplaysender.transport.Iap2LinkChannel
import java.io.IOException
import java.security.SecureRandom

/**
 * iAP2 发送端（iPhone 角色）控制会话：完整走通无线 CarPlay 的 bring-up 序列，
 * 最终从 0x5703 / 0x4301 解析出接收端热点与 AirPlay 端点 —— Phase 1 的 Go/No-Go 交付。
 *
 * 序列（对照 reference/xcertplay Iap2WirelessControlClient 的对端行为）：
 *   0x1D00 → 0x1D01 → 0x1D02（识别）
 *   0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05（MFi，跳过证书/签名校验）
 *   [配件发来 5 个订阅帧 + 0x6800 StartHID，回 0x6801]
 *   0x5702 → 0x5703（拿热点 SSID/密码/信道）
 *   0x4300 → 0x4301（拿 AirPlay 端口/公钥/无线参数）
 *   0x4E0D + 0x4E0E（通知无线可用与传输标识）
 */
class Iap2SenderSession(
    private val channel: Iap2LinkChannel,
    private val log: (String) -> Unit,
    /** 本机真实蓝牙 MAC（0x4300/0x4E0E 的传输标识与 _carplay-ctrl 广播的 id；真车会校验）。 */
    private val bluetoothMac: String = "02:00:00:00:00:00",
    /** 本机蓝牙名（0x4E09 设备信息）。 */
    private val deviceName: String = "iPhone",
    /**
     * 收到 0x5703（热点凭据）时回调 —— 主控须立即：绑定车机 WiFi + 广播
     * `_carplay-ctrl._tcp`（带真实蓝牙 MAC）。真车（Audi MIB3）流程：
     * 车机在 WiFi 上发现该服务并 `GET /ctrl-int/1/connect` 邀请手机，
     * **邀请被接受后才发 0x4301**；若等到 0x4301 之后才广播则双方互等死锁。
     */
    private val onWifiCredentials: ((WifiConfiguration) -> Unit)? = null,
) {
    /** 从 0x5703 + 0x4301 解析出的接收端信息。 */
    data class CarPlayEndpoint(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
        val airPlayPort: Int,
        val deviceIdentifier: String?,
        val publicKey: String?,
        val sourceVersion: String?,
        val ipAddresses: List<String>,
    )

    private val parser = Iap2Csm.Parser()

    /** 刚解析、尚未分类的帧（到达顺序）。 */
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /**
     * [expectOrNull] 等待期间取到的「非预期」帧暂存区；[recvFrame] 优先从这里取。
     *
     * **必须与 [receivedFrames] 分开**：两者若混用，expectOrNull 把非目标帧塞回
     * 它自己下一轮优先读取的队列，就会同一帧反复取出—记录—塞回形成活锁
     * （2026-10-18 实测：车机 bring-up 期间发 0x5000 StartNowPlayingUpdates，
     * 日志以 ~1000 行/秒刷屏直到等待超时）。
     */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 暂存区上限：车机订阅类消息可能在长等待期间持续到达，无上限会吃内存。 */
    private val maxPendingFrames = 256
    private var pendingOverflowLogged = false

    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期间车机重发 0x5702 时需重新应答）。 */
    @Volatile
    private var wifi: WifiConfiguration? = null

    /**
     * 0x1D01 参数 6：车机声明「它可发送」的消息 ID 清单。
     * 用它判断 0x6800 (StartHID) / 0x4301 是否真的会来——声明里没有就不该盲等：
     * 奥迪实测只声明 0x5703/0xea03，旧流程因此在 0x4301 上空等 10s、StartHID 上空等 2s。
     */
    private var accessorySendIds: Set<Int> = emptySet()

    /** 车机是否声明会发某消息；null = 未提供清单（未知，按保守策略等待）。 */
    private fun accessoryDeclaresSend(messageId: Int): Boolean? = if (accessorySendIds.isEmpty()) null else messageId in accessorySendIds

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /**
         * 等 0x4301 的短窗口（声明会发的测试台毫秒级应答；没声明的车机直接跳过）。
         * 只在「车机没给声明清单」时才会用满窗口——从 10s 收到 3s：真车整个
         * bring-up 只有几十秒预算，且实测应答都在毫秒级。
         */
        const val START_SESSION_WAIT_NANOS = 3_000_000_000L

        /**
         * MFi 挑战「被拒档位」进程级记忆：只记录**导致链路被拆**的挑战长度
         * （观察到车机对某个长度不容忍；静默超时不记，属可重试的正常失败）。
         * 2026-10-11 奥迪实测新增：车机在 32B 挑战后 ~0.4s 直接拆链
         * （10-09 观察到的是「静默不断链」，两种行为都出现过）——若无记忆，
         * 每次会话都从 32B 撞起、反复断链；有记忆则本次进程内后续会话直接
         * 从下一档开始（DiPlay 在 32B 秒答，不受 32 被阴影观察影响；
         * 若两档都被拒则清空重来，避免永久卡死）。
         */
        @Volatile
        private var mfiRejectedLengths: Set<Int> = emptySet()

        /** 挑战档位（长度, 等待窗口）：顺序与窗口由 DiPlay/Audi 双端实测决定。 */
        private val MFI_ATTEMPTS = listOf(32 to 3_000L, 20 to 6_000L, 16 to 3_000L)

        /** 真车系接收端特征（0x1D01 name/model/manufacturer 子串，小写比对）。 */
        private val LEGACY_RECEIVER_MARKERS = listOf("audi", "delphi", "mib", "harman", "porsche")
    }

    /** 从 0x1D01 提取接收端身份文本（name/model/manufacturer 小写拼接）——MFi 挑战长度选择用。 */
    private fun identityText(frame: Iap2Csm.Frame): String {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return listOfNotNull(
            params[0]?.value?.asStringOrEmpty(),
            params[1]?.value?.asStringOrEmpty(),
            params[2]?.value?.asStringOrEmpty(),
        ).joinToString(" ").lowercase()
    }

    fun run(timeoutMillis: Long = 60_000L): CarPlayEndpoint {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("iAP2 链路协商超时")
        }
        log("iap2 link ready peerMaxControl=${channel.peerMaxControlPayload()}")

        // ---- 识别 ----
        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        logIdentification(identification)
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)
        log("iap2 tx=0x1d02 identification-accepted")

        // EA 协议解析（启动时机见下方 MFi 完成后——2026-10-11 DiPlay 实测：
        // 认证前发 0xEA00 会让测试台状态机停摆，不再回 0xAA01；奥迪对此不敏感）
        val eaProtocols = parseEaProtocols(identification)

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        val identityText = identityText(identification)
        val legacyCar = LEGACY_RECEIVER_MARKERS.any { identityText.contains(it) }
        if (legacyCar) {
            log("iap2 识别为真车系（$identityText）——MFi 首选 20B（真 iPhone 实录长度；32B 对真车处置不稳定）")
        }
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        // 挑战长度按接收端身份选择（2026-10-11 奥迪字节级实测修正）：
        //  - 真车系（Audi/Delphi MIB 等）：**20B 首试**。真 iPhone 实录（carplay-wifi-extractor
        //    client）与 catplay 发送端都用 20B；奥迪对 20B 秒答。32B 先行会导致车机
        //    ACK 挑战帧后**整段静默**（不回 ACK、不回应答，会话变僵尸）——本日多轮实测，
        //    32B→20B 的会话全部卡死，20B→（记忆命中）的会话全部秒通。
        //  - 测试台 DiPlay（Carlinkit AuthV3 EC 身份）：**只接受精确 32B**，长度不符直接断链。
        // 全部无应答也不中止——我们不校验签名（0xAA05 自报），由 0x5702 是否应答判定放行。
        runMfiChallenge(deadline, prefer20First = legacyCar)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

        // ---- EA 会话启动（MFi 认证完成后）----
        // 奥迪 0x1D01 声明 de.audi.mmicconnectdatatransfer / com.audi.cpp.eadata0/1
        // 三个 EA 协议；触摸 HID 相关数据经 EA 会话。认证前发送会噎死 DiPlay（见上）。
        for ((protocolId, name) in eaProtocols) {
            sendFireAndForget(
                Iap2MessageIds.EA_OPEN_SESSION,
                Iap2Csm.u8(0, protocolId) + Iap2Csm.u16(1, protocolId),
            )
            log("iap2 tx=0xea00 startEASession protocol=$protocolId name=$name")
        }

        // ---- 订阅与 HID：0x6800 是可选步骤 ----
        // xcertplay 在订阅后发 StartHID；DiPlay 删掉了这一步（订阅完直接等 0x5702）。
        // 因此这里只短暂等待：收到 0x6800 就应答 0x6801，2 秒内没等到就直接继续。
        awaitOptionalHid(deadline)

        // ---- WiFi 配置交换 ----
        send(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
        val wifiConfiguration = expect(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION, deadline)
        val wifi = parseWifiConfiguration(wifiConfiguration)
        log(
            "iap2 rx=0x5703 ssid=${wifi.ssid} channel=${wifi.channel} " +
                "security=${wifi.securityType} psk=${if (wifi.passphrase.isEmpty()) "(空)" else "***"}",
        )
        // 真车死锁解药（catplay CarPlayInviter 实证）：车机在 WiFi 网络上发现手机的
        // _carplay-ctrl._tcp 后主动发 GET /ctrl-int/1/connect 邀请，**邀请成功才发 0x4301**。
        // 因此这里必须立即通知主控绑 WiFi + 广播 ctrl（带真实蓝牙 MAC 作 TXT id）。
        onWifiCredentials?.invoke(wifi)

        // ---- 设备状态问候（对照真 iPhone 实录：0x4E0D/0x4E0A/0x4E09/0x4E0B → 0x4300）----
        // Audi MIB3 实测：缺这批消息时，车机对 0x4300 只回链路 ACK、不出 0x4301。
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh-CN"))
        send(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, deviceName))
        val epochSeconds = System.currentTimeMillis() / 1000L
        val timezoneMinutes =
            java.util.TimeZone
                .getDefault()
                .getOffset(System.currentTimeMillis()) / 60_000
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(epochSeconds)) +
                Iap2Csm.u16(1, timezoneMinutes and 0xFFFF) +
                Iap2Csm.u8(2, 0),
        )
        send(
            Iap2MessageIds.DEVICE_UUID_UPDATE,
            Iap2Csm.string(0, deviceUuid()),
        )
        log(
            "iap2 tx=0x4e0d/0x4e0a/0x4e09/0x4e0b/0x4e0c 设备状态问候" +
                "（name=$deviceName mac=$bluetoothMac）",
        )

        // ---- CarPlay 会话启动 ----
        // 两种对端形态：
        //  - 测试台 DiPlay/部分车厂：收 0x4300 后回 0x4301（含 AirPlay 端口/IP/公钥）
        //  - Audi MIB3：识别清单只声明发送 0x5703/0xea03，**不认 0x4300、不发 0x4301**；
        //    AirPlay 端点走 WiFi 上的 mDNS（_airplay._tcp）。因此 0x4301 只给短窗口，
        //    超时返回无端点的 CarPlayEndpoint，由主控 mDNS 合成。
        // 0x4300 同样发后不管：2026-10-11 12:52 轮实测车机曾对它停止 ACK
        // （事件通道 SYN 洪水把它打懵的时段），链路看门狗险些再次自杀。
        sendFireAndForget(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac)),
        )
        // 车机声明里没有 0x4301 就直接跳过等待（Audi 实测每轮白等 10s）：
        // 真 iPhone 对这类车机也不等——AirPlay 端点经 mDNS/网关合成。
        val declaresStartSession = accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)
        val startSession =
            if (declaresStartSession == false) {
                log("iap2 车机未声明会发 0x4301——跳过等待（AirPlay 端点走 mDNS/网关）")
                null
            } else {
                expectOrNull(
                    Iap2MessageIds.CARPLAY_START_SESSION,
                    minOf(deadline, System.nanoTime() + START_SESSION_WAIT_NANOS),
                )
            }
        val endpoint =
            if (startSession != null) {
                parseStartSession(startSession, wifi)
            } else {
                log("iap2 0x4301 未到（该车机不发；AirPlay 端点走 mDNS _airplay._tcp）")
                CarPlayEndpoint(
                    ssid = wifi.ssid,
                    passphrase = wifi.passphrase,
                    channel = wifi.channel,
                    securityType = wifi.securityType,
                    airPlayPort = 0,
                    deviceIdentifier = null,
                    publicKey = null,
                    sourceVersion = null,
                    ipAddresses = emptyList(),
                )
            }
        if (startSession != null) {
            log(
                "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                    "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                    "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
            )
        }

        // 真机顺序：0x4301 之后回 0x4E0E（传输标识确认）。
        // Audi 实测：车机对该消息**接受但从不 ACK**（其余包毫秒级 ACK）——必须发后不管，
        // 否则重传看门狗在 +20s 把整条蓝牙链路杀掉（EA 会话/触摸载体随之丢失）。
        sendFireAndForget(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, bluetoothMac),
        )
        log("iap2 0x4e0d/0x4e0e/0x4300 —— Phase 1 完成：拿到热点凭据与（可能的）AirPlay 端点")

        // 后续（Phase 2）：加入热点 → mDNS 解析 _airplay._tcp → AirPlay 主会话
        this.wifi = wifi
        return endpoint
    }

    /**
     * Phase 2 过渡：bring-up 完成后保持链路打开，持续记录配件发来的控制消息
     * （如 0x4E0E 后重发的 0x5703、位置请求 0xFFFA 等），直到链路断开。
     * 真实无线 CarPlay 中这条控制通道与 AirPlay 会话并存于整个连接生命周期。
     */
    fun pumpIncomingForever() {
        log("SESSION 保活中（链路保持打开，持续记录配件消息）")
        // 同一消息 ID 连续到达时限流：车机进入媒体播放后会周期性发 NowPlaying 更新，
        // 逐条打日志会把文件日志撑爆（文件超 2MB 直接删，等于丢掉真正有用的上下文）。
        var lastLoggedId = -1
        var repeatCount = 0
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
            }
            if (frame.messageId == lastLoggedId) {
                repeatCount++
                if (repeatCount % 100 != 0) continue
            } else {
                if (repeatCount >= 99) {
                    log("iap2 上一条 0x${lastLoggedId.toString(16)} 重复 ${repeatCount + 1} 次（同类已限流）")
                }
                lastLoggedId = frame.messageId
                repeatCount = 0
            }
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) keep-alive bytes=${frame.body.size}",
            )
        }
        log("SESSION 保活结束（链路断开）")
    }

    // ---- 帧收发 ----

    /**
     * MFi 挑战应答：按「对端可接受的长度」依次尝试，每次失败（0xAA04 / 静默超时 / 断链）换下一档。
     * 基础顺序与窗口由两端实测决定（2026-10-09/10，缺一不可）：
     *  - **32B 优先**：测试台 DiPlay（Carlinkit AuthV3 EC 身份）**只接受精确 32B**，
     *    长度不对会直接断开链路（实测 70~350ms 内断链）；其应答快（~0.2s），3s 窗口足够。
     *  - **20B 次之**：真车 Audi MIB3 对 20B 秒答（catplay/extractor 真车实录同款）；
     *    对 32B 的处置两种都出现过——10-09 静默不断链、10-11 拆链。
     *  - **16B 兜底**：部分旧配件。
     * 「导致拆链」的档位由 [mfiRejectedLengths] 跨会话记忆（避免反复撞同一堵墙）；
     * 全部无应答时不抛异常直接返回——我们跳过签名校验，由后续 0x5702 是否获得应答
     * 判定对端是否放行（真车实测无应答后 0xaa05 仍可推进）。
     */
    private fun runMfiChallenge(
        deadline: Long,
        prefer20First: Boolean = false,
    ) {
        val ordered =
            if (prefer20First) {
                listOf(MFI_ATTEMPTS[1], MFI_ATTEMPTS[0], MFI_ATTEMPTS[2])
            } else {
                MFI_ATTEMPTS
            }
        var attempts = ordered.filter { it.first !in mfiRejectedLengths }
        if (attempts.isEmpty()) {
            // 所有档位都被拒过：清空重来，避免永久卡死
            mfiRejectedLengths = emptySet()
            attempts = ordered
        } else if (attempts.size < ordered.size) {
            log(
                "iap2 MFi 跳过被拒档位（本进程记忆：${mfiRejectedLengths.sorted().joinToString("B/")}B）" +
                    "——从 ${attempts.first().first}B 开始",
            )
        }
        for ((position, attempt) in attempts.withIndex()) {
            val size = attempt.first
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${position + 1} 次）")
            val window = minOf(System.nanoTime() + attempt.second * 1_000_000L, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        log("iap2 MFi 挑战期间链路断开（${size}B，对端不接受该长度）——记入本进程被拒档位")
                        mfiRejectedLengths = mfiRejectedLengths + size
                        return
                    }
                    continue
                }
                when (frame.messageId) {
                    Iap2MessageIds.MFI_RESPONSE -> {
                        log("iap2 rx=0xaa03 signature bytes=${frame.body.size}（跳过校验）")
                        gotResponse = true
                    }

                    Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                        log("iap2 rx=0xaa04 挑战 ${size}B 被拒")
                        gotFailure = true
                    }

                    else ->
                        log(
                            "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)})",
                        )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) return
            if (position < attempts.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${attempts[position + 1].first}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由 0x5702 判定车机是否放行）")
            }
        }
    }

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { acc, bytes -> acc + bytes }
            }
        val frame = Iap2Csm.encode(messageId, joined)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end))) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}")
    }

    /** [send] 的发后不管变体：链路不因对端不 ACK 此消息而重传/死亡（见 0x4E0E）。 */
    private fun sendFireAndForget(
        messageId: Int,
        body: ByteArray,
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end), awaitAck = false)) {
                throw IOException("iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("iap2 tx=0x${messageId.toString(16).padStart(4, '0')}（发后不管）")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待 0x${messageId.toString(16)} 超时")

    /**
     * 同 [expect]，但超时返回 null（用于可选消息）。非预期帧进暂存区供后续消费。
     *
     * 只从 [receivedFrames] 读新帧，**不重读自己刚暂存的帧**：旧实现两者共用一个
     * 队列，非预期帧被塞回后立刻又被本函数取出，形成活锁（见 pendingFrames 注释）。
     * 目标帧若已在暂存区（早到），先从这里取，保证不漏。
     */
    private fun expectOrNull(
        messageId: Int,
        deadlineNanos: Long,
    ): Iap2Csm.Frame? {
        pendingFrames.firstOrNull { it.messageId == messageId }?.let {
            pendingFrames.remove(it)
            return it
        }
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvNewFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return null
                continue
            }
            if (frame.messageId == messageId) return frame
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，暂存待后续消费",
            )
            pendingFrames += frame
            if (pendingFrames.size > maxPendingFrames) {
                pendingFrames.removeFirst()
                if (!pendingOverflowLogged) {
                    pendingOverflowLogged = true
                    log("iap2 非预期帧暂存区超过 $maxPendingFrames 条——丢弃最旧的（车机订阅类消息过多）")
                }
            }
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
        // 声明清单里没有 StartHID 就不等（DiPlay/奥迪实测均不发，旧流程每轮白等 2s）。
        if (accessoryDeclaresSend(Iap2MessageIds.START_HID) == false) {
            log("iap2 车机未声明会发 0x6800 StartHID——跳过 2s 等待")
            return
        }
        val hidWindowNanos = minOf(System.nanoTime() + HID_WAIT_NANOS, deadline)
        while (true) {
            val remaining = (hidWindowNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) {
                log("iap2 未收到 0x6800（该接收端不发 StartHID，继续）")
                return
            }
            val frame = recvFrame(remaining)
            if (frame == null) {
                if (!channel.isAlive) return // 链路已死：交给后续 expect 报出明确错误
                continue
            }
            when (frame.messageId) {
                Iap2MessageIds.START_HID -> {
                    log("iap2 rx=0x6800 start-hid bytes=${frame.body.size}")
                    send(
                        Iap2MessageIds.DEVICE_HID_REPORT,
                        Iap2Csm.u16(0, 0),
                        Iap2Csm.param(1, byteArrayOf(0)),
                    )
                    return
                }

                else ->
                    log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
            }
        }
    }

    /**
     * 通用取帧（MFi 挑战 / HID 等待 / 保活泵）：优先消费暂存的非预期帧
     * （保持到达顺序：暂存 > 新解析），再读新帧。
     */
    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    /** 只取新帧（先已解析队列、再读 socket）；绝不返回暂存区的帧。 */
    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        receivedFrames.firstOrNull()?.let {
            receivedFrames.removeFirst()
            return it
        }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 解析 ----

    /** 从 0x5703 解析出的热点凭据（收到后应立即绑 WiFi + 广播 _carplay-ctrl）。 */
    data class WifiConfiguration(
        val ssid: String,
        val passphrase: String,
        val channel: Int,
        val securityType: Int,
    )

    private fun parseWifiConfiguration(frame: Iap2Csm.Frame): WifiConfiguration {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        return WifiConfiguration(
            ssid = params[1]?.value?.asStringOrEmpty() ?: "",
            passphrase = params[2]?.value?.asStringOrEmpty() ?: "",
            securityType =
                params[3]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
            channel =
                params[4]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: 0,
        )
    }

    private fun parseStartSession(
        frame: Iap2Csm.Frame,
        wifi: WifiConfiguration,
    ): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses =
            wireless
                ?.filter { it.id == 3 }
                ?.mapNotNull { param -> param.value.asStringOrEmpty()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
        return CarPlayEndpoint(
            ssid = wifi.ssid,
            passphrase = wifi.passphrase,
            channel = wifi.channel,
            securityType = wifi.securityType,
            airPlayPort = params[2]?.value?.asU32() ?: 0,
            deviceIdentifier = params[3]?.value?.asStringOrEmpty(),
            publicKey = params[4]?.value?.asStringOrEmpty(),
            sourceVersion = params[5]?.value?.asStringOrEmpty(),
            ipAddresses = addresses,
        )
    }

    /**
     * 从 0x1D01 的 ExternalAccessoryProtocol 组件（参数组 10/11/12）解析 EA 协议：
     * 子参数 0 = 协议号（u8）、1 = 协议名。对照 DiPlay 同款构造（u8(0,index)+string(1,name)+u8(2,0)）。
     */
    private fun parseEaProtocols(frame: Iap2Csm.Frame): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        for (groupId in intArrayOf(10, 11, 12)) {
            val value = params[groupId]?.value ?: continue
            val sub = Iap2Csm.parseParams(value).associateBy { it.id }
            val protocolId =
                sub[0]
                    ?.value
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xFF) ?: continue
            val name = sub[1]?.value?.asStringOrEmpty() ?: "?"
            out += protocolId to name
            log("iap2 0x1d01 EA 组件[$groupId] protocol=$protocolId name=$name")
        }
        return out
    }

    private fun logIdentification(frame: Iap2Csm.Frame) {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val name = params[0]?.value?.asStringOrEmpty()
        val model = params[1]?.value?.asStringOrEmpty()
        val manufacturer = params[2]?.value?.asStringOrEmpty()
        log("iap2 rx=0x1d01 车机身份 name=$name model=$model manufacturer=$manufacturer")
        // 参 6/7 = 车机声明「它会发送/它可接收」的消息 ID 清单。
        // 参 7（它可接收）直接告诉我们它是否认可 0x4300 —— 真车排查的关键证据。
        val sends = params[6]?.value?.asU16List()
        val receives = params[7]?.value?.asU16List()
        if (sends != null) {
            // 后续可选消息（0x6800/0x4301）按此清单判断是否会来，避免固定窗口空等
            accessorySendIds = sends.toSet()
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
            log(
                "iap2 0x1d01 可选消息声明：StartHID(0x6800)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.START_HID)?.let { if (it) "会发" else "不发" } ?: "未知") +
                    " CarPlayStartSession(0x4301)=" +
                    (accessoryDeclaresSend(Iap2MessageIds.CARPLAY_START_SESSION)?.let { if (it) "会发" else "不发" } ?: "未知"),
            )
        }
        if (receives != null) {
            log("iap2 0x1d01 车机可收清单(${receives.size})=" + receives.joinToString(" ") { "0x%04x".format(it) })
        }
        log("iap2 0x1d01 原始体=" + frame.body.joinToString("") { "%02x".format(it) })
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) { index ->
            (value ushr (8 * (7 - index))).toByte()
        }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备）。 */
    private fun deviceUuid(): String =
        java.util.UUID
            .nameUUIDFromBytes(bluetoothMac.toByteArray())
            .toString()
            .uppercase()

    private fun ByteArray.asStringOrEmpty(): String? =
        if (isNotEmpty() && last() == 0.toByte()) {
            copyOf(size - 1).decodeToString()
        } else {
            null
        }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    /** 参 6/7 的消息清单：大端 u16 序列（识别帧里车机声明收/发能力用）。 */
    private fun ByteArray.asU16List(): List<Int>? {
        if (size < 2 || size % 2 != 0) return null
        return (0 until size step 2).map {
            ((this[it].toInt() and 0xFF) shl 8) or (this[it + 1].toInt() and 0xFF)
        }
    }
}
