package com.tacomatow.monitor.obd

/**
 * Mode 02 freeze frame: the conditions an ECU recorded when a code set.
 *
 * Read-only, like every other diagnostic service here — SAE J1979 defines no
 * mode 02 write. The frame is the difference between "P0711 came back" and
 * "it set at 62 mph, 240 °F pan, 3100 rpm on a grade", which is usually all a
 * tow fault needs to be understood.
 *
 * The request is `02 <PID> <frame>`: the extra byte selects which stored frame,
 * and its positive reply echoes it (`42 <PID> <frame> …`), so a reply is only
 * accepted for the frame that was asked. PID 02 is the code that triggered the
 * frame, so each frame can be labelled with its own DTC.
 *
 * Frame 00 is the emissions freeze frame every OBD-II vehicle keeps. Frames
 * 01 and up are optional and hold further per-code snapshots where the ECU
 * keeps them — many do not, and answer nothing, which is cheap to find out.
 * Pending codes have no frame of their own in the standard; where an ECU does
 * record one per code, it arrives here as a frame whose DTC is the pending
 * code.
 */
object FreezeFrame {

    /** Frame 00 first, then the optional per-code records worth probing. */
    val FRAMES = listOf(0, 1, 2)

    /**
     * PIDs worth reading, in the order they should be shown: what the driver
     * was doing first, then the load and temperatures that explain the code,
     * then the distance/time counters.
     */
    val WATCH = listOf(
        0x0C, // RPM
        0x0D, // vehicle speed
        0x05, // coolant
        0x5C, // engine oil
        0x04, // calculated load
        0x11, // throttle
        0x0B, // intake manifold pressure
        0x10, // MAF
        0x0F, // intake air
        0x46, // ambient air
        0x42, // control module voltage
        0x2F, // fuel level
        0x1F, // run time since start
        0x21, // distance travelled with MIL on
        0x33, // barometric pressure
    )

    /** The command for one reading: `02` + PID + frame, each a byte. */
    fun request(pid: Int, frame: Int): String = "02%02X%02X".format(pid and 0xFF, frame and 0xFF)

    /** Data bytes after `42 <pid> <frame>`, or null when the reply is something else. */
    fun data(response: String?, pid: Int, frame: Int): ByteArray? {
        if (response == null) return null
        val echo = byteArrayOf(0x42, pid.toByte(), frame.toByte())
        val msg = IsoTp.firstStartingWith(response, echo) ?: return null
        if (msg.size <= echo.size) return null
        return msg.copyOfRange(echo.size, msg.size)
    }

    /**
     * Supported-PID bitmap from `02 00 <frame>`. Same bit layout as mode 01:
     * the top bit of byte 0 is PID 0x01. Null when the reply is not a bitmap.
     */
    fun supported(payload: ByteArray?): Set<Int>? {
        if (payload == null || payload.size < 4) return null
        val out = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val byte = payload[i / 8].toInt() and 0xFF
            if ((byte shr (7 - (i % 8))) and 1 == 1) out += i + 1
        }
        return out
    }

    /** One decoded reading: label and the text to show, already in imperial units. */
    data class Reading(val label: String, val text: String)

    /** One stored frame. [dtc] is null when the frame exists but names no code. */
    data class Snapshot(
        val frame: Int,
        val dtc: String?,
        val readings: List<Reading>,
    )

    /**
     * Everything the ECU keeps. [note] explains an empty or partial result so
     * the screen never shows a blank card with no reason for it.
     */
    data class Report(
        val snapshots: List<Snapshot> = emptyList(),
        val note: String? = null,
    ) {
        val isEmpty: Boolean get() = snapshots.isEmpty()
    }

    /** Decode one PID's bytes. Null for anything not in [WATCH]. */
    fun reading(pid: Int, bytes: ByteArray?): Reading? {
        if (bytes == null || bytes.isEmpty()) return null
        val a = bytes[0].toInt() and 0xFF
        val b = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
        return when (pid) {
            0x04 -> Reading("Calculated load", "%.0f %%".format(a * 100f / 255f))
            0x05 -> Reading("Coolant", "%.0f °F".format(celsiusToF(a - 40)))
            0x0B -> Reading("Intake MAP", "$a kPa")
            0x0C -> Reading("RPM", "%.0f".format((a * 256f + b) / 4f))
            0x0D -> Reading("Speed", "%.0f mph".format(a * 0.621371f))
            0x0F -> Reading("Intake air", "%.0f °F".format(celsiusToF(a - 40)))
            0x10 -> Reading("MAF", "%.2f g/s".format((a * 256f + b) / 100f))
            0x11 -> Reading("Throttle", "%.0f %%".format(a * 100f / 255f))
            0x1F -> Reading("Run time", "%d s".format(a * 256 + b))
            0x21 -> Reading("Distance with MIL", "%d mi".format(((a * 256 + b) * 0.621371f).toInt()))
            0x2F -> Reading("Fuel level", "%.0f %%".format(a * 100f / 255f))
            0x33 -> Reading("Barometric", "$a kPa")
            0x42 -> Reading("Module voltage", "%.1f V".format((a * 256 + b) / 1000f))
            0x46 -> Reading("Ambient air", "%.0f °F".format(celsiusToF(a - 40)))
            0x5C -> Reading("Engine oil", "%.0f °F".format(celsiusToF(a - 40)))
            else -> null
        }
    }

    /** The DTC a frame was recorded for, from its `02 02 <frame>` reply. */
    fun dtc(response: String?, frame: Int): String? {
        val payload = data(response, 0x02, frame) ?: return null
        if (payload.size < 2) return null
        return DtcDecode.code(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
    }

    private fun celsiusToF(c: Int): Float = c * 9f / 5f + 32f
}
