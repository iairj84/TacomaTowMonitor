package com.tacomatow.monitor.obd

/**
 * READ-ONLY Toyota "Customize" (C-BEST) discovery.
 *
 * The body and combination-meter ECUs expose their customization blocks
 * through Toyota's legacy local-identifier services: `21 <LID>` reads a
 * block, `3B <LID> <data>` writes one. Dealer diagnostic tools replay
 * exactly these.
 *
 * LIDs and bit layouts are model-specific and Toyota does not publish
 * them, so this class deliberately does the safe half only: it asks each
 * ECU what it has and shows the raw answer. Nothing here writes.
 *
 * Addresses (11-bit):
 *   0x750  gateway, NOT an ECU: fans out by sub-target address, 0x40 below
 *   0x750/0x40  main body ECU (locks, lights, chimes, wireless) via gateway
 *   0x7C0  combination meter (warning buzzers, displays)
 *   0x7C4  A/C amplifier   (climate customization)
 */
object CustomizeExplorer {

    /**
     * One sweep destination. [sub] is a gateway sub-target (ISO 15765-2 mixed
     * addressing): 0x750 is a relay, and the first byte of every frame names
     * the downstream ECU — 0x40 is Main Body. A bare "21 XX" to 750 names
     * nobody and is never answered, which is why the gateway used to look
     * like 63 blocks of dead air.
     */
    data class Target(val header: String, val label: String, val sub: Int? = null)

    val TARGETS = listOf(
        Target("750", "Main body ECU (gateway 0x40)", 0x40),
        Target("7C0", "Combination meter"),
        Target("7C4", "A/C amplifier"),
        Target("7C6", "Smart key / certification"),
    )

    /** LIDs observed in the low range on this ECU generation. */
    val LID_RANGE: IntRange = 0x01..0x3F

    val totalProbes: Int get() = TARGETS.size * LID_RANGE.count()

    /**
     * The read itself. The sub-target is framing, not payload: putting it in
     * the command text makes the adapter prepend its own length byte, and the
     * gateway reads the result as sub-target 0x04. [Elm327Client] asks the
     * adapter to frame it instead (AT CEA), so this stays a plain KWP read.
     */
    fun readCommand(lid: Int): String = "21" + "%02X".format(lid)

    fun hex(bytes: ByteArray?): String? =
        bytes?.joinToString(" ") { "%02X".format(it) }

    /**
     * Compare two sweeps and report which blocks moved.
     *
     * This is the safe way to learn a layout: change ONE setting on the
     * factory radio's Vehicle Customization screen between two sweeps, and
     * the byte that moved is the setting, with both values in hand. It needs
     * no sniffing and no vendor software, because it observes the effect
     * rather than the traffic.
     */
    fun diff(
        before: List<LidReading>,
        after: List<LidReading>,
        /**
         * Blocks the second sweep actually asked about. A fast re-scan omits
         * some probes, and an omitted block must never look like a block that
         * stopped answering.
         */
        probedKeys: Set<Pair<String, Int>>? = null,
    ): SnapshotDiff {
        fun key(r: LidReading) = r.header to r.lid
        val beforeMap = before.filter { it.supported }.associateBy(::key)
        val afterMap = after.filter { it.supported }.associateBy(::key)
        val order = compareBy<Pair<String, Int>>({ it.first }, { it.second })

        val shared = beforeMap.keys.intersect(afterMap.keys).sortedWith(order)
        val changes = mutableListOf<BlockChange>()
        for (k in shared) {
            val old = beforeMap.getValue(k)
            val new = afterMap.getValue(k)
            val oldTokens = tokens(old.payloadHex)
            val newTokens = tokens(new.payloadHex)
            if (oldTokens == newTokens) continue
            val moved = (0 until maxOf(oldTokens.size, newTokens.size)).mapNotNull { i ->
                val a = oldTokens.getOrNull(i) ?: "—"
                val b = newTokens.getOrNull(i) ?: "—"
                if (a == b) null else ByteChange(i, a, b)
            }
            changes += BlockChange(
                header = k.first,
                ecuLabel = new.ecuLabel,
                lid = k.second,
                beforeHex = old.payloadHex ?: "",
                afterHex = new.payloadHex ?: "",
                byteChanges = moved,
            )
        }

        return SnapshotDiff(
            changes = changes,
            gained = (afterMap.keys - beforeMap.keys).sortedWith(order).map(afterMap::getValue),
            lost = (beforeMap.keys - afterMap.keys)
                .filter { probedKeys == null || it in probedKeys }
                .sortedWith(order)
                .map(beforeMap::getValue),
            compared = shared.size,
        )
    }

