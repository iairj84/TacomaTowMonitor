package com.tacomatow.monitor.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved customize sweep, kept across app restarts.
 *
 * Body writes can land on shutdown or only after an ignition cycle, so the
 * "change one setting, then compare" workflow has to survive the app being
 * killed. Only blocks that answered are stored: the payload is all a diff
 * needs, and keeping raw replies would bloat the file for no benefit.
 *
 * [label] is what the owner is about to change ("Auto-lock by shift from P").
 * Without it a diff is an anonymous byte moving, which is useless later.
 */
object CustomizeSnapshotStore {

    data class Snapshot(
        val at: Long,
        val readings: List<LidReading>,
        val label: String = "",
    )

    private const val PREFS = "customize_snapshot"
    private const val KEY_AT = "at"
    private const val KEY_LABEL = "label"
    private const val KEY_BLOCKS = "blocks"

    fun load(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BLOCKS, null) ?: return null
        val at = prefs.getLong(KEY_AT, 0L)
        val label = prefs.getString(KEY_LABEL, "") ?: ""
        val readings = try {
            val array = JSONArray(json)
            buildList<LidReading> {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        LidReading(
                            header = o.getString("h"),
                            ecuLabel = o.optString("e", "ECU"),
                            lid = o.getInt("l"),
                            raw = null,
                            payloadHex = o.getString("p"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupt or older layout — treat as "no snapshot" rather than crash.
            return null
        }
        return if (readings.isEmpty()) null else Snapshot(at, readings, label)
    }

    fun save(context: Context, snapshot: Snapshot) {
        val array = JSONArray()
        snapshot.readings.filter { it.supported }.forEach { r ->
            array.put(
                JSONObject()
                    .put("h", r.header)
                    .put("e", r.ecuLabel)
                    .put("l", r.lid)
                    .put("p", r.payloadHex)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_AT, snapshot.at)
            .putString(KEY_LABEL, snapshot.label)
            .putString(KEY_BLOCKS, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * One confirmed, named change: "this setting lives in this byte of this block".
 *
 * This is the smallest useful unit of a mapping table, and the only kind of
 * data worth sharing with other owners — observed on a real truck, named, with
 * both the before and after value.
 */
data class ChangeRecord(
    val label: String,
    val header: String,
    val ecuLabel: String,
    val lid: Int,
    val byteIndex: Int,
    val before: String,
    val after: String,
    val at: Long,
    /** Vehicle this was observed on. Blank means unknown, not "any". */
    val year: Int? = null,
    val trim: String = "",
    val calibration: String = "",
) {
    fun vehicleLabel(): String {
        val parts = buildList {
            year?.let { add(it.toString()) }
            if (trim.isNotBlank()) add(trim)
            if (calibration.isNotBlank()) add("cal $calibration")
        }
        return if (parts.isEmpty()) "vehicle not recorded" else parts.joinToString(" · ")
    }

    val displayLid: String get() = "0x%02X".format(lid)
    /** Bits that differ, derived from the stored values (LSB = bit 0). */
    val changedBits: List<Int>
        get() {
            val a = before.toIntOrNull(16) ?: return emptyList()
            val b = after.toIntOrNull(16) ?: return emptyList()
            val diff = a xor b
            return (0..7).filter { diff and (1 shl it) != 0 }
        }

    fun describe(): String {
        val bits = changedBits
        val suffix = when (bits.size) {
            0 -> ""
            1 -> "  (bit ${bits.first()})"
            else -> "  (bits ${bits.joinToString(", ")})"
        }
        return "$displayLid · $ecuLabel — byte $byteIndex: $before → $after$suffix"
    }
}

/** Append-only list of named changes, so settings stay distinguishable. */
object CustomizeRecordStore {

    private const val PREFS = "customize_records"
    private const val KEY_RECORDS = "records"

    fun load(context: Context): List<ChangeRecord> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            buildList<ChangeRecord> {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        ChangeRecord(
                            label = o.optString("label", "Unnamed change"),
                            header = o.getString("h"),
                            ecuLabel = o.optString("e", "ECU"),
                            lid = o.getInt("l"),
                            byteIndex = o.getInt("b"),
                            before = o.getString("before"),
                            after = o.getString("after"),
                            at = o.getLong("at"),
                            year = o.optInt("year", 0).takeIf { it in 1980..2100 },
                            trim = o.optString("trim", ""),
                            calibration = o.optString("cal", ""),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, records: List<ChangeRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject()
                    .put("label", r.label)
                    .put("h", r.header)
                    .put("e", r.ecuLabel)
                    .put("l", r.lid)
                    .put("b", r.byteIndex)
                    .put("before", r.before)
                    .put("after", r.after)
                    .put("at", r.at)
                    .put("year", r.year ?: JSONObject.NULL)
                    .put("trim", r.trim)
                    .put("cal", r.calibration)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Plain-text table of records, for sharing verified findings. */
    fun describe(records: List<ChangeRecord>, stamp: (Long) -> String): String {
        if (records.isEmpty()) return "No recorded changes."
        val sb = StringBuilder("Tacoma customize records (${records.size})\n\n")
        records.forEach { r ->
            sb.appendLine("${stamp(r.at)}  ${r.label}")
            sb.appendLine("  ${r.describe()}  (${r.header})")
        }
        return sb.toString().trimEnd()
    }
}
