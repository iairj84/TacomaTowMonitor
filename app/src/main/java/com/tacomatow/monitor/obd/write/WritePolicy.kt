package com.tacomatow.monitor.obd.write

/**
 * The gate every write must pass. Pure: no I/O, no adapter, no Android.
 *
 * Default-deny by design. Only three headers are writable — the body ECU, the
 * combination meter and the A/C amplifier — because those are the ones whose
 * customization blocks this project has actually read. Everything else,
 * including airbag, braking, steering, immobiliser and powertrain, is refused
 * by not being on the list. That is deliberate: a blocklist of "known
 * dangerous" addresses would have to be complete to be safe, and an allowlist
 * only has to be correct.
 *
 * See docs/WRITE-SAFETY.md for the reasoning and the wording.
 */
enum class RefusalRule {
    UNRECOGNISED_TARGET,
    MALFORMED_VALUE,
    BATCH,
    NO_CHANGE,
    UNVERIFIED_ENTRY,
    NO_BACKUP,
    BACKUP_UNPARSEABLE,
    STALE_BACKUP,
    REREAD_MISMATCH,
    READ_REFUSED,
    ENGINE_RUNNING,
    VEHICLE_MOVING,
    LOW_VOLTAGE,
    ADAPTER_UNTRUSTED,
    COOLDOWN,
}

/** Exactly one byte of one block, with the value it holds and the value wanted. */
data class WriteTarget(
    val header: String,
    val lid: Int,
    val byteIndex: Int,
    val before: String,
    val after: String,
    /**
     * Gateway sub-target. 0x750 is a relay, so a write there must say which
     * module it is for — 0x40 is Main Body. Must be null elsewhere: the meter
     * and A/C amplifier are addressed directly and have no sub-target.
     */
    val sub: Int? = null,
)

/**
 * Everything the policy needs to know about the truck and the session.
 * Defaults describe a healthy, stationary, verified case so tests can vary
 * one field at a time.
 */
data class WriteConditions(
    val verifiedForThisCalibration: Boolean = true,
    val backupPresent: Boolean = true,
    val backupParses: Boolean = true,
    val backupTakenThisSession: Boolean = true,
    val rereadMatchesBackup: Boolean = true,
    val lidsSelected: Int = 1,
    val bytesSelected: Int = 1,
    /** Most recent vehicle speed readings in km/h, newest last. */
    val speedSamples: List<Int> = List(5) { 0 },
    val batteryVolts: Float = 12.6f,
    val adapterTrusted: Boolean = true,
    val engineRunning: Boolean = false,
    val lastWriteToSameLidMillisAgo: Long? = null,
    val lastReadRefused: Boolean = false,
)

sealed interface WriteDecision {
    data object Allow : WriteDecision

    /** [message] is shown to the user; [detail] says which rule fired and why. */
    data class Refuse(
        val rule: RefusalRule,
        val message: String,
        val detail: String,
    ) : WriteDecision
}

object WritePolicy {

    /**
     * Writable blocks: body (through the gateway), meter, A/C amplifier.
     * Nothing else. A header alone is not enough for 750 — see [GATEWAY_SUB].
     */
    val ALLOWED_HEADERS = setOf("750", "7C0", "7C4")

    /** The gateway relay: writable only with a named sub-target. */
    const val GATEWAY_HEADER = "750"

    /** Main Body behind that gateway. The only sub-target a write may name. */
    const val MAIN_BODY_SUB = 0x40

    /** Customization local identifiers observed on this ECU generation. */
    val ALLOWED_LIDS = 0x01..0x3F

    const val MIN_VOLTS = 12.0f
    const val COOLDOWN_MS = 60_000L
    const val SPEED_SAMPLES_REQUIRED = 5

    private val HEX_BYTE = Regex("^[0-9A-Fa-f]{2}$")

