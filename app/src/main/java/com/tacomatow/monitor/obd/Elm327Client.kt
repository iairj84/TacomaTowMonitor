package com.tacomatow.monitor.obd

/**
 * ELM327 command layer. Transport-agnostic: works over Classic SPP
 * ([SppTransport]) and BLE GATT ([BleTransport]). Runs on a worker thread;
 * never on main.
 *
 * Init: ATZ / ATE0 / ATL0 / ATS0 / ATH1 / ATSP0, then per-PID header
 * switching (AT SH 701 for Tacoma trans temps, AT SH 7DF for Mode 01).
 */
class Elm327Client(
    private val transport: ElmTransport,
) {
    private var currentHeader: String? = null
    @Volatile private var open = false

    fun connect(timeoutMs: Int = 15_000): Boolean {
        if (!transport.open()) return false
        open = true
        // Order matters: reset, quiet echo/linefeeds, show headers so we can
        // validate ECU addresses, auto protocol for the Tacoma's 11-bit 500k CAN.
        val init = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")
        for (cmd in init) {
            val resp = transport.transact(cmd, 3_000) ?: return false
            if (cmd == "ATZ" && resp.isBlank()) {
                // Cheap clones sometimes swallow the first reset; one retry.
                transport.transact(cmd, 3_000) ?: return false
            }
        }
        // Warm the protocol on the bus so the first gauge read is fast.
        transport.transact("0100", 5_000)
        return true
    }

    fun isConnected(): Boolean = open

    /** Where a BLE setup got stuck (empty for Classic). Shown in the error. */
    fun transportStage(): String = (transport as? BleTransport)?.stage.orEmpty()

    /** Read one PID. Returns decoded value or null (unsupported / timeout). */
    fun readPid(def: PidDef): Float? {
        if (!open) return null
        setHeader(def.header)
        val raw = transport.transact(TacomaPids.command(def), 2_000) ?: return null
        val bytes = parseResponse(TacomaPids.command(def), raw) ?: return null
        return TacomaPids.decode(def, bytes)
    }

    fun readVoltage(): Float? {
        val raw = transport.transact("ATRV", 1_500) ?: return null
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
