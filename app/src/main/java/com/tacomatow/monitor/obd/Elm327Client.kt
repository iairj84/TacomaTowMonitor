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
    @Volatile private var protocol = "?"

    /** Which ELM protocol is in force ("6" = CAN 11/500, "0" = auto). */
    fun activeProtocol(): String = protocol
    @Volatile private var transportOpened = false

    /** False means we never even got a byte pipe — very different from a silent dongle. */
    fun didOpenTransport(): Boolean = transportOpened

    fun connect(): Boolean {
        if (!transport.open()) return false
        transportOpened = true
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
        // Pin CAN 11-bit/500k — the 3rd-gen Tacoma bus. With auto protocol
        // (ATSP0) these clones silently return NO DATA for the 0x701 header
        // used by the transmission DID. Fall back to auto if SP6 is refused.
        transport.transact("ATSP6", 3_000)
        protocol = "6"
        if (transport.transact("0100", 4_000).isNullOrBlank()) {
            transport.transact("ATSP0", 3_000)
            protocol = "0"
            transport.transact("0100", 4_000)
        }

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

    /** Where setup got stuck. Shown in the error text. */
    fun transportStage(): String = when (val t = transport) {
        is BleTransport -> t.stage
        is SppTransport -> t.stage
        else -> ""
    }

    /** Setup trail for the in-app adapter log. */
    fun transcript(): List<String> = transport.transcript()

    /**
     * Outcome of a PID read. [definitiveMiss] means the ECU answered "no
     * data" — retrying is pointless and expensive, so callers can back off
     * instead of burning seconds on a PID this vehicle does not publish.
     */
    data class PidAttempt(
        val value: Float?,
        val definitiveMiss: Boolean,
        /** e.g. "7F 22 31" — the ECU answered and refused. Distinct from silence. */
        val refusal: String? = null,
    )

    /**
     * Read one PID. Retried once on a *silent* miss: BLE clones drop the
     * occasional reply and a lone drop must not blank a gauge. An explicit
     * NO DATA is not retried.
     */
    fun readPidAttempt(def: PidDef): PidAttempt {
        if (!open) return PidAttempt(null, false)
        setHeader(def.header)
        val cmd = TacomaPids.command(def)
        repeat(2) { attempt ->
            val raw = transport.transact(cmd, 3_500)
            if (raw != null) {
                val noData = raw.contains("NODATA") || raw.contains("?") || raw.contains("ERROR")
                val bytes = parseResponse(cmd, raw)
                val value = bytes?.let { TacomaPids.decode(def, it) }
                if (value != null) return PidAttempt(value, false)
                val refused = negativeResponse(raw)
                if (refused != null) return PidAttempt(null, true, refused)
                if (noData) return PidAttempt(null, true)
            }
            if (attempt == 0) Thread.sleep(120)
        }
        return PidAttempt(null, false)
    }

    fun readPid(def: PidDef): Float? = readPidAttempt(def).value

    /**
     * Recognises a negative response ("7F <service> <code>") anywhere in a
     * reply. This is the single most useful distinction in OBD debugging: a
     * refusal proves the request arrived, whereas NO DATA only proves the
     * adapter gave up.
     */
    fun negativeResponse(raw: String): String? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            if (tokens[i].equals("7F", ignoreCase = true) && i + 2 < tokens.size) {
                return "7F ${tokens[i + 1].uppercase()} ${tokens[i + 2].uppercase()}"
            }
        }
        return null
    }

    /**
     * Enter the extended diagnostic session (10 03). Toyota ECUs and the
     * 0x701 gateway commonly refuse manufacturer reads in the default
     * session, answering "no data" — which looks identical to an unsupported
     * PID. Harmless and non-persistent: it lapses on power cycle/idle.
     */
    fun enterExtendedSession(): Boolean {
        if (!open) return false
        setHeader(null)
        val r = transport.transact("1003", 2_500)
        val ok = r != null && !r.contains("7F")
        return ok
    }

    /**
     * Drop back to automatic protocol selection. Some clones behave
     * differently pinned (ATSP6) than in auto-search, and Torque defaults to
     * auto — worth matching when a pinned session returns nothing.
     */
    fun setAutoProtocol(): Boolean {
        val r = transport.transact("ATSP0", 4_000)
        protocol = "0"
        currentHeader = null
        return r != null
    }

    /** Raw hex reply for an arbitrary header/command — used by PID detection. */
    fun readRaw(header: String?, command: String, timeoutMs: Int = 3_500): String? {
        if (!open) return null
        setHeader(header)
        return transport.transact(command, timeoutMs)
    }

    /**
     * Payload bytes after the positive-response echo, for any mode:
     * mode 22 -> "62"+pid, mode 21 -> "61"+pid, mode 01 -> "41"+pid.
     */
    fun payloadFor(command: String, response: String): ByteArray? {
        val echo = when {
            command.startsWith("22") -> "62" + command.substring(2)
            command.startsWith("21") -> "61" + command.substring(2)
            command.startsWith("01") -> "41" + command.substring(2)
            else -> return null
        }
        val r = response.replace(" ", "")
        if (r.contains("NODATA") || r.contains("ERROR") || r.contains("STOPPED") || r.contains("?")) return null
        val idx = r.indexOf(echo)
        if (idx < 0) return null
        val hex = r.substring(idx + echo.length).takeWhile { it.isDigit() || it in 'A'..'F' }
        if (hex.length < 2) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
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
            transport.transact("AT SH 7DF", 1_500)
        } else {
            transport.transact("AT SH $header", 1_500)
        }
        currentHeader = header
        // BLE clones need a beat to settle after an address change; reading
        // immediately returns a shifted/empty frame.
        Thread.sleep(250)
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
