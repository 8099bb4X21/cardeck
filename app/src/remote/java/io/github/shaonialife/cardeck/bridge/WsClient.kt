package io.github.shaonialife.cardeck.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * 最小 RFC6455 WebSocket 客户端（只为配对的调试桥服务，不追求通用）：
 * 文本帧收发、掩码、ping/pong、close、服务端分片合并。
 *
 * 不引 okhttp/ws 依赖的原因：调试桥代码只进 remote 变体，但依赖仍会拖进构建；
 * 自家的服务端（ws 库）与已知的消息形态让 ~200 行的手写实现足够可靠。
 * wss:// 走系统默认 SSLSocketFactory（内网穿透服务如 Cloudflare Tunnel 提供有效
 * 证书）；自签/裸 IP 场景用 ws://（局域网或 http 隧道）。
 *
 * 线程模型：每个连接一根读线程（内部），发送在任意线程串行化；连接生命周期由
 * [BridgeClient] 管理（重连退避在那里）。
 */
class WsClient(
    private val url: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()

        fun onText(text: String)

        fun onClosed(reason: String)
    }

    private val writeLock = Any()
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    private var alive = false
    val isConnected: Boolean get() = alive

    private val random = SecureRandom()

    /** 阻塞式连接（在调用线程完成握手；失败抛 IOException）。 */
    fun connect() {
        val secure = url.startsWith("wss://", ignoreCase = true)
        val rest = url.substring(if (secure) 6 else 5)
        val hostPortPath =
            if (rest.contains('/')) {
                val i = rest.indexOf('/')
                rest.substring(0, i) to rest.substring(i)
            } else {
                rest to "/"
            }
        val host = hostPortPath.first.substringBefore(':')
        val port =
            hostPortPath.first.substringAfter(':', if (secure) "443" else "80").toIntOrNull()
                ?: throw IOException("端口解析失败：$url")
        val path = hostPortPath.second.ifBlank { "/" }

        val raw =
            if (secure) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                factory.createSocket().also { s ->
                    s.connect(InetSocketAddress(host, port), 8_000)
                }
            } else {
                Socket().also { it.connect(InetSocketAddress(host, port), 8_000) }
            }
        raw.tcpNoDelay = true
        val input = BufferedInputStream(raw.getInputStream())
        val out = BufferedOutputStream(raw.getOutputStream())
        socket = raw
        output = out

        val key = ByteArray(16).also { random.nextBytes(it) }
        val keyB64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val handshake =
            "GET $path HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $keyB64\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(handshake.toByteArray(Charsets.US_ASCII))
        out.flush()

        val head = readUntilHeaderEnd(input) ?: throw IOException("握手应答不完整")
        if (!head.startsWith("HTTP/1.1 101") && !head.startsWith("HTTP/1.0 101")) {
            throw IOException("握手被拒：${head.lineSequence().first()}")
        }
        alive = true
        listener.onOpen()
        readLoop(input)
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        val terminator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            val bytes = buf.toByteArray()
            if (bytes.size >= 4 && bytes.copyOfRange(bytes.size - 4, bytes.size).contentEquals(terminator)) {
                return String(bytes, Charsets.ISO_8859_1)
            }
            if (bytes.size > 16_384) return null
        }
    }

    private fun readLoop(input: BufferedInputStream) {
        try {
            var fragmentOpcode = -1
            var fragmentBuffer: ByteArrayOutputStream? = null
            while (alive) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    OPCODE_TEXT, OPCODE_BINARY -> {
                        if (fragmentBuffer != null) {
                            // 收到新数据帧却挂着未完成分片：丢弃旧分片重新开始
                            fragmentBuffer = null
                            fragmentOpcode = -1
                        }
                        if (frame.isFin) {
                            listener.onText(String(frame.payload, Charsets.UTF_8))
                        } else {
                            fragmentOpcode = frame.opcode
                            fragmentBuffer = ByteArrayOutputStream().also { it.write(frame.payload) }
                        }
                    }

                    OPCODE_CONTINUATION -> {
                        fragmentBuffer?.let { frag ->
                            frag.write(frame.payload)
                            if (frame.isFin) {
                                listener.onText(String(frag.toByteArray(), Charsets.UTF_8))
                                fragmentBuffer = null
                                fragmentOpcode = -1
                            }
                        }
                    }

                    OPCODE_PING -> runCatching { sendFrame(OPCODE_PONG, frame.payload, mask = true) }

                    OPCODE_PONG -> Unit

                    OPCODE_CLOSE -> {
                        val reason =
                            frame.payload
                                .drop(2)
                                .toByteArray()
                                .toString(Charsets.UTF_8)
                        listener.onClosed("对端关闭：$reason")
                        return
                    }
                }
            }
            listener.onClosed("读循环结束")
        } catch (error: IOException) {
            listener.onClosed("IO：${error.message}")
        } finally {
            alive = false
            runCatching { socket?.close() }
        }
    }

    private class Frame(
        val opcode: Int,
        val isFin: Boolean,
        val payload: ByteArray,
    )

    private fun readFrame(input: BufferedInputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or (input.read() and 0xFF).toLong() }
        }
        if (len < 0 || len > 8_000_000) return null
        val maskKey = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(len.toInt())
        readFully(input, payload)
        if (maskKey != null) {
            payload.forEachIndexed { index, _ -> payload[index] = (payload[index].toInt() xor maskKey[index % 4].toInt()).toByte() }
        }
        return Frame(opcode, fin, payload)
    }

    private fun readFully(
        input: BufferedInputStream,
        target: ByteArray,
    ) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IOException("连接中断（读载荷）")
            offset += count
        }
    }

    /** 发送文本帧（连接断开时返回 false，由上层走重连）。 */
    fun sendText(text: String): Boolean = sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), mask = true)

    private fun sendFrame(
        opcode: Int,
        payload: ByteArray,
        mask: Boolean,
    ): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                val header = ByteArrayOutputStream()
                header.write(0x80 or opcode)
                val maskBit = if (mask) 0x80 else 0
                when {
                    payload.size < 126 -> header.write(maskBit or payload.size)
                    payload.size < 65536 -> {
                        header.write(maskBit or 126)
                        header.write((payload.size shr 8) and 0xFF)
                        header.write(payload.size and 0xFF)
                    }

                    else -> {
                        header.write(maskBit or 127)
                        var v = payload.size.toLong()
                        val shiftOrder = (7 downTo 0).map { it * 8 }
                        shiftOrder.forEach { shift -> header.write(((v shr shift) and 0xFF).toInt()) }
                        @Suppress("UNUSED_EXPRESSION")
                        v = 0
                    }
                }
                if (mask) {
                    val key = ByteArray(4).also { random.nextBytes(it) }
                    header.write(key)
                    val masked = ByteArray(payload.size)
                    payload.forEachIndexed { i, b -> masked[i] = (b.toInt() xor key[i % 4].toInt()).toByte() }
                    out.write(header.toByteArray())
                    out.write(masked)
                } else {
                    out.write(header.toByteArray())
                    out.write(payload)
                }
                out.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    fun close(
        code: Int = 1000,
        reason: String = "",
    ) {
        closedByUs = true
        alive = false
        val body = ByteArray(2 + reason.toByteArray(Charsets.UTF_8).size)
        body[0] = (code shr 8).toByte()
        body[1] = code.toByte()
        reason.toByteArray(Charsets.UTF_8).copyInto(body, 2)
        runCatching { sendFrame(OPCODE_CLOSE, body, mask = true) }
        runCatching { socket?.close() }
    }

    private companion object {
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
    }
}
