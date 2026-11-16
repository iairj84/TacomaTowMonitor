package com.tacomatow.monitor.obd

/**
 * ELM327 command layer. Transport-agnostic: works over Classic SPP
 * ([SppTransport]) and BLE GATT ([BleTransport]). Runs on a worker thread;
 * never on main.
 *
 * Init is deliberately TOLERANT: a quiet reply to a cosmetic command
 * (ATE0/ATS1) must not abort the whole connection, and cheap BLE clones
 * routinely need several seconds before their first answer after power-up.
 * The session only fails if the adapter never says anything at all.
 */
class Elm327Client(
    private val transport: ElmTransport,
) {
    private var currentHeader: String? = UNKNOWN_HEADER
    @Volatile private var open = false
    @Volatile private var protocol = "?"

    /** Which ELM protocol is in force ("6" = CAN 11/500, "0" = auto). */
    fun activeProtocol(): String = protocol

    companion object {
        /**
         * Sentinel for "the adapter's header is unknown". A protocol change
         * (ATSP) resets the ELM's header behind our back, so caching `null`
         * would wrongly skip the AT SH 7DF that puts it back on functional
         * broadcast addressing.
         */
        private const val UNKNOWN_HEADER = "\u0000"

        /** The Toyota gateway's request ID. Reply ID is 0x758. */
        const val GATEWAY_HEADER = "750"

        /** ISO 15765-2 sub-target for Main Body behind that gateway. */
        const val MAIN_BODY_SUB = 0x40
    }

    /** Which framing the gateway last negotiated. */
    @Volatile private var gatewayFraming = GatewayFraming.NONE
    @Volatile private var gatewaySub: Int? = null
    private val gatewayNotes = mutableListOf<String>()

    /**
     * What the gateway setup actually negotiated, for the adapter log.
     *
     * Worth recording because the failure is silent: a clone that refuses
     * CEA, or accepts it but refuses AT TA, answers nothing at all — which
     * looks exactly like a module with no settings to read.
     */
    fun gatewayReport(): List<String> = synchronized(gatewayNotes) { gatewayNotes.toList() }

    private fun noteGateway(line: String) = synchronized(gatewayNotes) {
        if (gatewayNotes.size < 10) gatewayNotes.add(line)
    }
    @Volatile private var transportOpened = false
    @Volatile private var identityOk = false

    /** False means we never even got a byte pipe — very different from a silent dongle. */
    fun didOpenTransport(): Boolean = transportOpened

    fun connect(): Boolean {
        if (!transport.open()) return false
        transportOpened = true
        open = true
        var anyAnswer = false

        // Break out of whatever the dongle was last doing. Any character
        // aborts a command in progress, and a BLE ELM left mid-session — or
        // parked in a streaming mode — answers nothing at all until it is
        // power cycled. ATWS is that power cycle done in software: a warm
        // start clears the command state without the owner unplugging it.
        repeat(3) { transport.transact("", 300) }
        val warm = transport.transact("ATWS", 4_000)
        if (!warm.isNullOrBlank()) anyAnswer = true

        // Full reset only if the warm start was refused outright. BLE clones
        // often stay silent for seconds after the notify subscription arms.
        var reset = if (warm.isNullOrBlank()) transport.transact("ATZ", 8_000) else warm
        if (reset.isNullOrBlank()) {
            Thread.sleep(600)
            reset = transport.transact("AT", 4_000)
            transport.transact("ATZ", 8_000)
        }
        if (!reset.isNullOrBlank()) anyAnswer = true

        // Cosmetic setup: never fatal if a clone ignores one of these.
        transport.transact("ATE0", 2_000)
        transport.transact("ATL0", 2_000)
        // Spaces between bytes: a first frame and its consecutive frames are
        // always printed on different lines, and only spaced output lets them
        // be joined back into one message.
        transport.transact("ATS1", 2_000)
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
        // A channel that answers with bytes which are not ELM text can produce
        // replies that look like an ECU refusing an identifier. Until the
        // adapter names itself, no such reply can be trusted as evidence about
        // the truck, so it is recorded as unverified instead of reported.
        identityOk = ident?.uppercase()?.let {
            it.contains("ELM") || it.contains("STN") || it.contains("OBD")
        } == true
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

    /** True when the adapter identified itself as an ELM327-class device. */
    fun identityVerified(): Boolean = identityOk

    /** The BLE channel this session proved — null on classic or when nothing was proven. */
    fun confirmedBleChannel(): UartChannel? =
        (transport as? BleTransport)?.confirmedChannel()

    /** Fires when the byte pipe drops on its own, so a session can be rebuilt. */
    fun onLinkLost(handler: () -> Unit) = transport.onLinkLost(handler)

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
                // The adapter prints "NO DATA" with a space; collapse it
                // before matching or unsupported PIDs are never recognised
                // and get retried on every sweep.
                val flat = raw.replace(" ", "").uppercase()
                val noData = flat.contains("NODATA") || flat.contains("?") || flat.contains("ERROR")
                // "OK", "?" or "SEARCHING..." is the adapter talking about
                // itself, not an answer from the truck. Parsing those as a
                // reply is how a desynced clone produces phantom results.
                if (isAdapterNoise(raw)) {
                    if (attempt == 0) Thread.sleep(150)
                    return@repeat
                }
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
    /** Adapter housekeeping rather than vehicle data. */
    fun isAdapterNoise(raw: String): Boolean {
        val t = raw.trim().uppercase()
        return t.isEmpty() || t == "OK" || t == "?" ||
            t.startsWith("SEARCHING") || t.contains("UNABLE TO CONNECT")
    }

    /**
     * A negative response is a *message* starting with 0x7F, parsed through
     * IsoTp so packed and multi-line replies count. The old scan for a bare
     * "7F" token missed every packed refusal and could mistake a data byte
     * for one.
     */
    fun negativeResponse(raw: String): String? {
        val msg = IsoTp.messages(raw).firstOrNull { m ->
            m.isNotEmpty() && (m[0].toInt() and 0xFF) == 0x7F
        } ?: return null
        if (msg.size < 3) return null
        return "7F %02X %02X".format(msg[1], msg[2])
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
        // Only a 0x50 message is acceptance. A silent "NO DATA" used to be
        // counted as success, which made the log claim an open session that
        // never was — and with headers on, the reply starts with the CAN ID
        // ("7E8 06 50 03 …"), so a prefix check could never succeed either.
        return r != null && IsoTp.messages(r).any { m ->
            m.isNotEmpty() && (m[0].toInt() and 0xFF) == 0x50
        }
    }

    /**
     * How gateway traffic is framed through the adapter.
     *
     * 0x750 is a gateway, not an ECU: every frame carries the downstream
     * module's sub-target as an ISO 15765-2 extended address, and replies
     * arrive on 0x758 with the same byte. Framing it by hand does not work —
     * with CAN auto-formatting on, the ELM prepends its own length byte, so
     * "40 02 21 14" leaves as "04 40 02 21 14" and the gateway reads
     * sub-target 0x04. The adapter has to insert it (AT CEA hh).
     */
    enum class GatewayFraming {
        NONE,

        /** The adapter adds the sub-target itself (AT CEA). Preferred. */
        ADAPTER_EXTENDED,

        /** No CEA on this firmware: formatting off, sub-target prefixed by hand. */
        MANUAL,
    }

    /**
     * Start routing through the gateway at [sub]. Idempotent per sub-target,
     * so a sweep pays the setup once.
     *
     * Order matters: CEA tells the adapter to insert the byte on transmit,
     * but reception is filtered by the *tester address* (AT TA, default F1),
     * so a 758/40 reply is dropped as "not for us" until TA matches. Flow
     * control is declared explicitly because multi-frame replies need an FC
     * frame that carries the same sub-target.
     */
    fun beginGateway(sub: Int): GatewayFraming {
        if (!open) return GatewayFraming.NONE
        val ae = "%02X".format(sub and 0xFF)
        gatewayNotes.clear()
        setHeader(GATEWAY_HEADER)
        val ta = transport.transact("ATTA$ae", 2_000)
        val cea = transport.transact("ATCEA$ae", 2_000)
        if (cea != null && !cea.contains("?")) {
            transport.transact("ATFCSH$GATEWAY_HEADER", 1_500)
            transport.transact("ATFCSD" + ae + "300000", 1_500)
            transport.transact("ATFCSM1", 1_500)
            gatewayFraming = GatewayFraming.ADAPTER_EXTENDED
            noteGateway("gateway $GATEWAY_HEADER/$ae: CEA accepted, adapter frames the sub-target")
        } else {
            // CEA arrived in v1.4, and plenty of clones and emulators never
            // implemented it. Fall back to raw frames: formatting off and the
            // length plus sub-target supplied here. Without flow control only
            // single-frame replies survive, which covers the LID blocks.
            transport.transact("ATCAF0", 2_000)
            gatewayFraming = GatewayFraming.MANUAL
            noteGateway(
                "gateway $GATEWAY_HEADER/$ae: AT CEA refused — raw framing, " +
                    "single-frame replies only (multi-frame blocks will look empty)",
            )
        }
        if (ta == null || ta.contains("?")) {
            noteGateway("gateway: AT TA refused — replies addressed to $ae may be filtered out")
        }
        transport.transact("ATCRA758", 2_000)
        gatewaySub = sub and 0xFF
        return gatewayFraming
    }

    /** Leave gateway framing and hand the adapter back to the poll loop. */
    fun endGateway() {
        val wasFramed = gatewaySub != null || gatewayFraming != GatewayFraming.NONE
        gatewaySub = null
        gatewayFraming = GatewayFraming.NONE
        if (!wasFramed || !open) return
        transport.transact("ATCEA", 1_500) // bare CEA = extended addressing off
        transport.transact("ATTAF1", 1_500) // default tester address
        transport.transact("ATCAF1", 1_500) // formatting back on
        transport.transact("ATFCSM0", 1_500) // automatic flow control
        transport.transact("ATAR", 1_500) // auto receive again (clears CRA)
        // The header was changed behind setHeader's cache.
        currentHeader = UNKNOWN_HEADER
    }

    /**
     * One read through the gateway: header 750, sub-target [sub]. The command
     * is the plain KWP read — the sub-target is framing, not payload.
     */
    fun readGatewayLid(sub: Int, lid: Int, timeoutMs: Int = 1_200): String? {
        if (!open) return null
        val framing = if (gatewaySub == (sub and 0xFF)) gatewayFraming else beginGateway(sub)
        if (framing == GatewayFraming.NONE) return null
        val read = "21%02X".format(lid and 0xFF)
        val cmd = if (framing == GatewayFraming.MANUAL) {
            manualFrame("%02X02%s".format(sub and 0xFF, read))
        } else {
            read
        }
        return transport.transact(cmd, timeoutMs)
    }

    /** Extended session on the sub-target itself, through the gateway. */
    fun enterGatewaySession(sub: Int): Boolean {
        if (!open) return false
        val framing = if (gatewaySub == (sub and 0xFF)) gatewayFraming else beginGateway(sub)
        if (framing == GatewayFraming.NONE) return false
        val cmd = if (framing == GatewayFraming.MANUAL) {
            manualFrame("%02X021003".format(sub and 0xFF))
        } else {
            "1003"
        }
        val r = transport.transact(cmd, 2_500)
        return r != null && IsoTp.messages(r, sub and 0xFF).any {
            it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x50
        }
    }

    /**
     * Under CAF0 nothing is formatted or padded — the bytes given are the
     * frame. Many ECUs ignore diagnostic frames short of 8 bytes, so hand
     * over a full one; costs nothing.
     */
    private fun manualFrame(hex: String): String = hex.padEnd(16, '0')

    /**
     * TesterPresent with response suppression, for long sweeps. The extended
     * session lapses after a few idle seconds, so a sweep that never says
     * hello loses it halfway through. Sent on the header the sweep is using —
     * through the gateway when [sub] is given, so the sub-target's session is
     * actually refreshed — and with no header change, so it costs no flips.
     * Suppressed (0x80) so ECUs stay silent. Best effort.
     */
    fun sendTesterPresent(header: String? = null, sub: Int? = null) {
        if (!open) return
        try {
            if (sub != null) {
                val framing =
                    if (gatewaySub == (sub and 0xFF)) gatewayFraming else beginGateway(sub)
                if (framing == GatewayFraming.NONE) return
                val cmd = if (framing == GatewayFraming.MANUAL) {
                    manualFrame("%02X023E80".format(sub and 0xFF))
                } else {
                    "3E80"
                }
                transport.transact(cmd, 600)
            } else {
                setHeader(header)
                transport.transact("3E80", 600)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Drop back to automatic protocol selection. Some clones behave
     * differently pinned (ATSP6) than in auto-search, and Torque defaults to
     * auto — worth matching when a pinned session returns nothing.
     */
    fun setAutoProtocol(): Boolean {
        transport.transact("ATSP0", 4_000)
        protocol = "0"
        currentHeader = UNKNOWN_HEADER
        // Auto-search runs asynchronously and aborts every queued command with
        // SEARCHING... meanwhile, so prove it actually settled before callers
        // treat the switch as usable.
        val probe = transport.transact("0100", 6_000)
        currentHeader = UNKNOWN_HEADER
        return probe != null && !probe.contains("SEARCHING", true) &&
            !probe.contains("UNABLE", true)
    }

    /** Pin the Tacoma's bus (CAN 11-bit / 500k). */
    fun setCanProtocol(): Boolean {
        val r = transport.transact("ATSP6", 4_000)
        protocol = "6"
        currentHeader = UNKNOWN_HEADER
        return r != null
    }

    /**
     * How long the ELM itself waits for an ECU before answering "NO DATA".
     * Units are 4 ms, sent as hex. A sweep of missing blocks pays this once
     * per probe, so shortening it is the difference between five minutes and
     * one. Null restores the documented 200 ms default.
     */
    fun setResponseTimeout(ms: Int?) {
        if (!open) return
        val units = if (ms == null) 0x32 else (ms / 4).coerceIn(0x0A, 0xFF)
        transport.transact("ATST" + "%02X".format(units), 2_000)
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
     * Multi-frame replies are reassembled; anything that is not a complete
     * message starting with the echo is not data.
     */
    fun payloadFor(command: String, response: String, gatewaySub: Int? = null): ByteArray? {
        val echo = when {
            command.startsWith("22") -> "62" + command.substring(2)
            command.startsWith("21") -> "61" + command.substring(2)
            command.startsWith("01") -> "41" + command.substring(2)
            command.startsWith("09") -> "49" + command.substring(2)
            else -> return null
        }
        val echoBytes = echo.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val msg = IsoTp.firstStartingWith(response, echoBytes, gatewaySub) ?: return null
        if (msg.size <= echoBytes.size) return null
        return msg.copyOfRange(echoBytes.size, msg.size)
    }

    /**
     * Trouble codes for one reporting mode: 03 stored, 07 pending, 0A permanent.
     * Empty when the bus says nothing. Read-only — clearing (mode 04) has no
     * function here on purpose.
     */
    fun readDtcs(mode: String, timeoutMs: Int = 5_000): List<String> {
        if (!open) return emptyList()
        val raw = try { readRaw(null, mode, timeoutMs) } catch (_: Exception) { null }
        return DtcDecode.decode(mode, raw)
    }

    /** MIL on/off plus the confirmed-code count from mode 01 PID 01. */
    fun readMil(timeoutMs: Int = 4_000): Pair<Boolean, Int>? {
        if (!open) return null
        val raw = try { readRaw(null, "0101", timeoutMs) } catch (_: Exception) { null }
            ?: return null
        return DtcDecode.mil(payloadFor("0101", raw))
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
     * Returns data bytes after the PID echo, reassembled across frames.
     */
    private fun parseResponse(cmd: String, resp: String): ByteArray? = payloadFor(cmd, resp)

    fun close() {
        open = false
        try { transport.close() } catch (_: Exception) {}
        currentHeader = null
    }
}
