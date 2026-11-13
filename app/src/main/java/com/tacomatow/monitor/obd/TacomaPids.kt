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

    /** Decode raw ECU bytes to °F (or rpm/mph). Returns null when unsupported. */
    fun decode(def: PidDef, bytes: ByteArray): Float? {
        if (bytes.size < 1) return null
        val a = bytes[0].toInt() and 0xFF
        val b = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
        return when (def.id) {
            "trans_pan", "trans_conv" -> {
                // Owner-verified °F equation for 221627 / 221628.
                if (bytes.size < 2) null
                else (a * 459f / 255f) + (b * 1.6f / 255f) - 40f
            }
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
