package com.lizhihua.carplaysender.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.lizhihua.carplaysender.airplay.AirPlayCrypto
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主屏视频发送端（Phase 2-C2）。
 *
 * 线格式（对照 DiPlay ScreenStream.kt）：
 *  - 每条消息 = 128 字节头 + body；头部 = [0..3] u32LE body 长度、[4] opcode、
 *    [8..15] NTP 32.32 LE 发送时间；整头作帧加密 AAD，其余字节为零
 *  - opcode 1 = VideoConfig：明文 avcC 记录（looksLikeAvcC 判定）
 *  - opcode 0 = VideoFrame：ChaCha20-Poly1305 密封的「4 字节 BE 长度前缀 NAL 序列」，
 *    nonce = nonce64(帧计数)，计数按帧顺序从 0 递增
 *
 * 视频源：MediaCodec Surface 输入（H.264 硬编码），当前画测试画面
 * （彩条 + 帧号文字）——后续替换为正式车机桌面 UI。
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val width: Int,
    private val height: Int,
    private val state: CarUiState,
    private val log: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private var frameCounter = 0L
    private var configSent = false
    private var framesSent = 0L

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val client = Socket()
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            socket = client
            log("VIDEO: TCP 已连通 $host:$dataPort，启动编码器 ${width}x$height@30")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            running.set(true)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            Thread({ drawLoop(surface) }, "video-draw").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    // ---- 绘制：交互测试界面（按钮 + 点击计数），触摸来自车机事件通道 ----

    private fun drawLoop(surface: Surface) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = height * 0.03f
        }
        val small = android.graphics.Paint(paint).apply { textSize = height * 0.018f }
        val dim = android.graphics.Paint(paint).apply { alpha = 90 }
        val activeStroke = android.graphics.Paint(paint).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f
        }
        val background = android.graphics.Paint().apply { color = 0xFF101418.toInt() }
        var frame = 0L
        while (running.get()) {
            val canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: Exception) {
                break
            }
            try {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
                paint.color = android.graphics.Color.WHITE
                canvas.drawText("CarPlay Sender", width * 0.07f, height * 0.12f, paint)
                small.color = 0xFF9AB0C4.toInt()
                canvas.drawText(
                    "点下面的按钮（触摸来自车机屏）",
                    width * 0.07f,
                    height * 0.17f,
                    small,
                )

                for (index in state.buttonLabels.indices) {
                    val top = state.buttonTop(index)
                    val active = state.activeButton == index
                    val fill = android.graphics.Paint().apply {
                        color = state.buttonColors[index].toInt()
                        alpha = if (active) 255 else 110
                    }
                    canvas.drawRoundRect(
                        state.buttonLeft,
                        top,
                        state.buttonLeft + state.buttonWidth,
                        top + state.buttonHeight,
                        40f,
                        40f,
                        fill,
                    )
                    if (active) {
                        activeStroke.color = android.graphics.Color.WHITE
                        canvas.drawRoundRect(
                            state.buttonLeft,
                            top,
                            state.buttonLeft + state.buttonWidth,
                            top + state.buttonHeight,
                            40f,
                            40f,
                            activeStroke,
                        )
                    }
                    dim.color = android.graphics.Color.WHITE
                    canvas.drawText(
                        state.buttonLabels[index],
                        state.buttonLeft + state.buttonWidth / 2 - paint.measureText(state.buttonLabels[index]) / 2,
                        top + state.buttonHeight / 2 + paint.textSize / 3,
                        dim,
                    )
                }

                small.color = 0xFF7EE3A0.toInt()
                canvas.drawText(
                    "点击 ${state.taps.get()} 次   最近触摸 ${state.lastTouch}",
                    width * 0.07f,
                    height * 0.9f,
                    small,
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            frame++
            try {
                Thread.sleep(33)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // ---- 编码输出 → 协议封装发送 ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        val output = socket?.getOutputStream() ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (_: Exception) {
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: continue
                    val flags = bufferInfo.flags
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        sendVideoConfig(buffer, bufferInfo.size, output)
                    } else if (bufferInfo.size > 0) {
                        sendVideoFrame(buffer, bufferInfo.size, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, output)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun sendVideoConfig(buffer: java.nio.ByteBuffer, size: Int, output: java.io.OutputStream) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps == null || pps == null) {
            log("VIDEO: csd 缺少 SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
            return
        }
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // profile
        avcc.write(sps[2].toInt()) // compat
        avcc.write(sps[3].toInt()) // level
        avcc.write(0xFF) // 6 位保留 + 2 位长度尺寸-1（4 字节）
        avcc.write(0xE1) // 3 位保留 + 5 位 SPS 数
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        writeMessage(OP_CONFIG, body, body.size, output)
        configSent = true
        log("VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B（SPS ${sps.size}B/PPS ${pps.size}B）")
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: 丢弃一帧（config 尚未发送）")
            return
        }
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        if (nalUnits.isEmpty()) return

        val packed = ByteArrayOutputStream(size)
        for (nal in nalUnits) {
            packed.write((nal.size ushr 24) and 0xFF)
            packed.write((nal.size ushr 16) and 0xFF)
            packed.write((nal.size ushr 8) and 0xFF)
            packed.write(nal.size and 0xFF)
            packed.write(nal)
        }
        val plain = packed.toByteArray()

        val header = buildHeader(OP_FRAME, plain.size + TAG_SIZE)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(frameCounter), plain, header)
        frameCounter++
        output.write(header)
        output.write(sealed)
        output.flush()
        framesSent++
        if (keyFrame || framesSent <= 3 || framesSent % 150 == 0L) {
            log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
        }
    }

    /** 128 字节消息头：u32LE body 长度、opcode、NTP 32.32 LE 时间戳，其余为零。 */
    private fun buildHeader(opcode: Int, bodyLength: Int): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(opcode: Int, body: ByteArray, declaredLength: Int, output: java.io.OutputStream) {
        output.write(buildHeader(opcode, declaredLength))
        output.write(body)
        output.flush()
    }

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        var starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    private companion object {
        const val HEADER_LEN = 128
        const val OP_FRAME = 0
        const val OP_CONFIG = 1
        const val TAG_SIZE = 16
    }
}
