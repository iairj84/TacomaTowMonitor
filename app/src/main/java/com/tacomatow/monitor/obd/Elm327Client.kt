package com.tacomatow.monitor.obd

/**
 * ELM327 command layer. Transport-agnostic: works over Classic SPP
 * ([SppTransport]) and BLE GATT ([BleTransport]). Runs on a worker thread;
 * never on main.
 *
 * Init is deliberately TOLERANT: a quiet reply to a cosmetic command
 * (ATE0/ATS0) must not abort the whole connection, and cheap BLE clones
 * routinely need several seconds before their first answer after power-up.
 * The session only fails if the adapter never says anything at all.
 */
class Elm327Client(
    private val transport: ElmTransport,
) {
    private var currentHeader: String? = null
    @Volatile private var open = false

    fun connect(): Boolean {
        if (!transport.open()) return false
        open = true
        var anyAnswer = false

        // Reset first, generously. BLE clones often stay silent for seconds
        // after the notify subscription arms; retry once before giving up.
        var reset = transport.transact("ATZ", 8_000)
        if (reset.isNullOrBlank()) {
            Thread.sleep(600)
            reset = transport.transact("AT", 4_000)
            transport.transact("ATZ", 8_000)
        }
        if (!reset.isNullOrBlank()) anyAnswer = true

        // Cosmetic setup: never fatal if a clone ignores one of these.
        transport.transact("ATE0", 2_000)
        transport.transact("ATL0", 2_000)
        transport.transact("ATS0", 2_000)
        transport.transact("ATH1", 2_000)
        transport.transact("ATSP0", 3_000)

        // Prove we can actually talk to the car, not just to the adapter.
        val ident = transport.transact("ATI", 3_000)
        if (!ident.isNullOrBlank()) anyAnswer = true
        val probe = transport.transact("0100", 4_000)
        if (!probe.isNullOrBlank()) anyAnswer = true

        if (!anyAnswer) {
            open = false
            return false
        }
        return true
    }

    fun isConnected(): Boolean = open

    /** Where setup got stuck (empty unless a BLE link failed). Shown in the error. */
    fun transportStage(): String {
        val ble = transport as? BleTransport ?: return ""
        return ble.stage
    }

    /** Setup trail for the in-app adapter log. */
    fun transcript(): List<String> = transport.transcript()

    /** Read one PID. Returns decoded value or null (unsupported / timeout). */
    fun readPid(def: PidDef): Float? {
        if (!open) return null
        setHeader(def.header)
        val raw = transport.transact(TacomaPids.command(def), 2_500) ?: return null
        val bytes = parseResponse(TacomaPids.command(def), raw) ?: return null
        return TacomaPids.decode(def, bytes)
    }

    fun readVoltage(): Float? {
        val raw = transport.transact("ATRV", 2_000) ?: return null
        // Clone replies look like "12.6V". Be lenient.
        val m = Regex("""(\d{1,2}\.\d)""").find(raw.replace("V", ""))
        return m?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun setHeader(header: String?) {
        if (header == currentHeader) return
        if (header == null) {
            transport.transact("AT SH 7DF", 1_000)
        } else {
            transport.transact("AT SH $header", 1_000)
        }
        currentHeader = header
    }

    /**
     * Parse "6221627XXXX" (Mode 22 -> 0x62) or "4105XX" (Mode 01 -> 0x41).
     * Returns data bytes after the PID echo.
     */
    private fun parseResponse(cmd: String, resp: String): ByteArray? {
        val r = resp.replace(" ", "")
        if (r.contains("NODATA") || r.contains("ERROR") ||
            r.contains("STOPPED") || r.contains("?")
        ) return null
        // Mode 22 echo: "62" + pid(4 hex). Mode 01 echo: "41" + pid(2 hex).
        val echoCandidates = when {
            cmd.startsWith("22") -> listOf("62" + cmd.substring(2))
            cmd.startsWith("01") -> listOf("41" + cmd.substring(2))
            else -> emptyList()
        }
        for (echo in echoCandidates) {
            val idx = r.indexOf(echo)
            if (idx >= 0) {
                val hex = r.substring(idx + echo.length)
                    .takeWhile { it.isDigit() || it in 'A'..'F' }
                if (hex.length >= 2) return hexToBytes(hex)
            }
        }
        // Multi-ECU / CAN-ID prefixed frames (ATH1 on): find echo anywhere.
        return null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'A'..'F' }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun close() {
        open = false
        try { transport.close() } catch (_: Exception) {}
        currentHeader = null
    }
}
