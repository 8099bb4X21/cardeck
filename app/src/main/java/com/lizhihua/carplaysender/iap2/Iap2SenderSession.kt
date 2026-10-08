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
    private val random = SecureRandom()

    private companion object {
        /** 等 StartHID 的窗口：xcertplay 会发（订阅后立即到），DiPlay 不发。 */
        const val HID_WAIT_NANOS = 2_000_000_000L
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
        // 挑战长度：EC 密钥身份（AuthV3）要求精确 32 字节，RSA（AuthV2）接受 1..128 —— 32 两者通吃
        val challenge = ByteArray(32).also(random::nextBytes)
        send(
            Iap2MessageIds.MFI_REQUEST_CHALLENGE,
            Iap2Csm.param(0, challenge),
        )
        val response = expect(Iap2MessageIds.MFI_RESPONSE, deadline)
        log("iap2 rx=0xaa03 signature bytes=${response.body.size}（跳过校验）")
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

        // ---- CarPlay 会话启动 ----
        send(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(1, Iap2Csm.u8(0, 1)),
        )
        val startSession = expect(Iap2MessageIds.CARPLAY_START_SESSION, deadline)
        val endpoint = parseStartSession(startSession, wifi)
        log(
            "iap2 rx=0x4301 airPlayPort=${endpoint.airPlayPort} " +
                "device=${endpoint.deviceIdentifier} publicKey=${endpoint.publicKey?.take(16)}… " +
                "srcvers=${endpoint.sourceVersion} ips=${endpoint.ipAddresses}",
        )

        // ---- 无线可用与传输标识通知 ----
        send(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE, Iap2Csm.u8(0, 1))
        send(
            Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
            Iap2Csm.string(0, "02:00:00:00:00:00"),
        )
        log("iap2 tx=0x4e0d/0x4e0e —— Phase 1 完成：拿到热点与 AirPlay 端点")

        // 后续（Phase 2）：加入热点 → mDNS 解析 _airplay._tcp → AirPlay 主会话
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

    private fun expect(messageId: Int, deadline: Long): Iap2Csm.Frame {
        while (true) {
            val frame = recvFrame(remainingMillis(deadline))
            if (frame == null) {
                if (!channel.isAlive) {
                    throw IOException(
                        "接收端在等待 0x${messageId.toString(16)} 时断开连接（多半是它处理我们的上一条消息失败了）",
                    )
                }
                throw IOException("等待 0x${messageId.toString(16)} 超时")
            }
            if (frame.messageId == messageId) return frame
            log(
                "iap2 rx=0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)})，非预期，跳过",
            )
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
        receivedFrames.firstOrNull()?.let { receivedFrames.removeFirst(); return it }
        if (timeoutMillis <= 0) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.forEachIndexed { index, frame -> if (index > 0) receivedFrames += frame }
        return frames.first()
    }

    // ---- 解析 ----

    private data class WifiConfiguration(
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
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L
        return remaining.coerceIn(0, 60_000L)
    }

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
}
