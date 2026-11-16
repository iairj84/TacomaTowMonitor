package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterCheckTest {

    private fun evaluate(
        baseline: String? = "7E8 06 41 00 BE 3E B8 11",
        session: String? = null,
        trans: String? = null,
        body: String? = null,
    ) = AdapterCheck.evaluate(
        identity = "ELM327 v1.5",
        protocol = "6",
        baselineRaw = baseline,
        sessionRaw = session,
        transRaw = trans,
        bodyRaw = body,
    )

    @Test
    fun `headers-on session with answering trans is extended-ok`() {
        // The old prefix check never matched "7E8 06 50 03 ...", so every
        // adapter looked session-refused even while extended data flowed.
        val report = evaluate(
            session = "7E8 06 50 03 00 32 01 F4",
            trans = "701 05 62 16 27 0A 1C",
        )
        assertEquals(AdapterVerdict.EXTENDED_OK, report.verdict)
    }

    @Test
    fun `gateway-routed body answer counts as extended data`() {
        // Main Body answers on 758 with its sub-target first - still data.
        val report = evaluate(
            session = "7E8 06 50 03 00 32 01 F4",
            body = "758 40 04 61 14 9A 26",
        )
        assertEquals(AdapterVerdict.EXTENDED_OK, report.verdict)
    }

    @Test
    fun `refused session with silent ECUs is still session-refused`() {
        val report = evaluate(session = "7E8 03 7F 10 12")
        assertEquals(AdapterVerdict.SESSION_REFUSED, report.verdict)
    }
}
