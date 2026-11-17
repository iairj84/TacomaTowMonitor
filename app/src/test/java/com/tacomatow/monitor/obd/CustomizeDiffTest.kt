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
        val learned = mapOf(("7C0" to 0x23) to listOf(0xFF), ("7C4" to 0x26) to listOf(0xFF))
        val diff = CustomizeExplorer.diff(before, after, noiseMasks = learned)
        assertEquals(1, diff.changes.size)
        assertEquals(0x14, diff.changes.single().lid)
        assertEquals(2, diff.noise.size)
        assertFalse(diff.hasSpread)
        assertEquals(1, diff.candidates.size)
    }

    @Test
    fun `a stable bit in a lively block still counts`() {
        // The user's caution: block 0x23 drifts in byte 0's low bits, but
        // the setting lives in steady byte 1. Per-block filtering would
        // hide it; per-bit filtering keeps it while still showing the drift.
        val before = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("7C0", "Combination meter", 0x23, "7A 00"),
        )
        val after = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("7C0", "Combination meter", 0x23, "7B 01"),
        )
        // Idle scans saw only byte 0's low two bits drift.
        val mask = mapOf(("7C0" to 0x23) to listOf(0x03, 0x00))
        val diff = CustomizeExplorer.diff(before, after, noiseMasks = mask)
        assertEquals(1, diff.changes.size)
        assertEquals(0x23, diff.changes.single().lid)
        // The change holds only steady byte 1; the drift stays visible.
        assertEquals(listOf(1), diff.changes.single().byteChanges.map { it.index })
        assertEquals(1, diff.noise.size)
        assertEquals(listOf(0), diff.noise.single().byteChanges.map { it.index })
    }

    @Test
    fun `masks learn per-bit drift from idle scans`() {
        val body = reading("750", "Main body ECU", 0x14, "65")
        val runs = listOf(
            listOf(body, reading("7C0", "Combination meter", 0x23, "7A")),
            listOf(body, reading("7C0", "Combination meter", 0x23, "7B")),
            listOf(body, reading("7C0", "Combination meter", 0x23, "79")),
        )
        val masks = CustomizeNoise.masksAcross(runs)
        // 7A^7B = 01, 7A^79 = 03: only the low two bits ever moved.
        assertEquals(listOf(0x03), masks["7C0" to 0x23])
        assertFalse(masks.containsKey("750" to 0x14))
    }

    @Test
    fun `a change confined to drifting bits is fully ignored but still shown`() {
        val before = listOf(reading("7C0", "Combination meter", 0x23, "7A"))
        val after = listOf(reading("7C0", "Combination meter", 0x23, "7B"))
        val mask = mapOf(("7C0" to 0x23) to listOf(0x03))
        val diff = CustomizeExplorer.diff(before, after, noiseMasks = mask)
        assertTrue(diff.changes.isEmpty())
        assertEquals(1, diff.noise.size)
        val text = CustomizeExplorer.describe(diff, "before", "after")
        assertTrue(text.contains("ignored (bits known to move on their own)"))
        assertTrue(text.contains("0x23"))
    }

    @Test
    fun `discovery sweeps the full LID range`() {
        // The low range was an untested assumption; one wide sweep pays the
        // cost once and the shortlist absorbs the answers.
        assertEquals(0x01..0xFF, CustomizeExplorer.LID_RANGE)
        assertEquals(4 * 255, CustomizeExplorer.totalProbes)
    }

    @Test
    fun `two runs are the minimum for learning noise`() {
        val one = listOf(listOf(reading("750", "Main body ECU", 0x14, "65")))
        assertTrue(CustomizeNoise.volatileAcross(one).isEmpty())
    }

    @Test
    fun `blocks the baseline never probed are not reported as gained`() {
        // A baseline from a narrower sweep must not claim its missing upper
        // blocks "answered only after the change".
        val before = listOf(reading("750", "Main body ECU", 0x14, "65"))
        val after = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("750", "Main body ECU", 0xA4, "00"),
        )
        val diff = CustomizeExplorer.diff(before, after)
        assertTrue(diff.gained.isEmpty())
        assertEquals(1, diff.unbaselined)
        assertTrue(
            CustomizeExplorer.describe(diff, "before", "after")
                .contains("never probed in the baseline"),
        )
    }

    @Test
    fun `a block that was asked and silent then answers is a real gain`() {
        val before = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            LidReading("750", "Main body ECU", 0xA4, raw = "NO DATA", payloadHex = null),
        )
        val after = listOf(
            reading("750", "Main body ECU", 0x14, "65"),
            reading("750", "Main body ECU", 0xA4, "00"),
        )
        val diff = CustomizeExplorer.diff(before, after)
        assertEquals(1, diff.gained.size)
        assertEquals(0, diff.unbaselined)
    }

    @Test
    fun `spread diff still ranks the single-bit mover first`() {
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
        assertTrue(diff.hasSpread)
        assertEquals(0x14, diff.topCandidate!!.lid)
        assertEquals(
            listOf(0x14, 0x26, 0x23),
            diff.rankedChanges.map { it.lid },
        )
    }

    @Test
    fun `survey lists header lid length and payload`() {
        val text = CustomizeExplorer.describeSurvey(
            listOf(
                reading("750", "Main body ECU", 0xA4, "12 34"),
                reading("7C0", "Combination meter", 0x11, "AB"),
                LidReading("7C0", "Combination meter", 0x12, raw = null, payloadHex = null),
            ),
            "2019 TRD",
        )
        assertTrue(text.contains("2019 TRD"))
        assertTrue(text.contains("2 blocks answered"))
        assertTrue(text.contains("0xA4 (2 bytes): 12 34"))
        assertTrue(text.contains("0x11 (1 byte): AB"))
        assertFalse(text.contains("0x12"))
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