    fun evaluate(target: WriteTarget, conditions: WriteConditions): WriteDecision {
        val header = target.header.uppercase().removePrefix("0X")
        val lidHex = "0x%02X".format(target.lid)

        // 1. Safety first: unknown or non-customization blocks are not writable.
        if (header !in ALLOWED_HEADERS || target.lid !in ALLOWED_LIDS) {
            return refuse(
                RefusalRule.UNRECOGNISED_TARGET,
                "Refused: this block is not a customization block.",
                "$header / $lidHex is not one of ${ALLOWED_HEADERS.joinToString()}. " +
                    "Safety, immobiliser and powertrain blocks are refused by not being on the allowlist.",
            )
        }
        // 1b. 0x750 covers every module behind the gateway. A write has to
        //     name its sub-target, or "body ECU" means whichever module the
        //     relay happens to pick — or none at all.
        if (header == GATEWAY_HEADER) {
            if (target.sub != MAIN_BODY_SUB) {
                return refuse(
                    RefusalRule.UNRECOGNISED_TARGET,
                    "Refused: a gateway write must name its sub-target.",
                    "$header/$lidHex reached the gateway without a sub-target; only " +
                        "0x%02X (Main Body) is writable behind it.".format(MAIN_BODY_SUB),
                )
            }
        } else if (target.sub != null) {
            return refuse(
                RefusalRule.UNRECOGNISED_TARGET,
                "Refused: this block is addressed directly and takes no sub-target.",
                "$header/$lidHex was given sub-target 0x%02X.".format(target.sub),
            )
        }

        // 2. The request must be a single, well-formed byte change.
        if (!HEX_BYTE.matches(target.before) || !HEX_BYTE.matches(target.after)) {
            return refuse(
                RefusalRule.MALFORMED_VALUE,
                "Refused: that value is not a single byte.",
                "before='${target.before}' after='${target.after}'",
            )
        }
        if (conditions.lidsSelected != 1 || conditions.bytesSelected != 1) {
            return refuse(
                RefusalRule.BATCH,
                "Refused: one block and one byte per write.",
                "${conditions.lidsSelected} block(s), ${conditions.bytesSelected} byte(s) selected.",
            )
        }
        if (target.before.equals(target.after, ignoreCase = true)) {
            return refuse(
                RefusalRule.NO_CHANGE,
                "Nothing to write: the value is already ${target.after.uppercase()}.",
                "before and after are identical.",
            )
        }

        // 3. We must know the value is right for THIS calibration.
        if (!conditions.verifiedForThisCalibration) {
            return refuse(
                RefusalRule.UNVERIFIED_ENTRY,
                "Not writing: this setting was never verified on your truck's calibration.",
                "Entry is inferred or verified against a different calibration string.",
            )
        }

        // 4. A restore path must exist before the write does.
        if (!conditions.backupPresent) {
            return refuse(
                RefusalRule.NO_BACKUP,
                "Not writing: no backup of this block is stored.",
                "A write without a stored original cannot be undone.",
            )
        }
        if (!conditions.backupParses) {
            return refuse(
                RefusalRule.BACKUP_UNPARSEABLE,
                "Not writing: the stored backup cannot be read.",
                "A restore path that cannot be parsed is not a restore path.",
            )
        }
        if (!conditions.backupTakenThisSession) {
            return refuse(
                RefusalRule.STALE_BACKUP,
                "Not writing: the backup is from earlier, so the block may have changed since.",
                "Re-read the block with the engine off, then try again.",
            )
        }
        if (!conditions.rereadMatchesBackup) {
            return refuse(
                RefusalRule.REREAD_MISMATCH,
                "Not writing: the block changed since it was read.",
                "Something else modified this block; the backup no longer describes it.",
            )
        }
        if (conditions.lastReadRefused) {
            return refuse(
                RefusalRule.READ_REFUSED,
                "Not writing: the module refused the read immediately before this.",
                "A block that will not answer a read will not accept a write safely.",
            )
        }

        // 5. The truck must be still, off, and healthy.
        if (conditions.engineRunning) {
            return refuse(
                RefusalRule.ENGINE_RUNNING,
                "Not writing: turn the engine off and leave the ignition on.",
                "Body-module writes are performed with the engine off.",
            )
        }
        val speeds = conditions.speedSamples
        if (speeds.size < SPEED_SAMPLES_REQUIRED || speeds.any { it != 0 }) {
            return refuse(
                RefusalRule.VEHICLE_MOVING,
                "Not writing: the truck is not confirmed stationary.",
                "Needs $SPEED_SAMPLES_REQUIRED consecutive zero-speed samples, got ${speeds.joinToString()}.",
            )
        }
        if (conditions.batteryVolts < MIN_VOLTS) {
            return refuse(
                RefusalRule.LOW_VOLTAGE,
                "Not writing: battery is at ${"%.1f".format(conditions.batteryVolts)} V.",
                "Below %.1f V an interrupted write can leave a module unwritable.".format(MIN_VOLTS),
            )
        }

        // 6. The link and the device must be trustworthy.
        if (!conditions.adapterTrusted) {
            return refuse(
                RefusalRule.ADAPTER_UNTRUSTED,
                "Not writing: this adapter has failed checks and is read-only here.",
                "Writes are refused on adapters that misreport frames or wedge on manufacturer requests.",
            )
        }
        val since = conditions.lastWriteToSameLidMillisAgo
        if (since != null && since < COOLDOWN_MS) {
            return refuse(
                RefusalRule.COOLDOWN,
                "Not writing: that block was written ${since / 1000}s ago.",
                "Wait ${COOLDOWN_MS / 1000}s, re-read, then try again.",
            )
        }

        return WriteDecision.Allow
    }

    private fun refuse(rule: RefusalRule, message: String, detail: String) =
        WriteDecision.Refuse(rule, message, detail)

