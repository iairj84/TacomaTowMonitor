package com.tacomatow.monitor.obd

/**
 * Identifies what silicon is actually behind an "ELM327" label.
 *
 * The label is meaningless: the genuine article is a PIC18F2480 running Elm
 * Electronics' masked firmware (Elm has since closed), while $3 units ship a
 * repurposed Jieli Bluetooth audio SoC with the CAN stack in software and no
 * CAN transceiver at all. That difference decides whether a firmware problem
 * is even fixable in principle.
 */
enum class FirmwareKind {
    GENUINE_STN,
    ELM_PIC_CLONE,
    JIELI_SINGLE_CHIP,
    UNKNOWN,
}

data class FirmwareInfo(
    val kind: FirmwareKind,
    val identity: String?,
    val description: String?,
    val title: String,
    val explanation: String,
    val reflashable: Boolean,
    val advice: String,
)

object AdapterFirmware {

    private val elmVersion = Regex("""ELM\s*327\s*[Vv]?\s*(\d+\.\d+)""")

    fun classify(identity: String?, description: String?): FirmwareInfo {
        val id = identity?.trim().orEmpty()
        val desc = description?.trim().orEmpty()
        val combined = "$id $desc".uppercase()

        // The known junk-chip tell: TDA99 firmware strings on a single-die SoC.
        if (combined.contains("TDA99")) {
            return FirmwareInfo(
                kind = FirmwareKind.JIELI_SINGLE_CHIP,
                identity = id.ifBlank { null },
                description = desc.ifBlank { null },
                title = "Single-chip clone (Jieli SoC)",
                explanation = "This is the cheap-clone signature: one repurposed Bluetooth audio chip " +
                    "doing CAN in software, no CAN transceiver, and a partial ELM reimplementation. " +
                    "It cannot be upgraded — there is no flash to write and the missing hardware " +
                    "is not something firmware can supply.",
                reflashable = false,
                advice = "No firmware path exists for this chip. If you need manufacturer data, " +
                    "this adapter will never provide it.",
            )
        }

        if (combined.contains("STN") || combined.contains("OBDLINK") || combined.contains("VLINKER")) {
            return FirmwareInfo(
                kind = FirmwareKind.GENUINE_STN,
                identity = id.ifBlank { null },
                description = desc.ifBlank { null },
                title = "STN chip (ScanTool / OBDLink class)",
                explanation = "A purpose-built OBD interpreter with a real CAN controller. These " +
                    "implement full ISO-TP including flow control on any address pair — the " +
                    "capability cheap clones lack.",
                reflashable = true,
                advice = "No reflash needed. Vendor firmware updates are available if the " +
                    "manufacturer ships them.",
            )
        }

        val match = elmVersion.find(id)
        if (match != null || combined.contains("ELM")) {
            val version = match?.groupValues?.get(1)
            return FirmwareInfo(
                kind = FirmwareKind.ELM_PIC_CLONE,
                identity = id.ifBlank { null },
                description = desc.ifBlank { null },
                title = "ELM327 clone, PIC-class" + (version?.let { " (reports v$it)" } ?: ""),
                explanation = "Better silicon than the single-chip junk: this architecture can in " +
                    "principle run different firmware, and ediabaslib publishes a replacement with " +
                    "a bootloader for the ELM327L variants. But no third-party firmware adds " +
                    "multi-frame transmit — the limitation is in the protocol engine, not the flash.",
                reflashable = true,
                advice = "Reflashing is technically possible but will not unlock manufacturer " +
                    "traffic. Only pursue it if you want the faster CAN / K-line features.",
            )
        }

        return FirmwareInfo(
            kind = FirmwareKind.UNKNOWN,
            identity = id.ifBlank { null },
            description = desc.ifBlank { null },
            title = "Unrecognised adapter",
            explanation = "The adapter did not report a firmware string this app recognises. That " +
                "usually means a very cheap clone or a firmware that mangles ATI replies.",
            reflashable = false,
            advice = "Treat it as the clone class: test with the adapter check before buying " +
                "anything, and expect basic data only.",
        )
    }
}
