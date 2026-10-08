package com.lizhihua.carplaysender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 用 macOS plutil 生成的真实 bplist00 向量验证解析器。 */
class BinaryPlistTest {

    private val vector = Base64.getDecoder().decode(
        "YnBsaXN0MDDXAQIDBAUGBwgJFxgZGhtcYXVkaW9MYXRlbmN5WGRpc3BsYXlzWGRldmljZUlEVW1vZGVsV3Jhd1Np" +
            "emVYZmVhdHVyZXNUbmFtZRAAoQrWCwwNDg8QERITFBURWlVwc2lkZURvd25bUmVmcmVzaFJhdGVdV2lkdGhQaHlz" +
            "aWNhbFRVVUlEXkhlaWdodFBoeXNpY2FsWFJvdGF0aW9uCCNATgAAAAAAABEDIF8QJGI3ZTZjNWEwLTExMTEtNDAw" +
            "MC04MDAwLTAwMDAwMDAwMDAwMREB4AhfEBFGQToyMjo5MDoxMzozQjoxOFpEaVBsYXkyMCwxRgABAgMEBRJWYcyi" +
            "VkRpUGxheQAIABcAJAAtADYAPABEAE0AUgBUAFYAYwBuAHoAiACNAJwApQCmAK8AsgDZANwA3QDxAPwBAwEIAAAA" +
            "AAAAAgEAAAAAAAAAHAAAAAAAAAAAAAAAAAAAAQ8=",
    )

    @Test
    fun parsesTopLevelDictScalars() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        assertEquals("DiPlay", root["name"])
        assertEquals("FA:22:90:13:3B:18", root["deviceID"])
        assertEquals("DiPlay20,1", root["model"])
        assertEquals(1449249954L, root["features"])
        assertEquals(0L, root["audioLatency"])
    }

    @Test
    fun parsesNestedDisplayArray() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        val displays = root["displays"] as List<*>
        assertEquals(1, displays.size)
        val display = displays[0] as Map<*, *>
        assertEquals("b7e6c5a0-1111-4000-8000-000000000001", display["UUID"])
        assertEquals(800L, display["WidthPhysical"])
        assertEquals(480L, display["HeightPhysical"])
        assertEquals(60.0, display["RefreshRate"])
        assertFalse(display["Rotation"] as Boolean)
        assertFalse(display["UpsideDown"] as Boolean)
    }

    @Test
    fun parsesDataBlob() {
        val root = BinaryPlist.parse(vector) as Map<*, *>
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), root["rawSize"] as ByteArray)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPlistInput() {
        BinaryPlist.parse("not a plist at all".toByteArray())
    }

    @Test
    fun parsesHandCraftedMinimalPlist() {
        // 最小 bplist：唯一对象 true @8，offset 表 @9（1 字节 = 0x08），尾部 trailer
        val minimal = byteArrayOf(
            0x62, 0x70, 0x6C, 0x69, 0x73, 0x74, 0x30, 0x30, // bplist00
            0x09, // object #0 = true
            0x08, // offset 表：object#0 在偏移 8
            0, 0, 0, 0, 0, 0, 1, 1, // offsetIntSize=1, objectRefSize=1
            0, 0, 0, 0, 0, 0, 0, 1, // numObjects=1
            0, 0, 0, 0, 0, 0, 0, 0, // topObject=0
            0, 0, 0, 0, 0, 0, 0, 9, // offsetTableOffset=9
        )
        assertEquals(java.lang.Boolean.TRUE, BinaryPlist.parse(minimal))
    }
}
