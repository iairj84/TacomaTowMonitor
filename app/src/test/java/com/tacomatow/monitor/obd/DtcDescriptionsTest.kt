package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDescriptionsTest {

    @Test
    fun `exact entries describe common codes`() {
        assertEquals("Catalyst efficiency low, bank 1", DtcDescriptions.describe("P0420"))
        assertEquals(
            "Transmission fluid temperature signal high",
            DtcDescriptions.describe("P0713"),
        )
        assertEquals(
            "Lost communication with the engine computer",
            DtcDescriptions.describe("U0100"),
        )
    }

    @Test
    fun `cylinder families generate`() {
        assertEquals("Cylinder 5 misfire detected", DtcDescriptions.describe("P0305"))
        assertEquals("Cylinder 12 misfire detected", DtcDescriptions.describe("P0312"))
        assertEquals("Fuel injector circuit, cylinder 4", DtcDescriptions.describe("P0204"))
        assertEquals("Ignition coil circuit, cylinder 2", DtcDescriptions.describe("P0352"))
    }

    @Test
    fun `oxygen sensor blocks generate`() {
        assertEquals(
            "Oxygen sensor slow response, bank 1 sensor 1",
            DtcDescriptions.describe("P0133"),
        )
        assertEquals(
            "Oxygen sensor heater circuit fault, bank 2 sensor 2",
            DtcDescriptions.describe("P0161"),
        )
    }

    @Test
    fun `family fallback for the rest`() {
        assertEquals(
            "Auxiliary emission control",
            DtcDescriptions.describe("P0496"),
        )
        assertEquals(
            "Vehicle network communication",
            DtcDescriptions.describe("U0300"),
        )
    }

    @Test
    fun `manufacturer codes get no description`() {
        assertNull(DtcDescriptions.describe("P1604"))
        assertNull(DtcDescriptions.describe("P3000"))
        assertNull(DtcDescriptions.describe("B1000"))
    }

    @Test
    fun `every exact key is a well-formed generic code`() {
        val keys = DtcDescriptions::class.java.getDeclaredField("EXACT").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(DtcDescriptions) as Map<String, String>).keys
        }
        assertTrue(keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(key, key.matches(Regex("[PCBU]0[0-9A-F]{3}")))
            assertTrue(key, DtcDescriptions.describe(key) != null)
        }
    }
}