    private fun tokens(hex: String?): List<String> =
        hex?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()

    /** Plain-text version of a diff, for sharing a verified setting. */
    fun describe(diff: SnapshotDiff, savedLabel: String, currentLabel: String): String {
        val sb = StringBuilder()
        sb.appendLine("Tacoma customize diff")
        sb.appendLine("before: $savedLabel")
        sb.appendLine("after:  $currentLabel")
        sb.appendLine("blocks compared: ${diff.compared}")
        sb.appendLine()
        if (diff.changes.isEmpty()) {
            sb.appendLine("No block payloads changed.")
        } else {
            for (c in diff.changes) {
                sb.appendLine("0x%02X  %s  (%s)".format(c.lid, c.ecuLabel, c.header))
                sb.appendLine("  before: ${c.beforeHex}")
                sb.appendLine("  after:  ${c.afterHex}")
                for (b in c.byteChanges) sb.appendLine("  ${b.describe()}")
                sb.appendLine()
            }
        }
        if (diff.gained.isNotEmpty()) {
            sb.appendLine("answered only after the change:")
            diff.gained.forEach { sb.appendLine("  0x%02X %s".format(it.lid, it.ecuLabel)) }
        }
        if (diff.lost.isNotEmpty()) {
            sb.appendLine("answered only before the change:")
            diff.lost.forEach { sb.appendLine("  0x%02X %s".format(it.lid, it.ecuLabel)) }
        }
        return sb.toString().trimEnd()
    }
}

/** One byte position that moved between two snapshots of the same block. */
data class ByteChange(val index: Int, val before: String, val after: String) {

    private val fromValue: Int? get() = before.toIntOrNull(16)
    private val toValue: Int? get() = after.toIntOrNull(16)

    /**
     * Bit positions that differ, LSB first.
     *
     * Several Toyota customizations share one byte, so "26 -> 27" on its own
     * cannot say which option moved. "bit 0" can. Numbering is LSB = bit 0,
     * the convention Toyota's bitfields are documented in.
     */
    val changedBits: List<Int>
        get() {
            val a = fromValue ?: return emptyList()
            val b = toValue ?: return emptyList()
            val diff = a xor b
            return (0..7).filter { diff and (1 shl it) != 0 }
        }

    /** "00100110 -> 00100111", or null when a byte is missing. */
    fun binaryText(): String? {
        val a = fromValue ?: return null
        val b = toValue ?: return null
        return "${a.toString(2).padStart(8, '0')} → ${b.toString(2).padStart(8, '0')}"
    }

    fun describe(): String {
        val bits = changedBits
        val suffix = when (bits.size) {
            0 -> ""
            1 -> "  (bit ${bits.first()})"
            else -> "  (bits ${bits.joinToString(", ")})"
        }
        return "byte $index: $before → $after$suffix"
    }
}

/** A block whose payload moved between two snapshots. */
data class BlockChange(
    val header: String,
    val ecuLabel: String,
    val lid: Int,
    val beforeHex: String,
    val afterHex: String,
    val byteChanges: List<ByteChange>,
) {
    val displayLid: String get() = "0x%02X".format(lid)
}

/** Result of comparing two customize sweeps. */
data class SnapshotDiff(
    val changes: List<BlockChange>,
    val gained: List<LidReading>,
    val lost: List<LidReading>,
    val compared: Int,
) {
    val identical: Boolean get() = changes.isEmpty() && gained.isEmpty() && lost.isEmpty()
}

data class LidReading(
    val header: String,
    val ecuLabel: String,
    val lid: Int,
    /** Raw reply including CAN id and header bytes, e.g. "758 05 61 14 9A 26". */
    val raw: String?,
    /** Payload after the 61 <LID> echo, or null when unsupported. */
    val payloadHex: String?,
) {
    val supported: Boolean get() = payloadHex != null
    val displayLid: String get() = "0x%02X".format(lid)
}
