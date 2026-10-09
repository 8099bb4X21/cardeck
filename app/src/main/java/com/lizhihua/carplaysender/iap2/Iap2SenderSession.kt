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
    private val receivedFrames = ArrayDeque<Iap2Csm.Frame>()

    /** 非预期帧的暂存区（expectOrNull 等可选等待用）；recvFrame 优先从这里取。 */
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()
    private val random = SecureRandom()

    /** 0x5703 解析结果（保活期间车机重发 0x5702 时需重新应答）。 */
    @Volatile
    private var wifi: WifiConfiguration? = null

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L

        /** 单次 MFi 挑战的等待窗口（超时则回退下一长度）。 */
        const val MFI_RESPONSE_WAIT_NANOS = 6_000_000_000L

        /** 等 0x4301 的短窗口（真车不发该消息，走 mDNS；测试台毫秒级应答）。 */
        const val START_SESSION_WAIT_NANOS = 10_000_000_000L
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

        // ---- MFi（跳过校验：0xAA05 自报成功）----
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("iap2 rx=0xaa01 certificate bytes=${certificate.body.size}（跳过校验）")
        // 挑战长度：EC 密钥身份（AuthV3，Carlinkit 系）要求精确 32 字节；RSA（AuthV2）
        // 接受 1..128 —— 32 两者通吃（测试台 DiPlay 实测）。真车实录
        // （carplay-wifi-extractor client）用的是 20 字节。Audi MIB3 实测对 32B 静默，
        // 因此依次 32→20→16 回退；全部无应答也不中止——我们不校验签名（0xAA05 自报），
        // 车机若不在乎挑战可直接接受 0xAA05（由后续 0x5702 是否获得应答来判定）。
        runMfiChallenge(deadline)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
        log("iap2 tx=0xaa05 authentication-succeeded（自报）")

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
        val timezoneMinutes = java.util.TimeZone.getDefault()
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
        send(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac)),
        )
        val startSession = expectOrNull(
            Iap2MessageIds.CARPLAY_START_SESSION,
            minOf(deadline, System.nanoTime() + START_SESSION_WAIT_NANOS),
        )
        val endpoint = if (startSession != null) {
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
        send(
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
        while (true) {
            val frame = recvFrame(30_000L)
            if (frame == null) {
                if (!channel.isAlive) break
                continue
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
     * MFi 挑战应答：依次尝试 32B（AuthV3 要求）/ 20B（extractor 真车实录）/ 16B（部分旧配件）。
     * 每次失败（0xAA04 / 静默超时）换下一档；全部无应答时不抛异常直接返回——
     * 我们跳过签名校验，车机可能在未完成挑战时也接受 0xAA05（真车实测据此推进）。
     * 单次等待窗口见 [MFI_RESPONSE_WAIT_NANOS]。
     */
    private fun runMfiChallenge(deadline: Long) {
        // 20B 优先：catplay（真车验证）与 extractor 真车实录都用 20B；Audi MIB3 实测对 32B 静默。
        // 32B 次之（AuthV3 EC 身份精确要求，测试台 DiPlay 同款）；16B 兜底旧配件。
        val attempts = listOf(20, 32, 16)
        for ((index, size) in attempts.withIndex()) {
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            log("iap2 tx=0xaa02 challenge=${size}B（第 ${index + 1} 次）")
            val window = minOf(System.nanoTime() + MFI_RESPONSE_WAIT_NANOS, deadline)
            var gotResponse = false
            var gotFailure = false
            while (true) {
                val remaining = (window - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val frame = recvFrame(remaining)
                if (frame == null) {
                    if (!channel.isAlive) {
                        log("iap2 MFi 挑战期间链路断开（${size}B）")
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

                    else -> log(
                        "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)})",
                    )
                }
                if (gotResponse || gotFailure) break
            }
            if (gotResponse) return
            if (index < attempts.size - 1) {
                log("iap2 MFi ${size}B 无应答，改用 ${attempts[index + 1]}B 重试")
            } else {
                log("iap2 MFi 挑战全部无应答——跳过校验直接发 0xaa05（由 0x5702 判定车机是否放行）")
            }
        }
    }

    private fun send(messageId: Int, vararg body: ByteArray) {
        val joined = when (body.size) {
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

    private fun expect(messageId: Int, deadline: Long): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待 0x${messageId.toString(16)} 超时")

    /** 同 [expect]，但超时返回 null（用于可选消息）。非预期帧放回队列供后续消费。 */
    private fun expectOrNull(messageId: Int, deadlineNanos: Long): Iap2Csm.Frame? {
        while (true) {
            val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return null
            val frame = recvFrame(remaining)
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
        }
    }

    private fun awaitOptionalHid(deadline: Long) {
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

                else -> log(
                    "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)})",
                )
            }
        }
    }

    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        // 优先：可选等待暂存的非预期帧（保持到达顺序：暂存 > 已解析队列）
        pendingFrames.firstOrNull()?.let { pendingFrames.removeFirst(); return it }
        receivedFrames.firstOrNull()?.let { receivedFrames.removeFirst(); return it }
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
            securityType = params[3]?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0,
            channel = params[4]?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0,
        )
    }

    private fun parseStartSession(frame: Iap2Csm.Frame, wifi: WifiConfiguration): CarPlayEndpoint {
        val params = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val wirelessGroup = params[1]?.value
        val wireless = wirelessGroup?.let { Iap2Csm.parseParams(it) }
        val addresses = wireless
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
            log("iap2 0x1d01 车机发送清单(${sends.size})=" + sends.joinToString(" ") { "0x%04x".format(it) })
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

    private fun u64Bytes(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr (8 * (7 - index))).toByte()
    }

    /** 设备 UUID：由蓝牙 MAC 确定性派生，跨会话稳定（车机按它标识设备）。 */
    private fun deviceUuid(): String =
        java.util.UUID.nameUUIDFromBytes(bluetoothMac.toByteArray()).toString().uppercase()

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
