package com.lizhihua.carplaysender.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import com.lizhihua.carplaysender.airplay.AirPlayCrypto
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=100（对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *
 * 采集：`AudioPlaybackCapture`（Android 10+）捕获手机端其他应用播放的媒体/导航音频
 * （需要 MediaProjection——与镜像模式的同一授权）。每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 静音手机媒体音量（声音只从车机出）。null = 不做（测试用）。 */
    private val context: android.content.Context? = null,
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2

        /** 每包样本帧数（48000/512 ≈ 93.75 包/秒 ≈ 10.7ms/包）。 */
        const val SAMPLES_PER_PACKET = 512

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        /** RTP 动态负载类型起点（catplay 直接用 stream type 100 作 PT）。 */
        private const val PAYLOAD_TYPE = 100

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val ssrc = SecureRandom().nextInt()

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** 静音前的手机媒体音量 index（-1 = 未静音）。 */
    private var mutedOriginalVolume = -1

    /**
     * 静音手机媒体输出：音频通道激活后声音只从车机出（真 CarPlay 行为）。
     * 依据（实测 2026-10-10）：播放捕获取自 REMOTE_SUBMIX（音量应用点之前），
     * 手机流音量静音**不影响捕获数据**——手机不响、车机照常出声。
     *
     * 恢复值持久化（SharedPreferences）：进程被系统杀死/安装更新时来不及恢复，
     * 下次静音若读到 0 会把它当"原音量"，导致断开后永久静音（2026-10-10 踩过）。
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // 目标恢复值：优先上次未恢复成功的保存值；否则当前值（>0 才可信）
            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            log(
                "AUDIO: 已静音手机媒体输出（原音量 ${if (restoreTarget > 0) restoreTarget else "未知"}，" +
                    "断开后恢复）——声音只从车机出",
            )
        } catch (error: Exception) {
            log("AUDIO: 静音手机媒体失败 - ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("AUDIO: 已恢复手机媒体音量（$target）")
            } else {
                log("AUDIO: 无有效原音量记录，手机媒体保持当前值（请按音量键调整）")
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * 启动音频发送。需要 MediaProjection（AudioPlaybackCapture 的授权来源）。
     * 返回是否成功进入采集循环。
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(PACKET_BYTES * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AUDIO: AudioRecord 初始化失败（state=${recorder.state}）")
                runCatching { recorder.release() }
                client.close()
                socket = null
                return false
            }
            record = recorder
            recorder.startRecording()
            running.set(true)
            mutePhoneMedia()
            log(
                "AUDIO: 已启动（播放捕获 48kHz 立体声 → $host:$dataPort，" +
                    "每包 ${SAMPLES_PER_PACKET} 帧）",
            )

            Thread({ runCaptureLoop() }, "audio-capture").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { socket?.close() }
        socket = null
        thread = null
        restorePhoneMedia()
        log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
    }

    /** 捕获配置：媒体/游戏/导航等常见播放用途（逐项添加，个别用途被系统拒绝时跳过）。 */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val client = socket ?: return
        val pcm = ByteArray(PACKET_BYTES)
        var sequence = 0
        var timestamp = 0L
        var nonceCounter = 0L
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (error: Exception) {
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("AUDIO: 采集错误码 $read（连续 $readFailures 次）")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < PACKET_BYTES) continue // 残包（缓冲尾部）：丢弃，等下一个整包
                readFailures = 0

                val wire = sealPacket(pcm, sequence, timestamp, nonceCounter)
                sequence = (sequence + 1) and 0xFFFF
                timestamp += SAMPLES_PER_PACKET
                nonceCounter++

                try {
                    client.send(DatagramPacket(wire, wire.size))
                } catch (error: IOException) {
                    log("AUDIO: UDP 发送失败 - ${error.message}")
                    break
                }
                packetsSent++
                bytesSent += wire.size
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            stop()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        val wirePcm = bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePcm, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        header[1] = PAYLOAD_TYPE.toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
