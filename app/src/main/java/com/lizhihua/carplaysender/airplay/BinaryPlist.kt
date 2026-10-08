package com.lizhihua.carplaysender.airplay

/**
 * Apple 二进制 plist（bplist00）最小解析器：覆盖 /info 能力清单用到的类型
 * （null/bool/int/real/data/ASCII/UTF-16 字符串/UID/数组/字典）。
 * 返回值映射：Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / ByteArray / null。
 */
object BinaryPlist {

    fun parse(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "plist 太短（${bytes.size} 字节）" }
        require(
            bytes[0] == 0x62.toByte() && bytes[1] == 0x70.toByte() &&
                bytes[2] == 0x6C.toByte() && bytes[3] == 0x69.toByte() &&
                bytes[4] == 0x73.toByte() && bytes[5] == 0x74.toByte() &&
                bytes[6] == 0x30.toByte() && bytes[7] == 0x30.toByte(),
        ) { "不是 bplist00" }

        // 尾部 32 字节 trailer：unused(6) offsetIntSize(1) objectRefSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        val offsetIntSize = u8(bytes[bytes.size - 26])
        val objectRefSize = u8(bytes[bytes.size - 25])
        val numObjects = readUint(bytes, bytes.size - 24, 8).toInt()
        val topObject = readUint(bytes, bytes.size - 16, 8).toInt()
        val offsetTableOffset = readUint(bytes, bytes.size - 8, 8).toInt()
        require(topObject < numObjects) { "topObject 越界" }

        val offsets = IntArray(numObjects) { index ->
            readUint(bytes, offsetTableOffset + index * offsetIntSize, offsetIntSize).toInt()
        }
        return parseObject(bytes, offsets[topObject], objectRefSize, offsets)
    }

    private fun parseObject(bytes: ByteArray, offset: Int, refSize: Int, offsets: IntArray): Any? {
        val marker = u8(bytes[offset])
        val type = marker ushr 4
        val info = marker and 0xF
        return when (type) {
            0x0 -> when (info) {
                0x8 -> false
                0x9 -> true
                else -> null
            }

            0x1 -> readSigned(bytes, offset + 1, 1 shl info)

            0x2 -> when (info) {
                0x2 -> Float.fromBits(readUint(bytes, offset + 1, 4).toInt()).toDouble()
                0x3 -> Double.fromBits(readUint(bytes, offset + 1, 8))
                else -> null
            }

            0x4 -> { // data
                val (count, start) = elementCount(bytes, offset, info)
                bytes.copyOfRange(start, start + count)
            }

            0x5 -> { // ASCII 字符串
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.US_ASCII)
            }

            0x6 -> { // UTF-16BE 字符串
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count * 2, Charsets.UTF_16BE)
            }

            0x8 -> readUint(bytes, offset + 1, refSize) // UID

            0xA, 0xC -> { // array / set
                val (count, start) = elementCount(bytes, offset, info)
                List(count) { index ->
                    parseObject(
                        bytes,
                        offsets[readUint(bytes, start + index * refSize, refSize).toInt()],
                        refSize,
                        offsets,
                    )
                }
            }

            0xD -> { // dict
                val (count, start) = elementCount(bytes, offset, info)
                val map = LinkedHashMap<String, Any?>(count)
                for (index in 0 until count) {
                    val keyRef = readUint(bytes, start + index * refSize, refSize).toInt()
                    val valueRef = readUint(bytes, start + (count + index) * refSize, refSize).toInt()
                    val key = parseObject(bytes, offsets[keyRef], refSize, offsets)
                    map[key?.toString() ?: "#$index"] = parseObject(bytes, offsets[valueRef], refSize, offsets)
                }
                map
            }

            else -> null
        }
    }

    /** 集合/字符串/数据长度：info<0xF 时长度就是 info；否则长度存在随后的 int 对象里。 */
    private fun elementCount(bytes: ByteArray, offset: Int, info: Int): Pair<Int, Int> {
        if (info != 0xF) return info to offset + 1
        val intMarker = u8(bytes[offset + 1])
        require(intMarker ushr 4 == 0x1) { "期望长度 int 对象，得到 0x${intMarker.toString(16)}" }
        val intSize = 1 shl (intMarker and 0xF)
        val count = readSigned(bytes, offset + 2, intSize).toInt()
        return count to offset + 2 + intSize
    }

    private fun readSigned(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = if (size > 0 && bytes[offset].toInt() and 0x80 != 0) -1L else 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun readUint(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
}
