package com.tacomatow.monitor.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Portable mapping file for observed customize settings.
 *
 * The contents are only ever what an owner measured on their own truck —
 * a named setting, the block it lives in, and the byte values either side of
 * the change. Nothing here is copied from vendor software or a paid database,
 * which is what makes it safe to hand to another owner.
 *
 * Deliberately dumb and flat: version, a little provenance, and a list of
 * entries. An importer that needs anything cleverer is doing it wrong.
 */
object CustomizeMapping {

    const val FORMAT = "tacoma-customize-mapping"
    const val VERSION = 1
    const val MIME = "application/json"
    const val FILE_NAME = "tacoma-customize-mapping.json"

    data class ImportResult(
        val records: List<ChangeRecord>,
        val error: String? = null,
    )

    fun exportJson(records: List<ChangeRecord>): String {
        val entries = JSONArray()
        records.forEach { r ->
            entries.put(
                JSONObject()
                    .put("label", r.label)
                    .put("header", r.header)
                    .put("ecu", r.ecuLabel)
                    .put("lid", "%02X".format(r.lid))
                    .put("byte", r.byteIndex)
                    .put("before", r.before)
                    .put("after", r.after)
                    .put("year", r.year ?: JSONObject.NULL)
                    .put("trim", r.trim)
                    .put("calibration", r.calibration)
                    .put("observed_at", Instant.ofEpochMilli(r.at).toString())
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("vehicle", JSONObject().put("model", "Toyota Tacoma").put("generation", "3rd (2016-2023)"))
            .put(
                "note",
                "Observed by read-diff on a real vehicle: change one setting, compare " +
                    "before/after. Not extracted from any commercial product."
            )
            .put("exported_at", Instant.now().toString())
            .put("entries", entries)
            .toString(2)
    }

    fun write(context: Context, records: List<ChangeRecord>): File? = runCatching {
        val dir = File(context.cacheDir, "mappings").apply { mkdirs() }
        File(dir, FILE_NAME).apply { writeText(exportJson(records)) }
    }.getOrNull()

    fun parse(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ImportResult(emptyList(), "That file isn't valid JSON.")
        if (root.optString("format") != FORMAT) {
            return ImportResult(emptyList(), "Not a $FORMAT file.")
        }
        val array = root.optJSONArray("entries")
            ?: return ImportResult(emptyList(), "No entries in that file.")
        val out = mutableListOf<ChangeRecord>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // Exported as a hex string ("1E"); a JSON number is decimal —
            // optString coerces 30 to "30", which parses as 0x30 = 48.
            val lid = when (val v = o.opt("lid")) {
                is Number -> v.toInt().takeIf { it in 0..0xFF }
                else -> o.optString("lid").trim().removePrefix("0x").removePrefix("0X")
                    .toIntOrNull(16)
            } ?: continue
            val before = o.optString("before").trim()
            val after = o.optString("after").trim()
            if (before.isEmpty() || after.isEmpty()) continue
            out += ChangeRecord(
                label = o.optString("label", "Unnamed change").ifBlank { "Unnamed change" },
                header = o.optString("header", "750"),
                ecuLabel = o.optString("ecu", "ECU"),
                lid = lid,
                byteIndex = o.optInt("byte", 0),
                before = before,
                after = after,
                at = runCatching { Instant.parse(o.optString("observed_at")).toEpochMilli() }
                    .getOrElse { System.currentTimeMillis() },
                year = o.optInt("year", 0).takeIf { it in 1980..2100 },
                trim = o.optString("trim", ""),
                calibration = o.optString("calibration", o.optString("cal", "")),
            )
        }
        if (out.isEmpty()) return ImportResult(emptyList(), "That file had no usable entries.")
        return ImportResult(out)
    }

    /**
     * Append imported entries, dropping exact duplicates.
     * Same block and byte with a different value is a genuine second state,
     * so it is kept — that's how a three-way option ends up documented.
     */
    fun merge(
        existing: List<ChangeRecord>,
        incoming: List<ChangeRecord>,
    ): Pair<List<ChangeRecord>, Int> {
        fun key(r: ChangeRecord) = "${r.header}/${r.lid}/${r.byteIndex}/${r.before}->${r.after}"
        val seen = existing.mapTo(mutableSetOf()) { key(it) }
        val merged = existing.toMutableList()
        var skipped = 0
        incoming.forEach { r ->
            if (!seen.add(key(r))) skipped++ else merged += r
        }
        return merged to skipped
    }
}
