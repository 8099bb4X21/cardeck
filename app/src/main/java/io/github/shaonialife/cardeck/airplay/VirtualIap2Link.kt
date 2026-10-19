package io.github.shaonialife.cardeck.airplay

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.MfiLengthMemory
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * 事件通道上的虚拟 iAP2 链路（AirPlay 会话之上的「WiFi 隧道」），一条实现服务三种形态：
 *
 *  - **DiPlay（type-130 隧道）**：出站写隧道 TCP；链路协商用「回显先行」语义
 *    （对端引擎硬性期望，见 [Iap2LinkEngine.echoFirst]）；
 *  - **Audi MIB3（事件通道 + 控制通道 POST）**：真机时序（AADisplay 以 Frida 挂
 *    越狱 iPhone mediaserverd 抓包确认）——**屏幕流 SETUP 成功后由手机主动发一次
 *    DETECT（经控制通道 POST /command 的 iAPSendMessage）→ 车机回 SYN（事件通道）
 *    → 手机回 SYN|ACK（控制通道）**，随后控制会话整段重跑：0x1D00 → 0x1D01 →
 *    0x1D02+0xAA00 →（0xAA06 → 0xAA00）→ 0xAA02 挑战 20B → 0xAA03 → 0xAA05 →
 *    手机突发消息 → 应答车机订阅。**只回显空 ACK 的话 MIB3 约 10~12 秒整链拆除**
 *    （本仓库 2026-10-19 两轮实车日志 10.2s 复现，此前误判为心跳缺失）；
 *  - **旧事件形态 / 明文会话**：marker 一问一答回显（保持历史行为）。
 *
 * 车机→手机的链路字节统一从事件通道 iAPSendMessage 进来（[onCarBytes]）；
 * 手机→车机按形态分流（隧道 TCP / 控制通道 POST / 事件通道 POST）。
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** 控制通道 iAPSendMessage 出站（MIB3 手机侧帧的唯一正确通道）。 */
    private val sendControlIap: (ByteArray) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件（宿主控制连接未关）。 */
    private val alive: () -> Boolean,
    /** MFi 长度记忆的持久化上下文；null = 进程内记忆（单测）。 */
    private val mfiContext: Context? = null,
    /** 时钟（单测可注入）。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    // ---- DiPlay 隧道路径状态 ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** 同步回显待投递（隧道门闸：回显真正写进隧道 TCP 后才允许发 0x1D00）。 */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 路径状态 ----
    private var mib3Armed = false
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null

    // ---- MIB3 隧道 MFi 挑战（按车机持久化记忆；与蓝牙链路**独立记**——同一台车
    // 两条链路的认证后端可能不同，实测 MMI 3315 蓝牙认 32B 而 Frida 隧道是 20B） ----
    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        Thread(
            {
                while (alive()) {
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
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 事件形态启动：服务端待命（收到 marker 回 marker，等车机 SYN）。 */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 布防（屏幕流 SETUP 成功后调用，且仅一次）：主动向**控制通道**发一次 DETECT。
     * 必须等 SETUP 完成后再发——早发会在 SETUP 交换期间被冲掉（Frida 结论）；此后
     * 车机的 marker 都是探测重发，按真机行为忽略。
     */
    fun armMib3() {
        synchronized(lock) {
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage）——等待车机 SYN")
        }
    }

    /** 车机链路字节入口（事件通道 iAPSendMessage 的 data）。 */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            // 隧道存在时切换「回显先行」协商语义（DiPlay 对端引擎硬性期望）
            engine.echoFirst = tunnelWriter() != null
            val isMarker =
                bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0x55.toByte()
            if (isMarker && tunnelWriter() == null) {
                if (mib3Armed) {
                    // MIB3 布防后：marker 是车机的探测重发，真机行为是忽略
                    // （它要的是我们经控制通道主动 announce 的那次 detect）
                    mib3MarkerIgnores++
                    if (mib3MarkerIgnores <= 3 || mib3MarkerIgnores % 20 == 0) {
                        log("IAPLINK: 车机 marker（#$mib3MarkerIgnores）——MIB3 忽略（等 SYN）")
                    }
                } else {
                    // 布防前（屏幕 SETUP 未完成）：不回应——真机在 SETUP 前不 detect，
                    // 事件通道上的回显对 MIB3 无意义（它只认控制通道）；旧明文接收端
                    // 不走此链路语义
                    if (mib3DeferredLogs < 2) {
                        mib3DeferredLogs++
                        log("IAPLINK: SETUP 前的车机 marker 暂不回应（布防后主动 detect）")
                    }
                    return
                }
            }
            engine.feed(bytes, System.nanoTime() / 1_000_000L)
            drainLocked()
        }
    }

    /** 链路字节出站：隧道 TCP（DiPlay）→ 控制通道 POST（MIB3）→ 事件通道（旧形态）。 */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /**
     * 引擎出站字节落盘：SYN|ACK 真正投递到对应通道后才允许启动 CSM——
     * 对端引擎未见到 SYN|ACK 前收到裸 ACK 会直接 die（DiPlay 实测）。
     */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ 同步回显已写入隧道（链路协商完成，发送端 CSM 就绪）")
                    }
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK 已投递控制通道——等待链路确认后启动 CSM")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        // 先冲刷（回显必须先行送达对端），再启动 CSM；超时换档可能新排队帧，最后再冲一次
        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
    }

    /** MFi 挑战静默超时：记入该车机的记忆并换下一档重试（节拍线程每 200ms 驱动一次）。 */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log("IAPLINK: 隧道 MFi ${failed}B 静默 ${mfiWindowMillis}ms——记入记忆，换下一档重试")
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /**
     * 下一档 MFi 挑战（顺序=按车机记忆重排，冷启动基线 20B 优先——Frida 真机值）。
     * 全部档位无应答时与蓝牙路径同语义：跳过校验直接 0xAA05，由车机后续行为判定。
     */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiLengthMemory.orderAttempts(TUNNEL_MFI_BASELINE, mfiMemory)
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: 隧道 MFi 按车机记忆：${MfiLengthMemory.describe(mfiMemory)}" +
                        " → 本轮顺序 ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log("IAPLINK: 隧道 MFi 挑战全部无应答——跳过校验直接 0xaa05（由车机后续行为判定）")
            mfiFinished = true
            mib3AuthStage = 4
            sendCsmLocked(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            sendMib3BurstLocked()
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: 隧道 tx=0xaa02 challenge=${mfiCurrentLength}B（第 ${mfiPosition + 1} 次，窗口 ${mfiWindowMillis}ms）")
    }

    /**
     * 控制会话启动（隧道/MIB3 两形态）：链路 NORMAL 且 SYN|ACK 已投递后，主动发
     * 0x1D00 StartIdentification。缺此步时对端控制端 5 分钟窗口超时即拆掉整条链路
     * （DiPlay 2026-10-11 实测根因；MIB3 的拆除窗口短得多，~10s）。
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** 链路上的 CSM 发送（进入引擎 backlog，由引擎按发送窗口 flush）。 */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
    }

    /** 直接投递完整 CSM 帧（Frida 抓的逐字节常量，勿重新编码）。 */
    private fun sendRawCsmLocked(frame: ByteArray) {
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        val messageId = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
    }

    /**
     * 控制会话应答（隧道与 MIB3 共用识别/MFi 骨架，MIB3 另有突发与订阅应答）：
     *   0x1D01 → 0x1D02 + 0xAA00；0xAA06 → 再发 0xAA00；
     *   0xAA01 → 0xAA02 挑战（MIB3 20B / DiPlay 32B）；0xAA03 → 0xAA05（MIB3 随发突发）。
     * 订阅（仅 MIB3）：0x4157→0x4158、0x4154→0x4155、0x4170→0x4171、0x5000→0x5001 空态。
     * 0xEA02（requestAppLaunch）/0xA101（VehicleStatus）/0xFFFB（PASCD 定位样本 ~1Hz）
     * 只链路 ACK（引擎自动）——**0xFFFB 漏 ACK 车机同样 ~10s 后关控制通道**（Frida 结论）。
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        val mib3 = mib3Armed && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                if (mib3) {
                    // 记忆键 = 车机身份 + " tunnel"：与蓝牙链路的记忆互不污染
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 1
            }

            MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06（auth serial）→ 再发 0xaa00")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: 隧道 MFi ${mfiCurrentLength}B 被拒（0xaa04）——记入记忆，换下一档")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // 长度按车机记忆（冷启动 20B 优先，Frida 真机值；详见 startNextMfiChallengeLocked）
                    startNextMfiChallengeLocked()
                } else {
                    // DiPlay CARLINKIT 身份只接受精确 32B（长度不符直接断链）
                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: 隧道 rx=0xaa03 签名 ${frame.body.size}B（${mfiCurrentLength}B 挑战成功，已记忆）")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                } else {
                    log("IAPLINK: 隧道 MFi 完成——等待对端订阅（随后其标记隧道就绪）")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158（空态）")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    CALL_STATE_UPDATE,
                    Iap2Csm.param(0x0002, byteArrayOf(0)),
                )
                log("IAPLINK: MIB3 0x4154 → 0x4155（无通话）")
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171（空列表）")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                // 仪表盘歌词/媒体卡的数据通路（0x5001 + file-transfer 封面）后续接
                // MediaSessionsState——空态应答是 MIB3 存活的前置，先落这版。
                log("IAPLINK: MIB3 0x5000 → 0x5001（空态）——仪表盘媒体数据待接")
            }

            else -> Unit // 0xEA02/0xA101/0xFFFB 等只链路 ACK（引擎自动）
        }
    }

    /**
     * 0xAA05 后的突发（Frida：真机在认证成功后立即发这一批，先于车机的订阅请求）。
     * 0xFFFA/0xFFF0/0x4158/0x5001 用逐字节常量——字段布局只存在于真机抓包里，
     * 重新编码极易走样（对照 AADisplay WifiIap2Link 的 Frida 常量）。
     */
    private fun sendMib3BurstLocked() {
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(AUX_STATUS_NOTIFICATION, Iap2Csm.param(0x0006, ByteArray(0)))
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: MIB3 突发已发（会话标识 $sessionId）——等待车机订阅")
    }

    /** 0x4E0B：u64 秒 + s16 时区偏移(分) + u8 夏令时。 */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** 0x1D01 身份参数（name/model/manufacturer）→ 记忆键素材（NUL 去净、空档跳过）。 */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            maybeRespondControlCsmLocked(frame)
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

    companion object {
        /** 隧道 MFi 冷启动基线（Frida 真机 20B 优先；顺序会被按车机记忆重排）。 */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        // ---- MIB3 消息号（无正式名/未入 Iap2MessageIds 的小集合） ----
        const val MFI_AUTH_SERIAL = 0xAA06
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- 以下为 Frida 抓包的逐字节常量（MIB3 真机 iPhone → Audi MMI）---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation。 */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /** 0xFFF0 GPRMCDataStatusValuesNotification。 */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** 0x5001 NowPlayingUpdate 空态（无媒体/无播放）。 */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate 空态（无 SIM/无信号场景的可用占位）。 */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
