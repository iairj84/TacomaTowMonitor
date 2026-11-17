package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreezeFrameTest {

    @Test
    fun `the request carries the pid and the frame`() {
        assertEquals("020C00", FreezeFrame.request(0x0C, 0))
        assertEquals("020501", FreezeFrame.request(0x05, 1))
    }

    @Test
    fun `spaced reply is read after the frame echo`() {
        val payload = FreezeFrame.data("7E8 04 42 05 00 7B", 0x05, 0)
        assertEquals(listOf<Byte>(0x7B), payload?.toList())
    }

    @Test
    fun `packed reply is read too`() {
        val payload = FreezeFrame.data("7E8044205007B", 0x05, 0)
        assertEquals(listOf<Byte>(0x7B), payload?.toList())
    }

    @Test
    fun `a value from another frame is not this frame's`() {
        // The frame byte in the echo is what keeps 01 from answering as 00.
        assertNull(FreezeFrame.data("7E8 05 42 0C 01 1F 40", 0x0C, 0))
    }

    @Test
    fun `the code that set the frame is decoded`() {
        assertEquals("P0171", FreezeFrame.dtc("7E8 05 42 02 00 01 71", 0))
    }

    @Test
    fun `an empty frame names no code`() {
        assertNull(FreezeFrame.dtc("7E8 05 42 02 00 00 00", 0))
        assertNull(FreezeFrame.dtc("NO DATA", 0))
    }

    @Test
    fun `supported bitmap matches the mode 01 bit layout`() {
        val bitmap = FreezeFrame.supported(byteArrayOf(0x80.toByte(), 0x10, 0x00, 0x00))
        assertEquals(setOf(0x01, 0x0C), bitmap)
    }

    @Test
    fun `a reply too short to be a bitmap is rejected`() {
        assertNull(FreezeFrame.supported(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `rpm is two bytes`() {
        val r = FreezeFrame.reading(0x0C, byteArrayOf(0x1F, 0x40))
        assertEquals("RPM", r?.label)
        assertEquals("2000", r?.text)
    }

    @Test
    fun `coolant is shown in fahrenheit`() {
        // 0x5A = 90, minus the 40 offset is 50 °C.
        assertEquals("122 °F", FreezeFrame.reading(0x05, byteArrayOf(0x5A))?.text)
    }

    @Test
    fun `module voltage is millivolts across two bytes`() {
        assertEquals("13.7 V", FreezeFrame.reading(0x42, byteArrayOf(0x35, 0xB0.toByte()))?.text)
    }

    @Test
    fun `a pid outside the watched set is not invented`() {
        assertNull(FreezeFrame.reading(0x03, byteArrayOf(0x02)))
    }

}