    /**
     * The only services the console may send, by name.
     *
     * An allowlist, not a blocklist: 14 clears codes in its UDS form, 11
     * resets an ECU, 27 is security access and 85 is control-DTC, and a
     * blocklist that misses one of them writes to somebody's truck.
     */
    val CONSOLE_ALLOWED_SERVICES = setOf(
        "01", // current data
        "03", // stored trouble codes
        "07", // pending trouble codes
        "09", // vehicle information (VIN, calibration ID)
        "0A", // permanent trouble codes
        "19", // UDS read DTC information
        "1A", // UDS read data by identifier
        "21", // Toyota read by local identifier
        "22", // read data by identifier
        "3E", // tester present
    )

    /** Names for the services people actually try, so the refusal explains itself. */
    private val SERVICE_NAMES = mapOf(
        "04" to "clearing diagnostic codes",
        "11" to "resetting an ECU",
        "14" to "clearing diagnostic information",
        "27" to "security access",
        "2E" to "writing a data identifier",
        "2F" to "controlling an input/output",
        "31" to "running a routine",
        "34" to "requesting a download",
        "35" to "requesting an upload",
        "36" to "transferring data",
        "37" to "ending a transfer",
        "38" to "transferring a file",
        "3B" to "writing a customization block",
        "3D" to "writing a data identifier",
        "85" to "controlling DTC setting",
    )

    /** AT commands that reset or re-protocol the adapter mid-session. */
    private val CONSOLE_REFUSED_AT = setOf("Z", "WS", "D", "SP", "PC")

    /** AT prefixes that write the adapter's own memory, outliving the session. */
    private val CONSOLE_REFUSED_AT_PREFIX = listOf("PP", "@")

    /**
     * Gate for the diagnostics console's free-text header/command. Returns the
     * refusal to show, or null when the command may go out.
     *
     * This is how "never writes" is enforced rather than promised. Only read
     * services are allowed, checked *after* any gateway sub-target is
     * stripped: "40 05 3B …" is a write to Main Body, and reading its first
     * byte as a service number would have called it harmless.
     *
     * Adapter resets and re-protocol commands are refused because they desync
     * the session; AT PP and AT @ write the adapter's own memory, which
     * outlives the session; embedded line breaks are refused because they
     * would smuggle a second command past the ELM. Spaces are stripped before
     * any of that, because the adapter treats "AT Z" and "ATZ" identically.
     */
    fun consoleRefusal(header: String?, command: String): String? {
        val cmd = command.trim().uppercase()
        if (cmd.isEmpty()) return "Nothing to send."
        if (cmd.any { it == '\r' || it == '\n' }) {
            return "Refused: one command at a time — a line break would smuggle a second command to the adapter."
        }
        val h = header?.trim().orEmpty()
        if (h.isNotEmpty() && (h.length > 4 || h.any { it !in '0'..'9' && it !in 'A'..'F' })) {
            return "Refused: \"$h\" is not an 11-bit header (up to 4 hex digits)."
        }
        if (cmd.startsWith("AT")) {
            val compact = cmd.filterNot { it.isWhitespace() }
            val word = compact.removePrefix("AT")
            val resets = CONSOLE_REFUSED_AT.any { at ->
                word == at || (word.startsWith(at) && word.drop(at.length).all { it.isDigit() })
            }
            if (resets) {
                return "Refused: $word resets or reprotocols the adapter mid-session — reconnect from the dashboard instead."
            }
            val persistent = CONSOLE_REFUSED_AT_PREFIX.firstOrNull { word.startsWith(it) }
            if (persistent != null) {
                return "Refused: $word writes the adapter's own memory and outlives this session."
            }
            return null
        }
        val hex = cmd.replace(" ", "")
        if (hex.length < 2 || hex.length % 2 != 0 ||
            hex.any { it !in '0'..'9' && it !in 'A'..'F' }
        ) {
            return "Refused: not a hex command or AT command."
        }
        val service = serviceOf(hex)
        if (service !in CONSOLE_ALLOWED_SERVICES) {
            val what = SERVICE_NAMES[service]
            return "Refused: service $service" +
                (what?.let { " ($it)" } ?: "") +
                " is not a read — the console only sends " +
                CONSOLE_ALLOWED_SERVICES.joinToString("/") + "."
        }
        return null
    }

    /**
     * The service byte of a request, skipping a gateway sub-target.
     *
     * A gateway request is "<sub> <ISO-TP PCI> <service> …" — three bytes
     * before the service — so "40022114" is a read of LID 0x14 at Main Body.
     * Anything that does not parse as that shape keeps its first byte as the
     * service, which fails closed.
     */
    private fun serviceOf(hex: String): String {
        val direct = hex.substring(0, 2)
        if (direct in CONSOLE_ALLOWED_SERVICES) return direct
        if (hex.length >= 6) {
            val pci = hex.substring(2, 4).toIntOrNull(16)
            if (pci != null && pci ushr 4 == 0 && (pci and 0x0F) == (hex.length - 4) / 2) {
                return hex.substring(4, 6)
            }
        }
        return direct
    }
}
