package com.tacomatow.monitor.obd

/** What a single mode 22 identifier did when asked. */
enum class DidVerdict {
    /** The ECU returned a payload. */
    DATA,

    /** The ECU answered with 7F <22> <code>: the request arrived and was refused. */
    REFUSED,

    /** Nothing came back at all — the adapter dropped it. */
    SILENT,
}

data class DidProbe(
    val did: String,
    val raw: String?,
    val verdict: DidVerdict,
    val payloadHex: String?,
    val refusal: String?,
)

/**
 * Sweeps a bounded set of Toyota mode 22 identifiers and records what each one
 * did.
 *
 * The point is to separate two failures that both look like an empty gauge:
 * a DID the ECU *refuses* proves the adapter carries manufacturer traffic, so
 * the identifier is simply wrong for this calibration. A DID that gets silence
 * while a refused one exists means the adapter is dropping replies. And if
 * every single one is silent, the adapter cannot do manufacturer reads at all.
 *
 * Kept bounded on purpose: ~96 probes at ~1.2 s worst case is about a minute
 * standing still, which is as long as anyone will watch a progress line.
 */
object DidSweep {

    /** The transmission ECU, where a Tacoma's ATF temperatures live. */
    const val HEADER = "701"

    /** Known-good community identifiers, probed first so results lead with them. */
    private val KNOWN = listOf("1627", "1628", "1AAA", "1AC0")

    /**
     * Ranges worth walking. 0x16xx is the family 1627/1628 belong to; 0x1Axx
     * is the ScanGauge family. Bounded to 32 per range to stay inside a minute.
     */
    private val RANGES = listOf(0x1620..0x163F, 0x1AA0..0x1ABF)

    fun dids(): List<String> {
        val swept = RANGES.flatMap { range -> range.map { "%04X".format(it) } }
        return (KNOWN + swept).distinct()
    }

    fun describe(probes: List<DidProbe>): String = buildString {
        val data = probes.count { it.verdict == DidVerdict.DATA }
        val refused = probes.count { it.verdict == DidVerdict.REFUSED }
        val silent = probes.count { it.verdict == DidVerdict.SILENT }
        appendLine("Mode 22 sweep at header $HEADER: ${probes.size} identifiers")
        appendLine("answered with data: $data · refused (7F): $refused · silent: $silent")
        appendLine()
        probes.filter { it.verdict != DidVerdict.SILENT }.forEach { p ->
            when (p.verdict) {
                DidVerdict.DATA -> appendLine("22${p.did}  DATA     ${p.payloadHex ?: ""}")
                DidVerdict.REFUSED -> appendLine("22${p.did}  REFUSED  ${p.refusal ?: ""}")
                else -> Unit
            }
        }
        if (silent > 0) {
            appendLine()
            appendLine("silent (no reply): " + probes.filter { it.verdict == DidVerdict.SILENT }
                .joinToString(" ") { it.did })
        }
    }
}
