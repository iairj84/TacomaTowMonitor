package com.tacomatow.monitor.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripSummaryTest {

    private fun csv(name: String = "tacoma-tow-20260214-085500.csv", vararg rows: String): File {
        val f = File(Files.createTempDirectory("trips").toFile(), name)
        f.writeText(
            "time,trans_pan_f,trans_conv_f,coolant_f,oil_f,rpm,speed_mph,volts\n" +
                rows.joinToString("\n"),
        )
        return f
    }

    private fun row(t: String, pan: String = "", conv: String = "", coolant: String = "") =
        "$t,$pan,$conv,$coolant,,,,,"

    @Test
    fun `max temps and time above threshold`() {
        val s = TripSummaries.parse(csv(
            rows = arrayOf(
                row("08:55:00", "210", "215", "190"),
                row("08:55:10", "225", "231", "192"),
                row("08:55:20", "230", "241", "195"),
                row("08:55:30", "215", "218", "193"),
            ),
        ))!!
        assertEquals(230f, s.maxPanF)
        assertEquals(241f, s.maxConvF)
        assertEquals(195f, s.maxCoolantF)
        assertEquals(20L, s.abovePanSec)
        assertEquals(20L, s.aboveConvSec)
        assertEquals(30L, s.durationSec)
        assertEquals(4, s.samples)
        assertEquals("2026-02-14 08:55", s.dateLabel)
    }

    @Test
    fun `midnight crossing counts forward`() {
        val s = TripSummaries.parse(csv(
            rows = arrayOf(
                row("23:59:55", "225", "225", "190"),
                row("00:00:05", "226", "226", "190"),
            ),
        ))!!
        assertEquals(10L, s.durationSec)
        assertEquals(10L, s.abovePanSec)
    }

    @Test
    fun `long pauses are capped`() {
        val s = TripSummaries.parse(csv(
            rows = arrayOf(
                row("08:55:00", "225", "225", "190"),
                row("09:00:00", "226", "226", "190"),
            ),
        ))!!
        assertEquals(120L, s.durationSec)
    }

    @Test
    fun `malformed rows are skipped`() {
        val s = TripSummaries.parse(csv(
            rows = arrayOf(
                "",
                "nonsense",
                "08:55:00,210",
                row("08:55:10", "225", "225", "190"),
            ),
        ))!!
        assertEquals(1, s.samples)
        assertEquals(0L, s.durationSec)
    }

    @Test
    fun `empty and missing files give nothing`() {
        assertNull(TripSummaries.parse(csv(rows = arrayOf())))
        assertNull(TripSummaries.parse(File("/nonexistent/trip.csv")))
    }

    @Test
    fun `share text reads like a report`() {
        val s = TripSummaries.parse(csv(
            rows = arrayOf(
                row("08:55:00", "210", "215", "190"),
                row("08:55:10", "227", "231", "192"),
            ),
        ))!!
        val text = s.shareText()
        assertTrue(text.contains("2026-02-14 08:55"))
        assertTrue(text.contains("227"))
        assertTrue(text.contains("231"))
        assertTrue(text.contains("above 220"))
    }

    @Test
    fun `formatting`() {
        assertEquals("45s", TripSummaries.fmtDur(45))
        assertEquals("38 min", TripSummaries.fmtDur(2280))
        assertEquals("1h 05min", TripSummaries.fmtDur(3900))
        assertEquals("45s", TripSummaries.fmtAbove(45))
        assertEquals("4m 12s", TripSummaries.fmtAbove(252))
    }
}
