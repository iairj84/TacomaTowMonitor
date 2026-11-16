package com.tacomatow.monitor.obd

/**
 * PID catalog for a 2019 Tacoma (3.5L V6, A/T) + standard SAE PIDs.
 *
 * Transmission temps are Toyota-enhanced Mode 22 requests reported by
 * Tacoma owners for Torque Pro — NOT factory-published. Verified
 * community values: Mode 22, PIDs 0x1627/0x1628, header 701,
 * equation (A*459/255)+(B*1.6/255)-40 in °F.
 *
 * Standard engine PIDs (coolant 0105, oil 015C, rpm 010C, speed 010D)
 * follow SAE J1979. 015C only answers if the ECU supports oil temp —
 * the 2GR-FKS commonly does NOT, so the app marks it Unsupported
 * instead of showing junk.
 */
data class PidDef(
    val id: String,
    val label: String,
    val mode: String,
    /** e.g. "05", "5C", "1627" */
    val pid: String,
    /** ELM header override, e.g. "701". Null = default / functional. */
    val header: String? = null,
    /** Transmission decode variant, e.g. "1627" or "82". Picks the formula. */
    val kind: String? = null,
    val unit: String,
    val minF: Float? = null,
    val warnF: Float? = null,
    val critF: Float? = null,
)

object TacomaPids {
    val TRANS_PAN = PidDef(
        id = "trans_pan",
        label = "Trans Pan",
        mode = "22",
        pid = "1627",
        header = "701",
        kind = "1627",
        unit = "°F",
        warnF = 220f,
        critF = 240f,
    )
    val TRANS_CONVERTER = PidDef(
        id = "trans_conv",
        label = "T-Conv Out",
        mode = "22",
        pid = "1628",
        header = "701",
        kind = "1628",
        unit = "°F",
        warnF = 230f,
        critF = 250f,
    )
    val COOLANT = PidDef(
        id = "coolant",
        label = "Coolant",
        mode = "01",
        pid = "05",
        unit = "°F",
        warnF = 223f,
        critF = 235f,
    )
    val OIL = PidDef(
        id = "oil",
        label = "Engine Oil",
        mode = "01",
        pid = "5C",
        unit = "°F",
        warnF = 250f,
        critF = 270f,
    )
    val RPM = PidDef(id = "rpm", label = "RPM", mode = "01", pid = "0C", unit = "rpm")
    val SPEED = PidDef(id = "speed", label = "Speed", mode = "01", pid = "0D", unit = "mph")

    val TOW_DASHBOARD = listOf(TRANS_PAN, TRANS_CONVERTER, COOLANT, OIL)
    val ALL = TOW_DASHBOARD + listOf(RPM, SPEED)

    /**
     * Ways a 3rd-gen Tacoma might expose ATF temperature, best first.
     *
     * The 2016+ truck answers Mode 22 DID 1627/1628 requested at 11-bit
     * header 0x701 (reply arrives from 0x709). Some calibrations answer at
     * 0x700, and a few report the classic Toyota Mode 21 PID 82 instead.
     * Rather than hard-code one, the app probes these on connect and keeps
     * whichever returns a believable temperature.
     */
    data class TransCandidate(
        val header: String?,
        val mode: String,
        val pid: String,
        val kind: String,
        val label: String,
    )

    val TRANS_CANDIDATES = listOf(
        // Functional (broadcast) addressing is what Torque uses when the header
        // field is left blank — the RAV4 owners who got ATF temp with a $5
        // dongle were doing exactly this. Try it first, it costs nothing.
        TransCandidate(null, "22", "1627", "1627", "22/1627@functional"),
        TransCandidate("701", "22", "1627", "1627", "22/1627@701"),
        // ScanGauge's published 2016+ Tacoma 3.5L X-Gauges use these DIDs
        // (TXD 0701221AAA / 0701221AC0) instead of 1627/1628. Owners disagree
        // about which set a given calibration answers, so probe both.
        TransCandidate("701", "22", "1AAA", "1AAA", "22/1AAA@701"),
        TransCandidate("7E0", "22", "1AAA", "1AAA", "22/1AAA@7E0"),
        TransCandidate("700", "22", "1627", "1627", "22/1627@700"),
        TransCandidate("7E1", "22", "1627", "1627", "22/1627@7E1"),
        TransCandidate("7E0", "22", "1627", "1627", "22/1627@7E0"),
        TransCandidate("7E0", "21", "82", "82", "21/82@7E0"),
        TransCandidate("7E1", "21", "82", "82", "21/82@7E1"),
        TransCandidate(null, "21", "82", "82", "21/82@functional"),
    )

