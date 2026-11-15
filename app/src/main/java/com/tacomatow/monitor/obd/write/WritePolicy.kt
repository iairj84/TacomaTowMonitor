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

    /** Writable blocks: body, combination meter, A/C amplifier. Nothing else. */
    val ALLOWED_HEADERS = setOf("750", "7C0", "7C4")

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
}
