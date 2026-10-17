package com.lizhihua.carplaysender.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 车机 Consumer Control HID 媒体键解析（奥迪 eso_playback 实测）。
 *
 * 关键语义（2026-10-17 实测定谳）：Array 报告里**字节值是 Usage 数组下标**——
 * 奥迪按下「下一首」收到 0x05，对应 usages[5]=0xB5；「上一首」0x06→usages[6]=0xB6。
 * 早期把字节直接当 Usage ID，得到 0x05/0x06 → 无对应键（用户反馈「收到消息但不切歌」）。
 */
class ConsumerKeysTest {
    /** 奥迪 /info hidDevices[0].hidDescriptor 原文（46B）。 */
    private val audiDescriptor =
        byteArrayOf(
            0x05,
            0x0c,
            0x09,
            0x01,
            0xa1.toByte(),
            0x01,
            0x15,
            0x00,
            0x25,
            0x08,
            0x05,
            0x0c,
            0x0a,
            0x00,
            0x00, // Usage 0x0000
            0x0a,
            0xb0.toByte(),
            0x00, // Play
            0x0a,
            0xb1.toByte(),
            0x00, // Pause
            0x0a,
            0xb3.toByte(),
            0x00, // FastForward
            0x0a,
            0xb4.toByte(),
            0x00, // Rewind
            0x0a,
            0xb5.toByte(),
            0x00, // ScanNextTrack
            0x0a,
            0xb6.toByte(),
            0x00, // ScanPreviousTrack
            0x0a,
            0xb9.toByte(),
            0x00, // Stop
            0x0a,
            0xbc.toByte(),
            0x00, // Repeat
            0x75,
            0x08,
            0x95.toByte(),
            0x01,
            0x81.toByte(),
            0x00,
            0xc0.toByte(),
        )

    @Test
    fun descriptorParsedAsArrayWithNineUsages() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)
        assertNotNull("应能解析出 Consumer 映射", map)
        assertEquals(true, map!!.isArray)
        assertEquals(9, map.usages.size)
        // 顺序必须与描述符声明一致（下标语义依赖它）
        assertEquals(
            listOf(0x0000, 0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC),
            map.usages,
        )
    }

    @Test
    fun reportValueIsArrayIndexNotUsageId() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        // 实测：下一首按字节 0x05、上一首 0x06
        assertEquals(0xB5, map.usageFor(0x05))
        assertEquals(0xB6, map.usageFor(0x06))
        // 教训：若把 0x05 直接当 Usage ID 会得到「未知」——这两个断言锁定正确语义
        assertEquals(0, ConsumerKeys.keyCodeFor(0x05))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        // 播放/暂停 = 下标 1/2
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        // 0 与越界 = 无按键
        assertNull(map.usageFor(0))
        assertNull(map.usageFor(20))
    }

    @Test
    fun nonConsumerDevicesAreNotMediaKeys() {
        // 判定以描述符为准，不靠报文长度（DiPlay 的 knob=4B、telephony=1B 会与媒体键重叠）：
        // telephony 描述符（Telephony 页 0x0B）不能被当成媒体键——其 usage 0xB3 若误判
        // 会发出「快进」。
        val telephonyDescriptor =
            byteArrayOf(
                0x05,
                0x0b,
                0x09,
                0x07,
                0xa1.toByte(),
                0x01,
                0x15,
                0x00,
                0x25,
                0x11,
                0x05,
                0x0b,
                0x09,
                0x00,
                0x09,
                0x20,
                0x09,
                0x21,
                0x09,
                0x26,
                0x09,
                0x2f,
                0x09,
                0xb0.toByte(),
                0x09,
                0xb1.toByte(),
                0x09,
                0xb2.toByte(),
                0x09,
                0xb3.toByte(),
                0x75,
                0x08,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x00,
                0xc0.toByte(),
            )
        assertNull(ConsumerKeys.parseDescriptor(telephonyDescriptor))
    }

    @Test
    fun diplayMediaDescriptorUsesIndexSemantics() {
        // DiPlay（测试台）media 描述符：Usage 数组 6 项，报告值同为下标
        val diplayMedia =
            byteArrayOf(
                0x05,
                0x0c,
                0x09,
                0x01,
                0xa1.toByte(),
                0x01,
                0x15,
                0x00,
                0x25,
                0x06,
                0x05,
                0x0c,
                0x0a,
                0x00,
                0x00, // 0x0000
                0x0a,
                0xb0.toByte(),
                0x00, // Play
                0x0a,
                0xb1.toByte(),
                0x00, // Pause
                0x0a,
                0xcd.toByte(),
                0x00, // Play/Pause toggle
                0x0a,
                0xb5.toByte(),
                0x00, // ScanNextTrack
                0x0a,
                0xb6.toByte(),
                0x00, // ScanPreviousTrack
                0x0a,
                0x9e.toByte(),
                0x02, // 0x029e（记录）
                0x75,
                0x08,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x00,
                0xc0.toByte(),
            )
        val map = ConsumerKeys.parseDescriptor(diplayMedia)!!
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usageFor(4)!!))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(map.usageFor(5)!!))
    }

    @Test
    fun usageMapsToAndroidMediaKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, ConsumerKeys.keyCodeFor(0xB0))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, ConsumerKeys.keyCodeFor(0xB1))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, ConsumerKeys.keyCodeFor(0xB3))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, ConsumerKeys.keyCodeFor(0xB4))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(0xB6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, ConsumerKeys.keyCodeFor(0xB9))
        // Repeat 无对应 Android 键 → 0（调用方忽略）
        assertEquals(0, ConsumerKeys.keyCodeFor(0xBC))
    }

    @Test
    fun descriptorWithoutConsumerPageReturnsNull() {
        // 奥迪触摸屏（uuid=1）描述符：Generic Desktop / Digitizer，无 Consumer 页
        val touchDescriptor =
            byteArrayOf(
                0x05,
                0x0d,
                0x09,
                0x04,
                0xa1.toByte(),
                0x01,
                0x05,
                0x0d,
                0x09,
                0x22,
                0xa1.toByte(),
                0x02,
                0x05,
                0x0d,
                0x09,
                0x33,
                0x15,
                0x00,
                0x25,
                0x01,
                0x75,
                0x01,
                0x95.toByte(),
                0x01,
                0x81.toByte(),
                0x02,
                0xc0.toByte(),
                0xc0.toByte(),
            )
        assertNull(ConsumerKeys.parseDescriptor(touchDescriptor))
    }

    @Test
    fun allDescriptorUsagesNamed() {
        listOf(0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC).forEach { usage ->
            assertFalse(
                "usage=0x%02x 应可读".format(usage),
                ConsumerKeys.nameOf(usage).startsWith("未知"),
            )
        }
    }
}
