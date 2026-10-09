package com.lizhihua.carplaysender.input

/**
 * 触摸 HID 报文解析（对照 DiPlay AirPlayHid.touchReport）。
 *
 * 报文 12 字节 = 2 槽 × 6 字节：[槽号][按下 0/1][X u16LE][Y u16LE]。
 * 坐标为显示像素（接收端已按其显示屏分辨率缩放，与我们的视频分辨率一致）。
 * 触摸设备 UUID = 0x2a2a2a2a 的十六进制字符串 "2a2a2a2a"。
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    fun parse(report: ByteArray): List<Contact> {
        if (report.size < SLOTS * BYTES_PER_FINGER) return emptyList()
        return (0 until SLOTS).mapNotNull { slot ->
            val offset = slot * BYTES_PER_FINGER
            Contact(
                slot = slot,
                down = report[offset + 1].toInt() and 0xFF == 1,
                xPixels = readU16Le(report, offset + 2),
                yPixels = readU16Le(report, offset + 4),
            )
        }
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
