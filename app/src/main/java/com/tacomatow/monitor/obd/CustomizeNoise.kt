package com.tacomatow.monitor.obd

import android.content.Context
import org.json.JSONArray

/** Module plus local identifier: how one customize block is named. */
typealias BlockKey = Pair<String, Int>

/**
 * Per-bit noise masks learned from idle scans.
 *
 * Key: block. Value: one bitmask per payload byte; bit set = that bit moved
 * while nothing was changed. An entry with an empty list is a legacy
 * whole-block mark (every bit counts as volatile).
 */
typealias NoiseMasks = Map<BlockKey, List<Int>>

/**
 * Which bits move on their own.
 *
 * A body ECU's blocks are not just the settings menu: they carry key
 * position, door switches, light sensor, blower, dimmer, and whatever else
 * the module is reporting at that instant. Read the same truck twice with
 * nothing changed and some bits still differ — and those bits will
 * differ again after a setting change, which is how a diff fills with
 * changes that are true once and never again.
 *
 * The fix is not to guess which ones those are. Take several scans in a row
 * with nothing changed, and every bit that moved in that window is noise
 * by definition. Diffs then ignore those bits — but keep the stable bits of
 * the same block, so a setting hiding in a steady byte of a lively block is
 * still found. What was ignored stays visible, so the mask is evidence, not
 * a quiet filter.
 */
object CustomizeNoise {

    fun key(reading: LidReading): BlockKey = reading.header to reading.lid

    /**
     * Per-bit masks of what moved across [runs] taken with nothing changed.
     *
     * Only blocks every run answered are considered: a block that was never
     * asked (a fast re-scan narrows the plan) would otherwise look like it
     * moved. Two runs are the minimum, and more is better — one extra scan
     * catches noise that happened to hold still for a moment.
     *
     * A byte that fails to parse in any run, or a length change across runs,
     * marks the affected bytes fully volatile (0xFF): claiming a bit is
     * stable requires seeing it hold still, not failing to read it.
     */
    fun masksAcross(runs: List<List<LidReading>>): NoiseMasks {
        if (runs.size < 2) return emptyMap()
        val maps = runs.map { run -> run.associateBy(::key) }
        val everywhere = maps.map { it.keys }.reduce { acc, keys -> acc intersect keys }
        val out = mutableMapOf<BlockKey, List<Int>>()
        for (k in everywhere) {
            val payloads = maps.map { it.getValue(k).payloadHex }
            if (payloads.any { it == null }) continue
            val parsed = payloads.map { parseBytes(it!!) }
            if (parsed.any { it == null }) {
                // Unparseable payload: cannot prove any bit stable.
                val width = parsed.mapNotNull { it?.size }.maxOrNull() ?: 0
                if (width > 0) out[k] = List(width) { 0xFF }
                continue
            }
            val bytes = parsed.map { it!! }
            val width = bytes.maxOf { it.size }
            val mask = MutableList(width) { 0 }
            for (j in 0 until width) {
                var m = 0
                val base = bytes[0].getOrNull(j)
                if (base == null) {
                    m = 0xFF
                } else {
                    for (i in 1 until bytes.size) {
                        val other = bytes[i].getOrNull(j)
                        m = if (other == null) 0xFF else m or (base xor other)
                    }
                }
                mask[j] = m
            }
            if (mask.any { it != 0 }) out[k] = mask
        }
        return out
    }

    /**
     * Blocks that differed across [runs] taken with nothing changed.
     *
     * Kept for callers that need a block-level answer — the write path, for
     * example, where any drift in a block marks the whole block as live data.
     * The diff itself uses [masksAcross] so stable bits survive.
     */
    fun volatileAcross(runs: List<List<LidReading>>): Set<BlockKey> =
        masksAcross(runs).keys

    private fun parseBytes(hex: String): List<Int>? {
        val tokens = hex.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val out = ArrayList<Int>(tokens.size)
        for (t in tokens) {
            val v = t.toIntOrNull(16) ?: return null
            if (v !in 0..0xFF) return null
            out += v
        }
        return out
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

/** Per-bit volatile masks, kept across app restarts and per vehicle profile. */
object CustomizeNoiseStore {

    private const val PREFS = "customize_noise"
    private const val KEY_BLOCKS = "volatile"

    fun load(context: Context): NoiseMasks {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOCKS, null) ?: return emptyMap()
        return try {
            val array = JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val key = o.getString("h") to o.getInt("l")
                    if (o.has("m")) {
                        val m = o.getJSONArray("m")
                        put(key, List(m.length()) { j -> m.getInt(j) and 0xFF })
                    } else {
                        // Legacy whole-block entry: empty list = every bit volatile.
                        put(key, emptyList())
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(context: Context, masks: NoiseMasks) {
        val array = JSONArray()
        masks.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .forEach { (key, mask) ->
                val o = org.json.JSONObject().put("h", key.first).put("l", key.second)
                o.put("m", JSONArray(mask))
                array.put(o)
            }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLOCKS, array.toString())
            .apply()
    }

    /** Legacy saver for callers/tests that still hold a block set. */
    fun saveBlocks(context: Context, keys: Set<BlockKey>) {
        save(context, keys.associateWith { emptyList() })
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
