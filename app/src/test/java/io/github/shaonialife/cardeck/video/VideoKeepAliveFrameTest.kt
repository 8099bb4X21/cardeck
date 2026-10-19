package io.github.shaonialife.cardeck.video

import io.github.shaonialife.cardeck.airplay.BinaryPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 屏幕流 keepAlive 帧格式回归。
 *
 * 2026-10-19 奥迪 MMI 3315 实测：只有 UDP keepAlive + /feedback 时，RECORD 后约
 * 10.2s 整轮会话被 reset（视频/事件通道都健康）。catplay 在视频持续推流时**仍每秒**
 * 向屏幕数据口写一条 keepAlive 帧——这是接收端屏幕上独立的存活信号。
 * 帧格式写错时接收端静默忽略、现象仍是「10 秒断开」，所以必须有单测锁住。
 */
class VideoKeepAliveFrameTest {
    private fun header(frame: ByteArray) = frame.copyOfRange(0, 128)

    private fun bodySize(header: ByteArray): Int =
        ByteBuffer
            .wrap(header, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    @Test
    fun emptyKeepAliveIsOpcode2HeaderOnly() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = false, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        assertEquals(128, frame.size)
        assertEquals(0, bodySize(frame))
        assertEquals(2, frame[4].toInt() and 0xFF)
    }

    @Test
    fun statsKeepAliveIsOpcode5WithBplistBody() {
        val frame =
            VideoSender.buildKeepAliveFrame(
                withStats = true,
                frames = 30,
                bytesPerSecond = 900_000.0,
                framesPerSecond = 30,
            )
        val head = header(frame)
        assertEquals(5, head[4].toInt() and 0xFF)
        val size = bodySize(head)
        assertEquals(frame.size - 128, size)

        val body = frame.copyOfRange(128, frame.size)
        val stats = BinaryPlist.parse(body) as Map<*, *>
        assertEquals(30L, stats["encoderCurrentFPS"])
        assertEquals(30L, stats["sentFramesAvg"])
        assertEquals(900_000.0, stats["txUsageAvg"])
    }

    @Test
    fun statsBodyLengthIsMirroredIntoParams14AsLittleEndianFloat() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        val expected = (frame.size - 128).toFloat()
        // params[14] 位于偏移 120..128，后 4 字节是 f32 LE 的 body 长度（catplay 同款）
        val declared = ByteBuffer.wrap(frame, 124, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(expected, declared, 0.0f)
        // 前 4 字节固定 0.0f
        assertEquals(0.0f, ByteBuffer.wrap(frame, 120, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }

    @Test
    fun keepAliveHeaderCarriesNoChaChaTag() {
        // keepAlive 是明文帧：body 长度必须等于实际 body（不含 16B tag），
        // 否则接收端会把统计体后的字节当下一帧头，帧边界整体错位。
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 1, bytesPerSecond = 0.0, framesPerSecond = 1)
        val size = bodySize(header(frame))
        assertTrue("body=$size", size > 0)
        assertEquals(128 + size, frame.size)
    }
}
