package com.tacomatow.monitor.obd

import java.io.File

/**
 * One logged tow trip, reduced to what matters after the fact: how hot the
 * transmission got, and for how long it stayed hot.
 *
 * The CSV rows are not evenly spaced — a sweep costs what it costs — so
 * time-above-threshold is measured from actual timestamps: each row owns the
 * interval since the previous row. Single gaps are capped at two minutes so
 * a pause with the app still recording does not invent an hour at 230 °F.
 */
data class TripSummary(
    val fileName: String,
    val dateLabel: String,
    val samples: Int,
    val durationSec: Long,
    val maxPanF: Float?,
    val maxConvF: Float?,
    val maxCoolantF: Float?,
    val abovePanSec: Long,
    val aboveConvSec: Long,
    val thresholdF: Float,
) {
    fun shareText(): String = buildString {
        appendLine("Tacoma tow trip — $dateLabel")
        appendLine("$samples samples over ${TripSummaries.fmtDur(durationSec)} ($fileName)")
        appendLine("Trans pan:  ${temp(maxPanF)} · ${TripSummaries.fmtAbove(abovePanSec)} above ${thresholdF.toInt()}°F")
        appendLine("Converter:  ${temp(maxConvF)} · ${TripSummaries.fmtAbove(aboveConvSec)} above ${thresholdF.toInt()}°F")
        append("Coolant:    ${temp(maxCoolantF)} max")
    }

    private fun temp(f: Float?): String =
        if (f == null) "—" else "max %.0f°F".format(f)
}

object TripSummaries {

    fun parse(file: File, thresholdF: Float = 220f): TripSummary? {
        val lines = try { file.readLines() } catch (_: Exception) { return null }
        // time,trans_pan_f,trans_conv_f,coolant_f,oil_f,rpm,speed_mph,volts
        val rows = lines.drop(1).mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 4) return@mapNotNull null
            val t = p[0].split(":").let { hms ->
                if (hms.size != 3) return@mapNotNull null
                val h = hms[0].toIntOrNull() ?: return@mapNotNull null
                val m = hms[1].toIntOrNull() ?: return@mapNotNull null
                val s = hms[2].toIntOrNull() ?: return@mapNotNull null
                h * 3600 + m * 60 + s
            }
            Row(t, p[1].toFloatOrNull(), p[2].toFloatOrNull(), p[3].toFloatOrNull())
        }
        if (rows.isEmpty()) return null
        var dur = 0L
        var abovePan = 0L
        var aboveConv = 0L
        for (i in 1 until rows.size) {
            var dt = (rows[i].t - rows[i - 1].t).toLong()
            if (dt < 0) dt += 86400 // crossed midnight
            dt = dt.coerceAtMost(120)
            dur += dt
            if ((rows[i].pan ?: -1f) >= thresholdF) abovePan += dt
            if ((rows[i].conv ?: -1f) >= thresholdF) aboveConv += dt
        }
        return TripSummary(
            fileName = file.name,
            dateLabel = dateLabel(file.name),
            samples = rows.size,
            durationSec = dur,
            maxPanF = rows.mapNotNull { it.pan }.maxOrNull(),
            maxConvF = rows.mapNotNull { it.conv }.maxOrNull(),
            maxCoolantF = rows.mapNotNull { it.coolant }.maxOrNull(),
            abovePanSec = abovePan,
            aboveConvSec = aboveConv,
            thresholdF = thresholdF,
        )
    }

    fun fmtDur(sec: Long): String = when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600}h %02dmin".format((sec % 3600) / 60)
    }

    fun fmtAbove(sec: Long): String =
        if (sec < 60) "${sec}s" else "${sec / 60}m %02ds".format(sec % 60)

    private fun dateLabel(name: String): String {
        val m = Regex("tacoma-tow-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})\\d{2}").find(name)
            ?: return name
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi"
    }

    private data class Row(val t: Int, val pan: Float?, val conv: Float?, val coolant: Float?)
}
