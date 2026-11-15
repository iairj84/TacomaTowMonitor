package com.tacomatow.monitor.obd

import android.content.Context

/**
 * What truck these mappings came from.
 *
 * A LID/byte layout is only as good as the calibration it was observed on, so
 * every recorded change is stamped with the vehicle it came from and an import
 * can be checked against the truck in front of you. Blank means "not recorded"
 * — which is unknown, not a mismatch.
 */
data class VehicleProfile(
    val year: Int? = null,
    val trim: String = "",
    val calibration: String = "",
) {
    val isSet: Boolean get() = year != null || trim.isNotBlank() || calibration.isNotBlank()

    /**
     * Stable key for per-vehicle state (the fast re-scan shortlist).
     * Case-folded so "trd" and "TRD" are the same truck.
     */
    fun key(): String = if (!isSet) {
        "unset"
    } else {
        "${year ?: ""}|${trim.trim().lowercase()}|${calibration.trim().lowercase()}"
    }

    fun summary(): String {
        val parts = buildList {
            year?.let { add(it.toString()) }
            if (trim.isNotBlank()) add(trim)
            if (calibration.isNotBlank()) add("cal $calibration")
        }
        return if (parts.isEmpty()) "unset" else parts.joinToString(" · ")
    }

    /**
     * True only when both sides state a value and they disagree. An entry with
     * no vehicle data is unverified, not wrong, and shouldn't be flagged.
     */
    fun conflictsWith(record: ChangeRecord): Boolean =
        (year != null && record.year != null && year != record.year) ||
            (trim.isNotBlank() && record.trim.isNotBlank() && !trim.equals(record.trim, true)) ||
            (
                calibration.isNotBlank() && record.calibration.isNotBlank() &&
                    !calibration.equals(record.calibration, true)
                )
}

object VehicleProfileStore {

    private const val PREFS = "vehicle_profile"
    private const val KEY_YEAR = "year"
    private const val KEY_TRIM = "trim"
    private const val KEY_CAL = "calibration"

    fun load(context: Context): VehicleProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val year = prefs.getInt(KEY_YEAR, 0)
        return VehicleProfile(
            year = year.takeIf { it in 1980..2100 },
            trim = prefs.getString(KEY_TRIM, "") ?: "",
            calibration = prefs.getString(KEY_CAL, "") ?: "",
        )
    }

    fun save(context: Context, profile: VehicleProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_YEAR, profile.year ?: 0)
            .putString(KEY_TRIM, profile.trim)
            .putString(KEY_CAL, profile.calibration)
            .apply()
    }
}
