package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    private fun fixture(name: String): Transcript.Record {
        val stream = javaClass.getResourceAsStream("/transcripts/$name")
            ?: throw AssertionError("missing test fixture /transcripts/$name")
        return Transcript.parse(stream.bufferedReader().readText())
    }

    // ---- the format ----

    @Test
    fun `a multi-line reply survives the round trip`() {
        val entries = listOf(
            Transcript.Entry("2182", 1200, "758 40 10 0A 61 82 A1 A2\r758 40 21 A3"),
        )
        val text = Transcript.encode(entries, mapOf("app" to "9 (9)"))
        val parsed = Transcript.parse(text)
        assertEquals(entries, parsed.entries)
        assertEquals("9 (9)", parsed.header["app"])
        // Escaped on disk so one exchange is one line.
        assertTrue(text, text.contains("A1 A2\\r758"))
    }

    @Test
    fun `no answer is recorded as a distinct case`() {
        val parsed = Transcript.parse(Transcript.encode(listOf(Transcript.Entry("2114", 700, null)), emptyMap()))
        assertEquals(1, parsed.entries.size)
        assertNull(parsed.entries[0].reply)
        assertEquals(700, parsed.entries[0].timeoutMs)
    }

    @Test
    fun `an empty command keeps its own entry`() {
        // The abort carriage return is a real request; swallowing it would
        // shift every later reply by one entry.
        val parsed = Transcript.parse(Transcript.encode(listOf(Transcript.Entry("", 300, null)), emptyMap()))
        assertEquals("", parsed.entries[0].command)
    }

    @Test
    fun `a command with spaces is kept whole`() {
        val parsed = Transcript.parse(Transcript.encode(listOf(Transcript.Entry("AT SH 750", 3000, "OK")), emptyMap()))
        assertEquals("AT SH 750", parsed.entries[0].command)
        assertEquals(3000, parsed.entries[0].timeoutMs)
    }

    // ---- record, then replay ----

    @Test
    fun `recording and replaying the same script gives the same replies`() {
        val script = mapOf(
            "ATI" to "ELM327 v1.5",
            "0105" to "7E8 03 41 05 7B",
            "221627" to "7E8 07 62 16 27 3C 80 00",
        )
        val recorder = TranscriptRecorder()
        val live = RecordingTransport(
            object : ElmTransport {
                override fun open() = true
                override fun transact(cmd: String, timeoutMs: Int): String? = script[cmd]
                override fun close() {}
                override fun transcript(): List<String> = emptyList()
            },
            recorder,
        )
        recorder.start()
        script.keys.forEach { live.transact(it, 1_000) }
        val entries = recorder.stop()

        val replay = ReplayTransport(Transcript.parse(Transcript.encode(entries, emptyMap())))
        script.forEach { (cmd, reply) -> assertEquals(reply, replay.transact(cmd, 1_000)) }
        replay.assertComplete()
    }

    @Test
    fun `a recording that was never started captures nothing`() {
        val recorder = TranscriptRecorder()
        val wrapped = RecordingTransport(
            object : ElmTransport {
                override fun open() = true
                override fun transact(cmd: String, timeoutMs: Int): String? = "OK"
                override fun close() {}
                override fun transcript(): List<String> = emptyList()
            },
            recorder,
        )
        wrapped.transact("ATI", 1_000)
        assertTrue(recorder.stop().isEmpty())
    }

    @Test
    fun `a replay that drifts from the recording fails loudly`() {
        val record = Transcript.parse(
            Transcript.encode(listOf(Transcript.Entry("ATI", 3000, "ELM327 v1.5")), emptyMap()),
        )
        val replay = ReplayTransport(record)
        val error = runCatching { replay.transact("0100", 4_000) }.exceptionOrNull()
        assertTrue("no failure for a command that was not recorded", error != null)
        assertTrue(error!!.message!!, error.message!!.contains("ATI"))
    }

    // ---- a recorded session, replayed through the real client ----

    @Test
    fun `the gateway fixture drives the real client end to end`() {
        val record = fixture("gateway-and-pending.txt")
        val replay = ReplayTransport(record)
        val c = Elm327Client(replay)

        assertTrue("connect failed", c.connect())
        assertTrue("adapter identity not established", c.identityVerified())
        assertEquals(Elm327Client.GatewayFraming.ADAPTER_EXTENDED, c.beginGateway(0x40))

        // Single-frame block through the gateway: the sub-target is framing.
        val block = c.readGatewayLid(0x40, 0x14, 1_200)
        assertEquals("9A", CustomizeExplorer.hex(c.payloadFor("2114", block!!, 0x40)))

        // Multi-frame block: the second line carries 758/40 again, and only the
        // whole 10-byte message may be reported.
        val long = c.readGatewayLid(0x40, 0x82, 1_200)
        assertEquals(
            "A1 A2 A3 A4 A5 A6 A7 A8",
            CustomizeExplorer.hex(c.payloadFor("2182", long!!, 0x40)),
        )

        c.endGateway()

        // Response pending then data: the client must widen its window and come
        // back with the answer, not a refusal or a blank.
        val answer = c.readRaw(null, "2114", 900)
        assertTrue("answer lost: $answer", answer!!.replace(" ", "").contains("6114"))
        assertNull("78 reported as a refusal", c.negativeResponse(answer, "2114"))
        assertTrue(
            "the pending retry did not widen the adapter window",
            record.entries.any { it.command == "2114" && it.timeoutMs == 5_000 },
        )
        replay.assertComplete()
    }
}
