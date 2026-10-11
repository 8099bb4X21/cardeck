package com.lizhihua.carplaysender.input

/**
 * 触摸 HID 报文解析（对照 DiPlay AirPlayHid.touchReport）。
 *
 * DiPlay 测试台格式：报文 12 字节 = 2 槽 × 6 字节：[槽号][按下 0/1][X u16LE][Y u16LE]。
 * 坐标为显示像素（接收端已按其显示屏分辨率缩放，与我们的视频分辨率一致）。
 * 触摸设备 UUID = 0x2a2a2a2a 的十六进制字符串 "2a2a2a2a"。
 *
 * Audi MIB3 真车格式（2026-10-11 hidDescriptor 实测，uuid=1 eso_touchScreen）：
 * 单指 5 字节 = [tip 开关 1bit + 7bit 填充][X u16LE][Y u16LE]；
 * 描述符 X 上限 0x584=1412、Y 上限 0x28e=654 = 车机屏幕分辨率的绝对坐标。
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    const val AUDI_TOUCH_UUID_HEX = "1"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    /** 按 uuid 选择解析器；未知 uuid 返回 null（调用方记日志）。 */
    fun parseByUuid(uuid: String?, report: ByteArray): List<Contact>? = when (uuid) {
        TOUCH_UUID_HEX -> parse(report)
        AUDI_TOUCH_UUID_HEX -> parseSingleTouch(report)
        else -> null
    }

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

    /** Audi eso_touchScreen：5 字节单指绝对坐标。 */
    fun parseSingleTouch(report: ByteArray): List<Contact> {
        if (report.size < 5) return emptyList()
        return listOf(
            Contact(
                slot = 0,
                down = report[0].toInt() and 0x01 != 0,
                xPixels = readU16Le(report, 1),
                yPixels = readU16Le(report, 3),
            ),
        )
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
