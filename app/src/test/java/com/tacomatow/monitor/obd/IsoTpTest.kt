package com.tacomatow.monitor.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTpTest {

    private fun bytes(vararg ints: Int): ByteArray =
        ints.map { it.toByte() }.toByteArray()

    @Test
    fun `single frame spaced`() {
        assertArrayEquals(
            bytes(0x41, 0x01, 0x81),
            IsoTp.messages("7E8 03 41 01 81").single(),
        )
    }

    @Test
    fun `0x12 block reassembles to nine bytes spaced`() {
        // The exact reply from the field log: first frame announces 9, the
        // consecutive frame carries the rest. Old parse showed 4 bytes.
        val raw = "7C8 10 09 61 12 00 20 00 00 7C8 21 00 00 00 00 00 00 00 00"
        val msg = IsoTp.messages(raw).single()
        assertArrayEquals(bytes(0x61, 0x12, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00), msg)
        assertArrayEquals(
            bytes(0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00),
            client.payloadFor("2112", raw),
        )
    }

    @Test
    fun `0x12 block reassembles packed`() {
        val raw = "7C810096112002000007C82100000000000000"
        assertArrayEquals(
            bytes(0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00),
            client.payloadFor("2112", raw),
        )
    }

    @Test
    fun `transmission reply single frame`() {
        assertArrayEquals(
            bytes(0x0A, 0x1C),
            client.payloadFor("221627", "701 05 62 16 27 0A 1C"),
        )
    }

    @Test
    fun `refusal is not data`() {
        assertNull(client.payloadFor("0101", "7E8 03 7F 01 11"))
    }

    @Test
    fun `two ECUs both heard`() {
        val msgs = IsoTp.messages("7E8 04 43 01 03 01 7E9 04 47 01 02 02")
        assertEquals(2, msgs.size)
        assertArrayEquals(bytes(0x43, 0x01, 0x03, 0x01), msgs[0])
        assertArrayEquals(bytes(0x47, 0x01, 0x02, 0x02), msgs[1])
    }

    @Test
    fun `silence and noise give nothing`() {
        assertTrue(IsoTp.messages("NO DATA").isEmpty())
        assertTrue(IsoTp.messages("").isEmpty())
        assertTrue(IsoTp.messages("?").isEmpty())
        assertTrue(IsoTp.messages("7C8 10 09 61 12 00 20").isEmpty())
        assertNull(client.payloadFor("2112", "7C8 10 09 61 12 00 20"))
    }

    @Test
    fun `multiline spaced frames reassemble`() {
        // The ELM prints one CAN frame per line: a first frame and its
        // consecutive frames always land on different lines.
        val raw = "7E8 10 09 61 12 00 20 00 00\r7E8 21 00 00 00 00 00 00 00\r"
        assertArrayEquals(
            bytes(0x61, 0x12, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00),
            IsoTp.messages(raw).single(),
        )
    }

    @Test
    fun `multiline packed lines parse independently`() {
        val raw = "7C80461149A26\r7C90461141122\r"
        val msgs = IsoTp.messages(raw)
        assertEquals(2, msgs.size)
        assertArrayEquals(bytes(0x61, 0x14, 0x9A, 0x26), msgs[0])
        assertArrayEquals(bytes(0x61, 0x14, 0x11, 0x22), msgs[1])
    }

    @Test
    fun `mixed spaced and packed lines both count`() {
        val raw = "7E8 04 43 01 03 01\r7E90443010301"
        val msgs = IsoTp.messages(raw)
        assertEquals(2, msgs.size)
        assertTrue(msgs.all { it[0] == 0x43.toByte() })
    }

    @Test
    fun `gateway 758 reply strips sub-target spaced`() {
        assertArrayEquals(
            bytes(0x61, 0x14, 0x9A, 0x26),
            IsoTp.messages("758 40 04 61 14 9A 26").single(),
        )
        assertArrayEquals(
            bytes(0x9A, 0x26),
            client.payloadFor("2114", "758 40 04 61 14 9A 26"),
        )
    }

    @Test
    fun `gateway 758 reply strips sub-target packed`() {
        assertArrayEquals(
            bytes(0x61, 0x14, 0x9A, 0x26),
            IsoTp.messages("758400461149A26").single(),
        )
    }

    @Test
    fun `gateway multiline reassembles`() {
        val raw = "758 40 10 09 61 12 00 20 00 00\r758 40 21 00 00 00 00 00 00 00\r"
        assertArrayEquals(
            bytes(0x61, 0x12, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00),
            IsoTp.messages(raw).single(),
        )
    }

    @Test
    fun `AA padding tail is tolerated packed`() {
        assertArrayEquals(
            bytes(0x61, 0x14, 0x9A, 0x26),
            IsoTp.messages("7E80461149A26AAAA").single(),
        )
    }

    @Test
    fun `zero padding inside packed does not hang`() {
        // A 00 byte used to wedge the packed parser in an infinite loop:
        // advancing by 2*n with n == 0 never moves.
        assertArrayEquals(
            bytes(0x61, 0x14),
            IsoTp.messages("7C802611400").single(),
        )
    }

    // The real client, with a transport that can never answer: payloadFor is
    // pure parsing and never touches it.
    private val client = Elm327Client(object : ElmTransport {
        override fun open(): Boolean = false
        override fun transact(cmd: String, timeoutMs: Int): String? = null
        override fun close() {}
        override fun transcript(): List<String> = emptyList()
    })
}
