package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizeDiffTest {

    private fun reading(header: String, label: String, lid: Int, hex: String) =
        LidReading(header, label, lid, raw = "758 04 61 %02X $hex".format(lid), payloadHex = hex)

    private fun diffOf(before: List<LidReading>, after: List<LidReading>) =
        CustomizeExplorer.diff(before, after)

    @Test
    fun `one bit in one module is a candidate`() {
        val diff = diffOf(
            listOf(reading("750", "Main body ECU", 0x14, "65")),
            listOf(reading("750", "Main body ECU", 0x14, "25")),
        )
        assertEquals(1, diff.ecusChanged)
        assertFalse(diff.hasSpread)
        assertEquals(1, diff.candidates.size)
        assertTrue(diff.changes.single().isSingleBit)
    }

    @Test
    fun `the same toggle plus meter and AC drift is flagged as spread`() {
        // The shape of a real run: Main Body answered the setting, the meter
        // and A/C amplifier moved because the truck was in a different state.
        val before = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("7C0", "Combination meter", 0x23, "7A"),
            reading("7C4", "A/C amplifier", 0x26, "AC"),
        )
        val after = listOf(
            reading("750", "Main body ECU", 0x14, "25"),
            reading("7C0", "Combination meter", 0x23, "95"),
            reading("7C4", "A/C amplifier", 0x26, "D1"),
        )
        val diff = diffOf(before, after)
        assertEquals(3, diff.ecusChanged)
        assertTrue(diff.hasSpread)
        // No block can be called the setting when three modules moved.
        assertTrue(diff.candidates.isEmpty())
    }

    @Test
    fun `a seven bit move is not a single option`() {
        val diff = diffOf(
            listOf(reading("7C0", "Combination meter", 0x23, "7A")),
            listOf(reading("7C0", "Combination meter", 0x23, "95")),
        )
        val change = diff.changes.single()
        assertEquals(7, change.changedBitCount)
        assertFalse(change.isSingleBit)
        assertTrue(diff.candidates.isEmpty())
    }

    @Test
    fun `an unchanged sweep has no candidates`() {
        val same = listOf(reading("750", "Main body ECU", 0x14, "65"))
        val diff = diffOf(same, same)
        assertTrue(diff.identical)
        assertTrue(diff.candidates.isEmpty())
        assertEquals(0, diff.ecusChanged)
    }

    @Test
    fun `three control scans find the blocks that move on their own`() {
        // Same truck, nothing changed: the meter and A/C drift, the body
        // block holds still.
        val body = reading("750", "Main body ECU", 0x14, "65")
        val runs = listOf(
            listOf(body, reading("7C0", "Combination meter", 0x23, "7A"), reading("7C4", "A/C amplifier", 0x26, "AC")),
            listOf(body, reading("7C0", "Combination meter", 0x23, "95"), reading("7C4", "A/C amplifier", 0x26, "D1")),
            listOf(body, reading("7C0", "Combination meter", 0x23, "84"), reading("7C4", "A/C amplifier", 0x26, "B8")),
        )
        val volatile = CustomizeNoise.volatileAcross(runs)
        assertEquals(setOf("7C0" to 0x23, "7C4" to 0x26), volatile)
    }

    @Test
    fun `a learned noise set leaves only the real change`() {
        // The whole point: with noise subtracted, the same muddy diff reads
        // as one candidate instead of three modules of churn.
        val before = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("7C0", "Combination meter", 0x23, "7A"),
            reading("7C4", "A/C amplifier", 0x26, "AC"),
        )
        val after = listOf(
            reading("750", "Main body ECU", 0x14, "25"),
            reading("7C0", "Combination meter", 0x23, "95"),
            reading("7C4", "A/C amplifier", 0x26, "D1"),
        )
        val learned = setOf("7C0" to 0x23, "7C4" to 0x26)
        val diff = CustomizeExplorer.diff(before, after, volatile = learned)
        assertEquals(1, diff.changes.size)
        assertEquals(0x14, diff.changes.single().lid)
        assertEquals(2, diff.noise.size)
        assertFalse(diff.hasSpread)
        assertEquals(1, diff.candidates.size)
    }

    @Test
    fun `two runs are the minimum for learning noise`() {
        val one = listOf(listOf(reading("750", "Main body ECU", 0x14, "65")))
        assertTrue(CustomizeNoise.volatileAcross(one).isEmpty())
    }

    @Test
    fun `shared text warns about spread`() {
        val diff = diffOf(
            listOf(
                reading("750", "Main body ECU", 0x14, "65"),
                reading("7C0", "Combination meter", 0x13, "8D"),
            ),
            listOf(
                reading("750", "Main body ECU", 0x14, "25"),
                reading("7C0", "Combination meter", 0x13, "8C"),
            ),
        )
        val text = CustomizeExplorer.describe(diff, "before", "after")
        assertTrue(text.contains("WARNING: 2 modules changed"))
    }
}