    val CONVERTER_CANDIDATES = listOf(
        TransCandidate(null, "22", "1628", "1628", "22/1628@functional"),
        TransCandidate("701", "22", "1628", "1628", "22/1628@701"),
        TransCandidate("701", "22", "1AC0", "1AC0", "22/1AC0@701"),
        TransCandidate("7E0", "22", "1AC0", "1AC0", "22/1AC0@7E0"),
        TransCandidate("700", "22", "1628", "1628", "22/1628@700"),
        TransCandidate("7E1", "22", "1628", "1628", "22/1628@7E1"),
        TransCandidate("7E0", "22", "1628", "1628", "22/1628@7E0"),
    )

    /** Build a polling definition from a detected candidate. */
    fun defFor(
        candidate: TransCandidate,
        id: String,
        label: String,
        warnF: Float,
        critF: Float,
    ): PidDef = PidDef(
        id = id,
        label = label,
        mode = candidate.mode,
        pid = candidate.pid,
        header = candidate.header,
        kind = candidate.kind,
        unit = "°F",
        warnF = warnF,
        critF = critF,
    )

    /**
     * Decode a transmission-temperature payload.
     * kind 1627/1628: (A*459/255)+(B*1.6/255)-40 °F.
     * kind 82: Mode 21 PID 82 carries ATF1 (A,B) then ATF2 (C,D) as °C*10.
     */
    fun decodeTrans(kind: String, bytes: ByteArray): Float? = when (kind) {
        // 1AAA/1AC0 decode identically: (A*9/5)-40 °F when B is padding, which
        // is exactly what ScanGauge's MTH 0009 0005 FFD8 computes.
        "1627", "1628", "1AAA", "1AC0" -> {
            if (bytes.size < 2) null
            else {
                val a = bytes[0].toInt() and 0xFF
                val b = bytes[1].toInt() and 0xFF
                (a * 459f / 255f) + (b * 1.6f / 255f) - 40f
            }
        }
        "82" -> {
            if (bytes.size < 4) null
            else {
                val a = bytes[0].toInt() and 0xFF
                val b = bytes[1].toInt() and 0xFF
                val raw = (a * 256 + b) * 0.07f
                val c = (raw - 400f) / 10f // °C
                c * 9f / 5f + 32f
            }
        }
        else -> null
    }

    /** Decode raw ECU bytes to °F (or rpm/mph). Returns null when unsupported. */
    fun decode(def: PidDef, bytes: ByteArray): Float? {
        if (bytes.size < 1) return null
        val a = bytes[0].toInt() and 0xFF
        val b = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
        return when (def.id) {
            // The candidate that answered sets the formula: a 21/82 detection
            // answered in °C*10, not the 1627 equation. Dropping the kind
            // here once decoded the same bytes to 14 °F that detection had
            // read as 57 °F.
            "trans_pan", "trans_conv" -> decodeTrans(def.kind ?: "1627", bytes)
            "coolant" -> celsiusToF(a - 40)
            "oil" -> celsiusToF(a - 40)
            "rpm" -> if (bytes.size < 2) null else ((a * 256f) + b) / 4f
            "speed" -> (a * 0.621371f) // km/h -> mph
            else -> null
        }
    }

    private fun celsiusToF(c: Int): Float = c * 9f / 5f + 32f

    /** ELM command, e.g. "221627" or "0105". */
    fun command(def: PidDef): String = def.mode + def.pid
}
