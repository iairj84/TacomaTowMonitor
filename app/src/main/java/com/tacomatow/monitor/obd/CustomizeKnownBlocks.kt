package com.tacomatow.monitor.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blocks that have ever answered, kept per vehicle.
 *
 * A full sweep costs a 1.2 s timeout on every probe that is silent, which on a
 * real truck is the large majority. Remembering the ones that answered turns a
 * routine re-scan into a few seconds.
 *
 * The shortlist is keyed by the vehicle profile, so pointing the app at a
 * different truck starts clean instead of carrying a stale list that would
 * quietly shape which blocks get read.
 *
 * Deliberately never prunes: a block that answered once is cheap to re-ask, and
 * dropping it would quietly stop us noticing that it came back.
 */
object CustomizeKnownBlocks {

    private const val PREFS = "customize_known_v2"
    private const val KEY = "by_vehicle"
    private const val VERSION = 2

    /** Everything stored, keyed by [VehicleProfile.key]. */
    private fun loadAll(context: Context): MutableMap<String, MutableMap<String, MutableSet<Int>>> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableMapOf()
        return try {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { vehicleKey ->
                    val blocks = root.optJSONObject(vehicleKey) ?: return@forEach
                    val perEcu = mutableMapOf<String, MutableSet<Int>>()
                    blocks.keys().forEach { header ->
                        val array: JSONArray = blocks.optJSONArray(header) ?: return@forEach
                        val lids = mutableSetOf<Int>()
                        for (i in 0 until array.length()) lids.add(array.optInt(i))
                        if (lids.isNotEmpty()) perEcu[header] = lids
                    }
                    if (perEcu.isNotEmpty()) put(vehicleKey, perEcu)
                }
            }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun persist(context: Context, all: Map<String, Map<String, Set<Int>>>) {
        val root = JSONObject()
        all.forEach { (vehicleKey, perEcu) ->
            val blocks = JSONObject()
            perEcu.forEach { (header, lids) -> blocks.put(header, JSONArray(lids.sorted())) }
            root.put(vehicleKey, blocks)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, root.toString())
            .putInt("version", VERSION)
            .apply()
    }

    /**
     * This vehicle's shortlist. A truck that has been scanned but not yet named
     * keeps its list under "unset", so naming the profile inherits it instead
     * of silently losing it (the next full scan moves it into the named slot).
     */
    fun load(context: Context, profile: VehicleProfile): Map<String, Set<Int>> {
        val all = loadAll(context)
        val bucket = all[profile.key()] ?: all["unset"].takeIf { profile.isSet }
        return bucket?.mapValues { it.value.toSet() } ?: emptyMap()
    }

    /** Merge a sweep's answering blocks into this vehicle's shortlist. */
    fun record(
        context: Context,
        profile: VehicleProfile,
        readings: List<LidReading>,
    ): Map<String, Set<Int>> {
        val all = loadAll(context)
        val perEcu = all.getOrPut(profile.key()) { mutableMapOf() }
        readings.filter { it.supported }.forEach { r ->
            perEcu.getOrPut(r.header) { mutableSetOf() }.add(r.lid)
        }
        persist(context, all)
        return perEcu.mapValues { it.value.toSet() }
    }

    fun clear(context: Context, profile: VehicleProfile) {
        val all = loadAll(context)
        all.remove(profile.key())
        persist(context, all)
    }
}
