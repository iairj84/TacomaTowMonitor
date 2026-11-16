package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ClientTest {

    // A scripted adapter: identity, session reply and OKs for AT chatter.
    private fun clientFor(sessionReply: String?) = Elm327Client(object : ElmTransport {
        override fun open(): Boolean = true
        override fun transact(cmd: String, timeoutMs: Int): String? = when {
            cmd == "ATI" -> "ELM327 v1.5"
            cmd == "1003" -> sessionReply
            cmd.startsWith("AT") || cmd.isEmpty() -> "OK"
            else -> null
        }
        override fun close() {}
        override fun transcript(): List<String> = emptyList()
    }).also { it.connect() }

    @Test
    fun `packed refusal is detected`() {
        assertEquals("7F 22 31", clientFor(null).negativeResponse("7E8037F2231"))
    }

    @Test
    fun `multiline refusal is detected`() {
        assertEquals(
            "7F 22 31",
            clientFor(null).negativeResponse("7E8 03 7F 22 31\r7E9 03 7F 22 31"),
        )
    }

    @Test
    fun `data byte 0x7F is not a refusal`() {
        // The old scan for a bare 7F token mistook the middle of this payload
        // for a refusal of service 0x26.
        assertNull(clientFor(null).negativeResponse("7E8 05 61 14 7F 26 11"))
    }

    @Test
    fun `headers-on extended session is accepted`() {
        // With headers on the reply starts with the CAN ID, which the old
        // prefix check could never match - every session looked refused.
        assertTrue(clientFor("7E8 06 50 03 00 32 01 F4").enterExtendedSession())
    }

    @Test
    fun `packed extended session is accepted`() {
        assertTrue(clientFor("7E8065003003201F4").enterExtendedSession())
    }

    @Test
    fun `silent extended session is refused`() {
        assertFalse(clientFor("NO DATA").enterExtendedSession())
    }

    // Gateway framing: records what would go to the adapter. The adapter's
    // own length byte is the point — prefixing the sub-target by hand made the
    // frame leave as "04 40 02 21 14", which the gateway reads as sub-target 4.
    private fun recordingClient(sent: MutableList<String>, ceaReply: String) =
        Elm327Client(object : ElmTransport {
            override fun open(): Boolean = true
            override fun transact(cmd: String, timeoutMs: Int): String? {
                sent += cmd
                return when {
                    cmd == "ATCEA40" -> ceaReply
                    cmd == "ATI" -> "ELM327 v1.5"
                    cmd.startsWith("AT") || cmd.isEmpty() -> "OK"
                    else -> null
                }
            }
            override fun close() {}
            override fun transcript(): List<String> = emptyList()
        }).also { it.connect() }

    @Test
    fun `gateway sends a plain read when the adapter accepts CEA`() {
        val sent = mutableListOf<String>()
        val c = recordingClient(sent, ceaReply = "OK")
        assertEquals(Elm327Client.GatewayFraming.ADAPTER_EXTENDED, c.beginGateway(0x40))
        c.readGatewayLid(0x40, 0x14)
        assertTrue("no CEA", sent.contains("ATCEA40"))
        assertTrue("no tester address", sent.contains("ATTA40"))
        assertTrue("no flow control data", sent.contains("ATFCSD40300000"))
        assertEquals("2114", sent.last())
    }

    @Test
    fun `gateway falls back to raw framing when CEA is refused`() {
        val sent = mutableListOf<String>()
        val c = recordingClient(sent, ceaReply = "?")
        assertEquals(Elm327Client.GatewayFraming.MANUAL, c.beginGateway(0x40))
        c.readGatewayLid(0x40, 0x14)
        assertTrue("formatting still on", sent.contains("ATCAF0"))
        assertEquals("40022114", sent.last())
    }

    @Test
    fun `keep-alive through the gateway keeps the header`() {
        val sent = mutableListOf<String>()
        val c = recordingClient(sent, ceaReply = "OK")
        c.beginGateway(0x40)
        val headerChanges = sent.count { it.startsWith("AT SH") }
        c.sendTesterPresent(Elm327Client.GATEWAY_HEADER, 0x40)
        assertEquals("header flipped", headerChanges, sent.count { it.startsWith("AT SH") })
        assertEquals("3E80", sent.last())
    }
}
