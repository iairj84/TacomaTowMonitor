package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TacomaPidsTest {

    @Test
    fun `defFor keeps the 21-82 formula into polling`() {
        // The same bytes decoded 57 °F at detection and 14 °F while polling,
        // because polling always applied the 1627 equation.
        val candidate = TacomaPids.TransCandidate("7E0", "21", "82", "82", "21/82@7E0")
        val def = TacomaPids.defFor(candidate, "trans_pan", "Trans Pan", 220f, 240f)
        val bytes = byteArrayOf(0x10, 0x27, 0x00, 0x00)
        assertEquals(TacomaPids.decodeTrans("82", bytes), TacomaPids.decode(def, bytes))
        assertNotEquals(TacomaPids.decodeTrans("1627", bytes), TacomaPids.decode(def, bytes))
    }

    @Test
    fun `1627 default still decodes`() {
        val bytes = byteArrayOf(0x0A, 0x1C)
        assertEquals(
            TacomaPids.decodeTrans("1627", bytes),
            TacomaPids.decode(TacomaPids.TRANS_PAN, bytes),
        )
    }
}
