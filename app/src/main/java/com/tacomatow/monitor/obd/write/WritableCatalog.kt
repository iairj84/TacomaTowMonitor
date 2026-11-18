package com.tacomatow.monitor.obd.write

import com.tacomatow.monitor.obd.BlockKey
import com.tacomatow.monitor.obd.ChangeRecord
import com.tacomatow.monitor.obd.NoiseMasks
import com.tacomatow.monitor.obd.VehicleProfile

/**
 * Vehicle-matched writable list, derived from the owner's own mapping.
 *
 * Nothing here is a built-in table: every candidate comes from an imported
 * [ChangeRecord] the owner measured on their own truck. This file only
 * decides which of those records may become a write target, and why the
 * rest stay read-only.
 *
 * Phase 1 rule: exactly one single-bit setting, on one allowlisted block.
 * The 0x11 lock/unlock byte is deliberately held even though the radio
 * changes it — it encodes lock AND unlock as one enum value, and only a
 * fraction of its matrix is mapped. Reconstructing the other half from a
 * stale byte would change a setting the owner never picked.
 */
object WritableCatalog {

    /**
     * Safest single-bit setting to start with: lock/unlock feedback lights,
     * 750/0x40 LID 0x14 byte 0 bit 3 (9A <-> 92). Body ECU, allowlisted LID,
     * 3-byte block (single-frame), reversible on/off, non-driving function.
     */
    const val SAFEST_HEADER = "750"
    const val SAFEST_SUB = 0x40
    const val SAFEST_LID = 0x14
    const val SAFEST_BYTE = 0
    const val SAFEST_BIT = 3

    /** A record the truck in front of us may change, with its proven values. */
    data class Candidate(
        val record: ChangeRecord,
        /** Whole-byte values actually observed for this byte, sorted. */
        val allowedValues: List<String>,
    )

    /** A record held back, with the reason shown in the UI. */
    data class Held(
        val record: ChangeRecord,
        val reason: String,
    )

    fun isSafest(record: ChangeRecord): Boolean =
        record.header.equals(SAFEST_HEADER, ignoreCase = true) &&
            record.lid == SAFEST_LID &&
            record.byteIndex == SAFEST_BYTE &&
            record.changedBits == listOf(SAFEST_BIT)

    /**
     * True only when the record is fully stamped AND every stated field
     * matches the truck in front of us. A blank vehicle stamp is unknown,
     * not "any" — it can never authorize a write. A partial stamp (year
     * but no calibration) is also not enough: same patch or nothing.
     */
    fun vehicleMatches(record: ChangeRecord, profile: VehicleProfile): Boolean {
        if (record.year == null || record.trim.isBlank() || record.calibration.isBlank()) return false
        if (!profile.isSet) return false
        if (profile.year != null && profile.year != record.year) return false
        if (profile.trim.isNotBlank() && !profile.trim.equals(record.trim, ignoreCase = true)) return false
        if (profile.calibration.isNotBlank() && !profile.calibration.equals(record.calibration, ignoreCase = true)) return false
        // Profile states a field the record also states: they must agree.
        // (Covered above; this guards a profile stamped differently.)
        return true
    }

    /** Why this record stays read-only, or null when it is writable. */
    fun holdReason(
        record: ChangeRecord,
        profile: VehicleProfile,
        noise: NoiseMasks = emptyMap(),
    ): String? {
        if (!vehicleMatches(record, profile)) {
            return "Observed on ${record.vehicleLabel()} — not this truck (${profile.summary()})."
        }
        val header = record.header.uppercase().removePrefix("0X")
        if (header !in WritePolicy.ALLOWED_HEADERS || record.lid !in WritePolicy.ALLOWED_LIDS) {
            return "Outside the write allowlist (${record.displayLid} on ${record.header}) — needs per-block verification first."
        }
        if (record.header.equals("750", ignoreCase = true) && record.lid == 0x11) {
            return "Held: one byte encodes lock AND unlock — full matrix needed before any write."
        }
        val bits = record.changedBits
        if (bits.size != 1) {
            return "Held: phase 1 is single-bit settings only."
        }
        val mask = noise[record.header to record.lid]
        if (mask != null) {
            if (mask.isEmpty()) return "Held: block drifts at idle — live data, not a stored setting."
            val bitMask = mask.getOrNull(record.byteIndex) ?: 0
            if (bitMask and (1 shl bits.first()) != 0) {
                return "Held: this bit moves on its own — live data, not a stored setting."
            }
        }
        if (record.before.equals(record.after, ignoreCase = true)) return "Held: no change."
        return null
    }

    fun split(
        records: List<ChangeRecord>,
        profile: VehicleProfile,
        noise: NoiseMasks = emptyMap(),
    ): Pair<List<Candidate>, List<Held>> {
        val byBlock = mutableMapOf<BlockKey, MutableSet<String>>()
        records.forEach { r ->
            byBlock.getOrPut(r.header to r.lid) { mutableSetOf() }.add(r.before.uppercase())
            byBlock.getOrPut(r.header to r.lid) { mutableSetOf() }.add(r.after.uppercase())
        }
        // Deduplicate to one candidate per block/byte/bit: the allowed
        // values are the union of every observed before/after for it.
        val seen = mutableSetOf<String>()
        val writable = mutableListOf<Candidate>()
        val held = mutableListOf<Held>()
        records.sortedWith(compareBy({ it.header }, { it.lid }, { it.byteIndex })).forEach { r ->
            val key = "${r.header}/${r.lid}/${r.byteIndex}/${r.changedBits}"
            if (!seen.add(key)) return@forEach
            val reason = holdReason(r, profile, noise)
            if (reason == null) {
                val values = records
                    .filter { it.header == r.header && it.lid == r.lid && it.byteIndex == r.byteIndex }
                    .flatMap { listOf(it.before.uppercase(), it.after.uppercase()) }
                    .distinct().sorted()
                writable += Candidate(r, values)
            } else {
                held += Held(r, reason)
            }
        }
        return writable to held
    }
}
