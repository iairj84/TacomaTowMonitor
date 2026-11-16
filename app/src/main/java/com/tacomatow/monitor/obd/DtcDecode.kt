package com.tacomatow.monitor.obd

/**
 * Trouble-code replies, read-only.
 *
 * Mode 03 (stored/confirmed), 07 (pending) and 0A (permanent) answer with
 * service byte 0x43/0x47/0x4A, a count byte, then two bytes per code.
 * 00 00 pairs are padding, not codes. Mode 04 (clear codes) is a write to
 * the truck, so it deliberately has no function anywhere near here.
 */
object DtcDecode {

    private val SERVICE = mapOf("03" to 0x43, "07" to 0x47, "0A" to 0x4A)

    /**
     * Codes in one reply. Empty when the bus said nothing or refused.
     *
     * Parsed through [IsoTp] so packed, multi-line and multi-ECU replies all
     * count — the old spaced-only tokenizer read a packed reply as no codes,
     * and a truck with stored faults looked clean.
     */
    fun decode(mode: String, raw: String?): List<String> {
        val svc = SERVICE[mode.uppercase()] ?: return emptyList()
        if (raw.isNullOrBlank()) return emptyList()
        val upper = raw.uppercase()
        if (upper.contains("NO DATA") || upper.contains("UNABLE") ||
            upper.contains("ERROR") || upper.contains("STOPPED") || upper.contains("?")
        ) return emptyList()
        val tokens = upper.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        // Bare capture without PCI or headers, e.g. shared logs: "43 02 03 00 …".
        if (tokens[0] == "%02X".format(svc)) {
            return codesFrom(tokens.mapNotNull { it.toIntOrNull(16) }.drop(1))
        }
        val out = LinkedHashSet<String>()
        IsoTp.messages(raw).forEach { m ->
            if (m.size >= 2 && (m[0].toInt() and 0xFF) == svc) {
                out += codesFrom(m.drop(1).map { it.toInt() and 0xFF })
            }
            // A 7F message is a refusal, not codes — and not an error either.
        }
        return out.toList()
    }

    /** Mode 01 PID 01 payload: check-engine light plus confirmed-code count. */
    fun mil(payload: ByteArray?): Pair<Boolean, Int>? {
        if (payload == null || payload.isEmpty()) return null
        val a = payload[0].toInt() and 0xFF
        return ((a and 0x80) != 0) to (a and 0x7F)
    }

    /**
     * Bytes after the service byte: a count byte, then two bytes per code.
     * A 00 00 pair is padding.
     */
    private fun codesFrom(afterService: List<Int>): List<String> {
        val out = mutableListOf<String>()
        var j = 1 // skip the count byte
        while (j + 1 < afterService.size) {
            code(afterService[j], afterService[j + 1])?.let { out += it }
            j += 2
        }
        return out
    }

    /** e.g. 0x03, 0x01 -> "P0301". 00 00 padding -> null. */
    fun code(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val prefix = when (a ushr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (a ushr 4) and 0x03
        fun hex(n: Int) = "0123456789ABCDEF"[n and 0x0F]
        return "$prefix$d1${hex(a)}${hex(b ushr 4)}${hex(b)}"
    }
}

/** Everything one check-engine scan learned. Nulls mean the bus said nothing. */
data class DtcReport(
    val milOn: Boolean?,
    val milCount: Int?,
    val confirmed: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val note: String? = null,
)
