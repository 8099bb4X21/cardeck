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
                return PairVerifyClient(identity, transport, log).run(receiverStaticKey)
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
            log("AIRPLAY: TCP 已连通 $host:${endpoint.airPlayPort}")
            true
        } catch (error: IOException) {
            log("AIRPLAY: 连接失败 - ${error.message}")
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
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("未连接")
        client.soTimeout = timeoutMillis
        val output = client.getOutputStream()
        val head = "$method $path RTSP/1.0\r\n" +
            "CSeq: ${++cSeq}\r\n" +
            "User-Agent: AirPlay/${endpoint.sourceVersion ?: "950.7.1"}\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) output.write(body)
        output.flush()

        val input = client.getInputStream()
        // 关键：响应头和 body（甚至下一个响应）常在同一次 read 里到达——
        // 必须先把已缓冲字节算进 Content-Length，只从流里补差额，否则会永远等不到 body
        var buffer = leftover
        leftover = ByteArray(0)
        val separator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        var separatorIndex = indexOfSeparator(buffer, separator)
        while (separatorIndex < 0) {
            val chunk = ByteArray(4096)
            val count = input.read(chunk)
            if (count < 0) throw IOException("响应在头之前断流（已缓冲 ${buffer.size} 字节）")
            buffer += chunk.copyOf(count)
            separatorIndex = indexOfSeparator(buffer, separator)
        }
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val statusLine = headText.lineSequence().firstOrNull() ?: "(空)"
        val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val bodyStart = separatorIndex + separator.size
        while (buffer.size - bodyStart < contentLength) {
            val chunk = ByteArray(4096)
            val count = input.read(chunk)
            if (count < 0) throw IOException("body 在 ${buffer.size - bodyStart}/$contentLength 处断流")
            buffer += chunk.copyOf(count)
        }
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        if (buffer.size > bodyStart + contentLength) {
            leftover = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
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
