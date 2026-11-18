package com.tacomatow.monitor.obd.write

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Restore path for the write flow, separate from the explorer snapshots.
 *
 * First payload stored per block is kept as the original forever; every
 * write updates latest. A write without a parseable stored payload here is
 * impossible, not discouraged.
 */
object WriteBackupStore {

    private const val PREFS = "write_backup"
    const val KEY = "backups"

    data class Backup(val payloadHex: String, val at: Long, val calibration: String)

    fun load(context: Context, header: String, lid: Int): Pair<Backup?, Backup?> {
        val root = runCatching {
            JSONObject(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"),
            )
        }.getOrNull() ?: return null to null
        val o = root.optJSONObject("$header/$lid") ?: return null to null
        fun read(p: String): Backup? {
            val b = o.optJSONObject(p) ?: return null
            val payload = b.optString("payload").takeIf { it.isNotBlank() } ?: return null
            return Backup(payload, b.optLong("at"), b.optString("cal"))
        }
        return read("original") to read("latest")
    }

    fun store(context: Context, header: String, lid: Int, payloadHex: String, calibration: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}")) }.getOrElse { JSONObject() }
        val key = "$header/$lid"
        val existing = root.optJSONObject(key) ?: JSONObject()
        val now = System.currentTimeMillis()
        val entry = JSONObject().put("payload", payloadHex).put("at", now).put("cal", calibration)
        if (existing.optJSONObject("original") == null) existing.put("original", entry)
        existing.put("latest", entry)
        root.put(key, existing)
        prefs.edit().putString(KEY, root.toString()).apply()
    }
}

/** Per-block 60 s cooldown between writes. */
object WriteCooldownStore {

    private const val PREFS = "write_cooldown"
    private const val KEY = "last_by_block"

    fun lastWriteAgo(context: Context, header: String, lid: Int): Long? {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("$header/$lid", -1L).takeIf { it >= 0 } ?: return null
        return System.currentTimeMillis() - at
    }

    fun markWritten(context: Context, header: String, lid: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("$header/$lid", System.currentTimeMillis()).apply()
    }
}

/** Append-only local audit log, one line per attempt, exportable as text. */
object WriteAuditLog {

    private const val PREFS = "write_audit"
    private const val KEY = "lines"

    fun append(context: Context, line: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("line", line))
        // Cap at 500 entries; oldest drop first, never edited in place.
        while (arr.length() > 500) arr.remove(0)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun export(context: Context): String {
        val arr = runCatching {
            JSONArray(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"),
            )
        }.getOrElse { JSONArray() }
        if (arr.length() == 0) return "No write attempts recorded."
        return buildString {
            appendLine("Tacoma write audit (${arr.length()})")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                appendLine("${java.time.Instant.ofEpochMilli(o.optLong("at"))}  ${o.optString("line")}")
            }
        }.trimEnd()
    }
}
