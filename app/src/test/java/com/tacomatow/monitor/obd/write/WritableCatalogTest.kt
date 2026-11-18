package com.tacomatow.monitor.obd.write

import com.tacomatow.monitor.obd.ChangeRecord
import com.tacomatow.monitor.obd.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritableCatalogTest {

    private val truck = VehicleProfile(year = 2019, trim = "off road", calibration = "896")

    private fun record(
        label: String = "feedback",
        header: String = "750",
        lid: Int = 0x14,
        byte: Int = 0,
        before: String = "9A",
        after: String = "92",
        year: Int? = 2019,
        trim: String = "off road",
        cal: String = "896",
    ) = ChangeRecord(
        label = label, header = header, ecuLabel = "Main body ECU (gateway 0x40)",
        lid = lid, byteIndex = byte, before = before, after = after,
        at = 0L, year = year, trim = trim, calibration = cal,
    )

    @Test
    fun `safest setting matches its own truck`() {
        assertTrue(WritableCatalog.vehicleMatches(record(), truck))
        assertTrue(WritableCatalog.isSafest(record()))
    }

    @Test
    fun `unstamped record never matches`() {
        assertFalse(WritableCatalog.vehicleMatches(record(year = null, trim = "", cal = ""), truck))
    }

    @Test
    fun `different calibration does not match`() {
        assertFalse(WritableCatalog.vehicleMatches(record(cal = "123"), truck))
    }

    @Test
    fun `unset profile matches nothing`() {
        assertFalse(WritableCatalog.vehicleMatches(record(), VehicleProfile()))
    }

    @Test
    fun `0x11 is held even on the right truck`() {
        val r = record(label = "lock", lid = 0x11, byte = 1, before = "06", after = "0A")
        val reason = WritableCatalog.holdReason(r, truck)
        assertTrue(reason!!.contains("lock AND unlock"))
    }

    @Test
    fun `allowlisted single-bit passes`() {
        assertNull(WritableCatalog.holdReason(record(), truck))
    }

    @Test
    fun `upper-range LID is held`() {
        val reason = WritableCatalog.holdReason(record(lid = 0xAB, before = "06", after = "05"), truck)
        assertTrue(reason!!.contains("allowlist"))
    }

    @Test
    fun `volatile bit is held`() {
        val noise = mapOf(("750" to 0x14) to listOf(0x08))
        val reason = WritableCatalog.holdReason(record(), truck, noise)
        assertTrue(reason!!.contains("moves on its own"))
    }

    @Test
    fun `split separates writable from held with lock reason`() {
        val records = listOf(
            record(),
            record(label = "lock", lid = 0x11, byte = 1, before = "06", after = "0A"),
        )
        val (writable, held) = WritableCatalog.split(records, truck)
        assertEquals(1, writable.size)
        assertEquals(listOf("92", "9A"), writable.first().allowedValues)
        assertEquals(1, held.size)
    }
}
