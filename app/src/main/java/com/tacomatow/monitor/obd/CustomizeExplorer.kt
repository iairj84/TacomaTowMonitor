package com.tacomatow.monitor.obd

/**
 * READ-ONLY Toyota "Customize" (C-BEST) discovery.
 *
 * The body and combination-meter ECUs expose their customization blocks
 * through Toyota's legacy local-identifier services: `21 <LID>` reads a
 * block, `3B <LID> <data>` writes one. Carista replays exactly these.
 *
 * LIDs and bit layouts are model-specific and Toyota does not publish
 * them, so this class deliberately does the safe half only: it asks each
 * ECU what it has and shows the raw answer. Nothing here writes.
 *
 * Addresses (11-bit):
 *   0x750  main body ECU   (locks, lights, chimes, wireless)
 *   0x7C0  combination meter (warning buzzers, displays)
 *   0x7C4  A/C amplifier   (climate customization)
 */
object CustomizeExplorer {

    data class Target(val header: String, val label: String)

    val TARGETS = listOf(
        Target("750", "Main body ECU"),
        Target("7C0", "Combination meter"),
        Target("7C4", "A/C amplifier"),
        Target("7C6", "Smart key / certification"),
    )

    /** LIDs observed in the low range on this ECU generation. */
    val LID_RANGE: IntRange = 0x01..0x3F

    val totalProbes: Int get() = TARGETS.size * LID_RANGE.count()

    fun readCommand(lid: Int): String = "21" + "%02X".format(lid)

    fun hex(bytes: ByteArray?): String? =
        bytes?.joinToString(" ") { "%02X".format(it) }
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
