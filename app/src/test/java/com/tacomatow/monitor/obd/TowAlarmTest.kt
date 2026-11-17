package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm must page the driver once per excursion and never over missing
 * data. A gauge hovering on the line buzzing every sweep would get the
 * channel muted, which is how a real overheat gets missed.
 */
class TowAlarmTest {

    @Test
    fun `normal temps stay normal`() {
        assertEquals(
            TowAlarm.Level.NORMAL,
            TowAlarm.levelFor(panF = 195f, convF = 205f),
        )
    }

    @Test
    fun `pan crossing warn trips warn`() {
        assertEquals(TowAlarm.Level.WARN, TowAlarm.levelFor(panF = 220f, convF = 200f))
    }

    @Test
    fun `either gauge can trip crit`() {
        assertEquals(TowAlarm.Level.CRIT, TowAlarm.levelFor(panF = 200f, convF = 250f))
        assertEquals(TowAlarm.Level.CRIT, TowAlarm.levelFor(panF = 241f, convF = 200f))
    }

    @Test
    fun `missing gauges never page`() {
        assertEquals(TowAlarm.Level.NORMAL, TowAlarm.levelFor(panF = null, convF = null))
        assertEquals(TowAlarm.Level.WARN, TowAlarm.levelFor(panF = null, convF = 231f))
    }

    @Test
    fun `only rising edges fire`() {
        assertTrue(TowAlarm.shouldFire(TowAlarm.Level.WARN, TowAlarm.Level.NORMAL))
        assertTrue(TowAlarm.shouldFire(TowAlarm.Level.CRIT, TowAlarm.Level.WARN))
        assertTrue(TowAlarm.shouldFire(TowAlarm.Level.CRIT, TowAlarm.Level.NORMAL))
        assertFalse(TowAlarm.shouldFire(TowAlarm.Level.WARN, TowAlarm.Level.WARN))
        assertFalse(TowAlarm.shouldFire(TowAlarm.Level.CRIT, TowAlarm.Level.CRIT))
        assertFalse(TowAlarm.shouldFire(TowAlarm.Level.NORMAL, TowAlarm.Level.WARN))
    }

    @Test
    fun `clear needs hysteresis not just below warn`() {
        // 219 is below WARN 220 but inside the 5 °F band: still an excursion.
        assertFalse(TowAlarm.cleared(panF = 219f, convF = 200f))
        assertTrue(TowAlarm.cleared(panF = 214f, convF = 200f))
    }

    @Test
    fun `lost signal releases a stale alarm`() {
        assertTrue(TowAlarm.cleared(panF = null, convF = null))
    }

    @Test
    fun `summary never blanks`() {
        assertEquals("Pan 212°F · Conv 225°F", TowAlarm.summaryLine(212f, 225f))
        assertEquals("Pan — · Conv 225°F", TowAlarm.summaryLine(null, 225f))
        assertEquals("Waiting for adapter…", TowAlarm.summaryLine(null, null))
    }
}
