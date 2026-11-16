package com.tacomatow.monitor.obd

/**
 * Read-only decoding of SAE J1979 trouble-code replies.
 *
 * Mode 03 (stored/confirmed), 07 (pending) and 0A (permanent) answer with
 * service byte 0x43/0x47/0x4A, a count byte, then two bytes per code.
 * 00 00 pairs are padding, not codes. Mode 04 (clear codes) is a write to
 * the truck, so it deliberately has no function anywhere near here.
 */
object DtcDecode {

    private val SERVICE = mapOf("03" to 0x43, "07" to 0x47, "0A" to 0x4A)

    /** Codes in one reply. Empty when the bus said nothing or refused. */
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
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (isHeader(t)) {
                i++
                continue
            }
            val pci = t.toIntOrNull(16)
            if (pci == null) {
                i++
                continue
            }
            val data = mutableListOf<Int>()
            when (pci ushr 4) {
                // Single frame: length, then that many data bytes.
                0 -> {
                    val n = pci and 0x0F
                    if (n == 0) {
                        i++
                        continue
                    }
                    for (k in 0 until n) data += byteAt(tokens, i + 1 + k)
                    i += 1 + n
                }
                // First frame: total length, then ISO-TP reassembly.
                1 -> {
                    if (i + 1 >= tokens.size) break
                    var need = ((pci and 0x0F) shl 8) + (tokens[i + 1].toIntOrNull(16) ?: break)
                    i += 2
                    // First frame holds 6 data bytes after its 2-byte PCI; each
                    // consecutive frame holds 7 after its 1-byte PCI.
                    var room = 6
                    while (need > 0 && i < tokens.size) {
                        val f = tokens[i]
                        if (isHeader(f)) {
                            // Same message continuing ("7E8 21 …"). Anything else
                            // is another ECU starting its own reply.
                            val next = tokens.getOrNull(i + 1)?.toIntOrNull(16)
                            if (room != 0 || next == null || next ushr 4 != 2) break
                            i++
                            continue
                        }
                        if (room == 0) {
                            val v = f.toIntOrNull(16) ?: break
                            if (v ushr 4 != 2) break
                            i++
                            room = 7
                            continue
                        }
                        data += f.toIntOrNull(16) ?: break
                        i++
                        need--
                        room--
                    }
                }
                else -> i++
            }
            if (data.size >= 2 && data[0] == svc) {
                out += codesFrom(data.drop(1))
            }
            // A 7F frame is a refusal, not codes — and not an error either.
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

    private fun byteAt(tokens: List<String>, i: Int): Int =
        if (i in tokens.indices) tokens[i].toIntOrNull(16) ?: 0 else 0

    /** 11-bit header echoes like 7E8 — reply metadata, never data. */
    private fun isHeader(t: String): Boolean =
        t.length == 3 && t.all { it.isDigit() || it in 'A'..'F' }
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
