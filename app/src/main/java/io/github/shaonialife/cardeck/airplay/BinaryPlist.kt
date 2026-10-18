package io.github.shaonialife.cardeck.airplay

import java.io.ByteArrayOutputStream

/**
 * Apple 二进制 plist（bplist00）最小解析器：覆盖 /info 能力清单用到的类型
 * （null/bool/int/real/data/ASCII/UTF-16 字符串/UID/数组/字典）。
 * 返回值映射：Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / ByteArray / null。
 */
object BinaryPlist {
    // ---- 编码：Map<String,Any?>/List/String/Long/Int/Boolean/Double/ByteArray ----

    fun encode(value: Any?): ByteArray {
        val objects = ArrayList<ByteArray>()
        val totalObjects = countObjects(value)
        val rootRef = encodeObject(value, objects, totalObjects)
        var offset = 8
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = offset
            offset += bytes.size
        }
        val offsetIntSize = intBytes(offset)
        val refSize = intBytes(totalObjects)
        val tableSize = objects.size * offsetIntSize
        val tableOffset = offset

        val out = ByteArrayOutputStream(offset + tableSize + 32)
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        objects.forEach(out::write)
        offsets.forEach { writeUint(out, it.toLong(), offsetIntSize) }
        // trailer: unused(6) offsetIntSize(1) refSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        out.write(ByteArray(6))
        out.write(offsetIntSize)
        out.write(refSize)
        writeUint(out, objects.size.toLong(), 8)
        writeUint(out, rootRef.toLong(), 8)
        writeUint(out, tableOffset.toLong(), 8)
        return out.toByteArray()
    }

    /** 预遍历：引用宽度必须按最终对象总数（含容器自身）确定。 */
    private fun countObjects(value: Any?): Int =
        when (value) {
            is List<*> -> 1 + value.sumOf { countObjects(it) }
            is Map<*, *> -> 1 + value.keys.sumOf { countObjects(it) } + value.values.sumOf { countObjects(it) }
            else -> 1
        }

    private fun encodeObject(
        value: Any?,
        objects: ArrayList<ByteArray>,
        total: Int,
    ): Int {
        val bytes =
            when (value) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (value) 0x09 else 0x08)
                is Int -> encodeInt(value.toLong())
                is Long -> encodeInt(value)
                is Double -> byteArrayOf(0x23) + value.toRawBits().toBytes(8)
                is ByteArray -> withLength(0x40, value)
                is String -> encodeString(value)
                is List<*> -> {
                    val refs = value.map { encodeObject(it, objects, total) }
                    withLength(0xA0, refsToBytes(refs, total), refs.size)
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = value as Map<String, Any?>
                    val keyRefs = map.keys.map { encodeObject(it, objects, total) }
                    val valueRefs = map.values.map { encodeObject(it, objects, total) }
                    withLength(0xD0, refsToBytes(keyRefs + valueRefs, total), map.size)
                }

                else -> throw IllegalArgumentException("不支持的 plist 类型 ${value.javaClass.simpleName}")
            }
        objects += bytes
        return objects.size - 1
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII)
        return if (value.all { it.code < 0x80 } && ascii.decodeToString() == value) {
            withLength(0x50, ascii)
        } else {
            withLength(0x60, value.toByteArray(Charsets.UTF_16BE), value.length)
        }
    }

    private fun encodeInt(value: Long): ByteArray {
        val size =
            when {
                value >= 0 && value <= 0xFF -> 1
                value >= 0 && value <= 0xFFFF -> 2
                value >= 0 && value <= 0xFFFFFFFFL -> 4
                else -> 8
            }
        return byteArrayOf((0x10 or (log2(size))).toByte()) + value.toBytes(size)
    }

    private fun withLength(
        marker: Int,
        payload: ByteArray,
        count: Int = payload.size,
    ): ByteArray {
        if (count < 15) return byteArrayOf((marker or count).toByte()) + payload
        val lengthBytes = encodeInt(count.toLong())
        return byteArrayOf((marker or 0x0F).toByte()) + lengthBytes + payload
    }

    private fun refsToBytes(
        refs: List<Int>,
        objectCount: Int,
    ): ByteArray {
        val width = intBytes(objectCount)
        val out = ByteArray(refs.size * width)
        refs.forEachIndexed { index, ref ->
            val value = ref.toLong()
            for (byte in 0 until width) {
                out[index * width + byte] = (value ushr (8 * (width - 1 - byte))).toByte()
            }
        }
        return out
    }

    private fun Long.toBytes(size: Int): ByteArray = writeBytes(this, size)

    private fun writeBytes(
        value: Long,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            (value ushr (8 * (size - 1 - index))).toByte()
        }

    private fun writeUint(
        out: ByteArrayOutputStream,
        value: Long,
        size: Int,
    ) {
        out.write(writeBytes(value, size))
    }

    private fun intBytes(value: Int): Int =
        when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x100000000 -> 4
            else -> 8
        }

    private fun log2(size: Int): Int =
        when (size) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }

    // ---- 解析 ----

    fun parse(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "plist 太短（${bytes.size} 字节）" }
        require(
            bytes[0] == 0x62.toByte() &&
                bytes[1] == 0x70.toByte() &&
                bytes[2] == 0x6C.toByte() &&
                bytes[3] == 0x69.toByte() &&
                bytes[4] == 0x73.toByte() &&
                bytes[5] == 0x74.toByte() &&
                bytes[6] == 0x30.toByte() &&
                bytes[7] == 0x30.toByte(),
        ) { "不是 bplist00" }

        // 尾部 32 字节 trailer：unused(6) offsetIntSize(1) objectRefSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        val offsetIntSize = u8(bytes[bytes.size - 26])
        val objectRefSize = u8(bytes[bytes.size - 25])
        val numObjects = readUint(bytes, bytes.size - 24, 8).toInt()
        val topObject = readUint(bytes, bytes.size - 16, 8).toInt()
        val offsetTableOffset = readUint(bytes, bytes.size - 8, 8).toInt()
        require(topObject < numObjects) { "topObject 越界" }

        val offsets =
            IntArray(numObjects) { index ->
                readUint(bytes, offsetTableOffset + index * offsetIntSize, offsetIntSize).toInt()
            }
        return parseObject(bytes, offsets[topObject], objectRefSize, offsets)
    }

    private fun parseObject(
        bytes: ByteArray,
        offset: Int,
        refSize: Int,
        offsets: IntArray,
    ): Any? {
        val marker = u8(bytes[offset])
        val type = marker ushr 4
        val info = marker and 0xF
        return when (type) {
            0x0 ->
                when (info) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }

            0x1 -> readSigned(bytes, offset + 1, 1 shl info)

            0x2 ->
                when (info) {
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
    private fun elementCount(
        bytes: ByteArray,
        offset: Int,
        info: Int,
    ): Pair<Int, Int> {
        if (info != 0xF) return info to offset + 1
        val intMarker = u8(bytes[offset + 1])
        require(intMarker ushr 4 == 0x1) { "期望长度 int 对象，得到 0x${intMarker.toString(16)}" }
        val intSize = 1 shl (intMarker and 0xF)
        val count = readSigned(bytes, offset + 2, intSize).toInt()
        return count to offset + 2 + intSize
    }

    private fun readSigned(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var value = if (size > 0 && bytes[offset].toInt() and 0x80 != 0) -1L else 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
}
