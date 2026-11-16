package com.tacomatow.monitor.obd

import android.content.Context
import org.json.JSONArray

/** Module plus local identifier: how one customize block is named. */
typealias BlockKey = Pair<String, Int>

/**
 * Which blocks move on their own.
 *
 * A body ECU's blocks are not just the settings menu: they carry key
 * position, door switches, light sensor, blower, dimmer, and whatever else
 * the module is reporting at that instant. Read the same truck twice with
 * nothing changed and some blocks still differ — and those blocks will
 * differ again after a setting change, which is how a diff fills with
 * changes that are true once and never again.
 *
 * The fix is not to guess which ones those are. Take several scans in a row
 * with nothing changed, and every block that moved in that window is noise
 * by definition. Diffs then ignore them.
 */
object CustomizeNoise {

    fun key(reading: LidReading): BlockKey = reading.header to reading.lid

    /**
     * Blocks that differed across [runs] taken with nothing changed.
     *
     * Only blocks every run answered are considered: a block that was never
     * asked (a fast re-scan narrows the plan) would otherwise look like it
     * moved. Two runs are the minimum, and more is better — one extra scan
     * catches noise that happened to hold still for a moment.
     */
    fun volatileAcross(runs: List<List<LidReading>>): Set<BlockKey> {
        if (runs.size < 2) return emptySet()
        val maps = runs.map { run -> run.associateBy(::key) }
        val everywhere = maps.map { it.keys }.reduce { acc, keys -> acc intersect keys }
        return everywhere.filter { k ->
            maps.mapNotNull { it[k]?.payloadHex }.distinct().size > 1
        }.toSet()
    }

    /**
     * Blocks that answered in one run and not another. Not part of the
     * volatile set above — a block that stopped answering is a different
     * problem (adapter, session) — but worth reporting.
     */
    fun flakyAcross(runs: List<List<LidReading>>): Set<BlockKey> {
        if (runs.size < 2) return emptySet()
        val maps = runs.map { run -> run.associateBy(::key) }
        val keys = maps.flatMap { it.keys }.toSet()
        return keys.filter { k ->
            maps.mapNotNull { it[k]?.supported }.distinct().size > 1
        }.toSet()
    }
}

/** Volatile block set, kept across app restarts and per vehicle profile. */
object CustomizeNoiseStore {

    private const val PREFS = "customize_noise"
    private const val KEY_BLOCKS = "volatile"

    fun load(context: Context): Set<BlockKey> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOCKS, null) ?: return emptySet()
        return try {
            val array = JSONArray(json)
            buildSet {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(o.getString("h") to o.getInt("l"))
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun save(context: Context, keys: Set<BlockKey>) {
        val array = JSONArray()
        keys.sortedWith(compareBy({ it.first }, { it.second })).forEach { (header, lid) ->
            array.put(org.json.JSONObject().put("h", header).put("l", lid))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLOCKS, array.toString())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
