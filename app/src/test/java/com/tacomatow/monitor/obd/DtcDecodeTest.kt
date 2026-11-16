package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecodeTest {

    @Test
    fun `single stored code decodes`() {
        assertEquals(listOf("P0133"), DtcDecode.decode("03", "7E8 04 43 01 01 33"))
    }

    @Test
    fun `two stored codes decode`() {
        assertEquals(
            listOf("P0300", "P0301"),
            DtcDecode.decode("03", "7E8 06 43 02 03 00 03 01"),
        )
    }

    @Test
    fun `padding pairs are not codes`() {
        assertEquals(
            listOf("P0133"),
            DtcDecode.decode("03", "7E8 07 43 01 01 33 00 00 00"),
        )
    }

    @Test
    fun `body code in pending`() {
        assertEquals(listOf("B0123"), DtcDecode.decode("07", "7E8 04 47 01 81 23"))
    }

    @Test
    fun `network code in permanent`() {
        assertEquals(listOf("U0012"), DtcDecode.decode("0A", "4A 01 C0 12"))
    }

    @Test
    fun `bare capture without PCI decodes`() {
        assertEquals(listOf("P0301"), DtcDecode.decode("03", "43 01 03 01"))
    }

    @Test
    fun `multi-frame reply reassembles`() {
        assertEquals(
            listOf("P0300", "P0301", "P0402", "P0503"),
            DtcDecode.decode(
                "03",
                "7E8 10 0A 43 04 03 00 03 01 7E8 21 04 02 05 03 00 00 00",
            ),
        )
    }

    @Test
    fun `two ECUs both contribute`() {
        assertEquals(
            listOf("P0301", "C1234"),
            DtcDecode.decode("03", "7E8 04 43 01 03 01 7E9 04 43 01 52 34"),
        )
    }

    @Test
    fun `refusal is not a code`() {
        assertTrue(DtcDecode.decode("03", "7E8 03 7F 03 11").isEmpty())
    }

    @Test
    fun `silence is empty`() {
        assertTrue(DtcDecode.decode("03", "NO DATA").isEmpty())
        assertTrue(DtcDecode.decode("03", null).isEmpty())
        assertTrue(DtcDecode.decode("03", "").isEmpty())
    }

    @Test
    fun `unknown mode is empty`() {
        assertTrue(DtcDecode.decode("04", "7E8 04 44 01 03 01").isEmpty())
    }

    @Test
    fun `mil parses light and count`() {
        assertEquals(true to 1, DtcDecode.mil(byteArrayOf(0x81.toByte(), 0, 0, 0)))
        assertEquals(false to 2, DtcDecode.mil(byteArrayOf(0x02, 0, 0, 0)))
        assertNull(DtcDecode.mil(null))
        assertNull(DtcDecode.mil(byteArrayOf()))
    }
}
