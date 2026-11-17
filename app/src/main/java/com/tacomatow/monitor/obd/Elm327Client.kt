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

        /** Negative-response code: "request received, response pending". */
        const val NRC_RESPONSE_PENDING = 0x78

        /**
         * How long to keep waiting after an ECU says "response pending".
         *
         * 7F <service> 78 is not a refusal: it is the ECU asking for more
         * time, and the manufacturer services use it liberally — the gateway
         * answers 22 F1 90 after several seconds of thinking. Toyota's own
         * 10 03 reply advertises P2* = 5000 ms (00 32 01 F4), so that is the
         * window worth granting before calling the block silent.
         */
        private const val PENDING_WINDOW_MS = 5_000

        /**
         * The adapter's own maximum response timeout, 0xFF x 4 ms ≈ 1 s.
         * A sweeps runs at 120 ms, which expires long before a pending ECU
         * answers — so the window has to be widened for the retry.
         */
        private const val PENDING_ST_UNITS = 0xFF

        /** One extra grant. A second would add five more seconds per pending block. */
        private const val MAX_PENDING_GRANTS = 1

        /** Let the ECU see its next request without the aborted one still on the wire. */
        private const val PENDING_GAP_MS = 250L
    }

    /** Which framing the gateway last negotiated. */
    @Volatile private var gatewayFraming = GatewayFraming.NONE

    /** Adapter response timeout in force, in AT ST units of 4 ms. */
    @Volatile private var stUnits = 0x32
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

    private val setupNotes = mutableListOf<String>()

    /** Session setup problems worth showing — a refused header, mostly. */
    fun setupReport(): List<String> = synchronized(setupNotes) { setupNotes.toList() }

    private fun noteSetup(line: String) = synchronized(setupNotes) {
        if (setupNotes.size < 12) setupNotes.add(line)
    }

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
            val raw = transactPending(cmd, 3_500) { transport.transact(cmd, it) }
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
                if (responsePending(raw, cmd)) {
                    // It asked for more time and the extended wait expired
                    // too: that is silence, not a refusal. Retrying the read
                    // would only repeat the whole five-second wait.
                    return PidAttempt(null, true)
                }
                val refused = negativeResponse(raw, cmd)
                if (refused != null) {
                    // Mode 01 is a functional service: an ECM answers
                    // "7F 01 11" when that request arrives at a physical
                    // address instead. Before calling it a refusal, put the
                    // header out again and ask once more.
                    if (attempt == 0) {
                        reassertHeader(def.header)
                        Thread.sleep(120)
                        return@repeat
                    }
                    return PidAttempt(null, true, refused)
                }
                if (noData) return PidAttempt(null, true)
            }
            if (attempt == 0) Thread.sleep(120)
        }
        return PidAttempt(null, false)
    }

    fun readPid(def: PidDef): Float? = readPidAttempt(def).value

    /** Adapter housekeeping rather than vehicle data. */
    fun isAdapterNoise(raw: String): Boolean {
        val t = raw.trim().uppercase()
        return t.isEmpty() || t == "OK" || t == "?" ||
            t.startsWith("SEARCHING") || t.contains("UNABLE TO CONNECT")
    }

    /**
     * A negative response to *the request we sent*, "7F <service> <code>".
     *
     * This is the single most useful distinction in OBD debugging: a refusal
     * proves the request arrived, whereas NO DATA only proves the adapter gave
     * up. But only a refusal that names our own service proves anything. With
     * headers on and a receive filter that keeps a whole module's traffic, the
     * reply buffer routinely holds other frames — and a 7F for somebody else's
     * request (another module refusing 22 while we asked 21) was being read as
     * our refusal, which is a wrong answer about the truck rather than a
     * missing one.
     *
     * The service byte is compared with the 0x80 bit masked: ISO 14229 ECUs
     * echo the request byte, while some Toyota modules set the high bit. [raw]
     * is parsed through [IsoTp] so packed and multi-frame replies count, and
     * NRC 0x78 (response pending) is not a refusal — see [responsePending].
     */
    fun negativeResponse(raw: String, request: String, gatewaySub: Int? = null): String? {
        val service = requestedService(request) ?: return null
        val msg = IsoTp.messages(raw, gatewaySub).firstOrNull { m ->
            m.size >= 3 && (m[0].toInt() and 0xFF) == 0x7F &&
                (m[1].toInt() and 0x7F) == service &&
                (m[2].toInt() and 0xFF) != NRC_RESPONSE_PENDING
        } ?: return null
        return "7F %02X %02X".format(msg[1], msg[2])
    }

    /**
     * "Request received, response pending" for our own request: 7F <service>
     * 78. The ECU is working, not refusing, and the answer is worth waiting
     * for — the manufacturer read services are where this shows up.
     */
    fun responsePending(raw: String, request: String, gatewaySub: Int? = null): Boolean {
        val service = requestedService(request) ?: return false
        return IsoTp.messages(raw, gatewaySub).any { m ->
            m.size >= 3 && (m[0].toInt() and 0xFF) == 0x7F &&
                (m[1].toInt() and 0x7F) == service &&
                (m[2].toInt() and 0xFF) == NRC_RESPONSE_PENDING
        }
    }

    /** First byte of a request, which is the service a refusal would name. */
    private fun requestedService(request: String): Int? {
        val head = request.trim().take(2)
        if (head.length != 2) return null
        return head.toIntOrNull(16)?.and(0x7F)
    }

    /**
     * One command, with "response pending" honoured as a longer wait.
     *
     * [request] is the logical command ("2114") so the refusal and pending
     * checks can recognise our service; [send] runs the actual exchange, which
     * may have to be framed differently (gateway raw framing adds a length
     * byte). Every read in the app goes through here, so a pending answer is
     * waited out once, everywhere, instead of being reported as a refusal or a
     * dead block.
     */
    private fun transactPending(
        request: String,
        timeoutMs: Int,
        gatewaySub: Int? = null,
        send: (timeoutMs: Int) -> String?,
    ): String? {
        var raw = send(timeoutMs)
        var grants = 0
        while (raw != null && grants < MAX_PENDING_GRANTS &&
            responsePending(raw, request, gatewaySub)
        ) {
            grants++
            // The adapter printed the pending frame and handed the prompt
            // back with no data, so nothing more will arrive for this
            // command: abort what it is still holding, widen its window to
            // its own maximum, and ask again with the P2* window.
            transport.transact("", 250)
            transport.transact("ATST%02X".format(PENDING_ST_UNITS), 1_500)
            Thread.sleep(PENDING_GAP_MS)
            raw = send(PENDING_WINDOW_MS) ?: raw
        }
        if (grants > 0) transport.transact("ATST%02X".format(stUnits), 1_500)
        return raw
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

    /** What an in-session bus reset achieved. */
    enum class BusRecovery {
        /** `0100` answers again: the bus is back and polling can resume. */
        RECOVERED,

        /** The adapter reset fine but nothing on the bus answers — likely key-off. */
        SILENT,

        /** The adapter did not even answer the reset. */
        ADAPTER_DEAD,

        NOT_CONNECTED,
    }

    /**
     * Recover an adapter that still answers AT commands but has stopped
     * reaching the bus, without asking the owner to unplug it.
     *
     * A warm start drops the ELM's command state and its CAN controller — the
     * software equivalent of pulling the dongle, and the same trick [connect]
     * already uses after a bad previous session. It also puts every setting
     * back to defaults, so the session is set up again here: echo, line feeds,
     * headers and the pinned protocol all return, the header cache is cleared
     * and any gateway framing is gone (so the next gateway read re-runs CEA
     * instead of trusting stale state).
     *
     * [cold] uses AT Z instead, for a clone that survived a warm start. Both
     * are followed by a real `0100` on the bus: an adapter that reset cleanly
     * is not the same as a bus that answers.
     */
    fun recoverBus(cold: Boolean = false): BusRecovery {
        if (!open) return BusRecovery.NOT_CONNECTED
        // Abort whatever the ELM is still holding; any character does it.
        transport.transact("", 300)
        val reset = transport.transact(if (cold) "ATZ" else "ATWS", 6_000)
        if (reset.isNullOrBlank()) return BusRecovery.ADAPTER_DEAD
        reinitAfterReset()
        if (baselineAnswers()) return BusRecovery.RECOVERED
        // A clone that refused SP6 during setup was left on auto; try that
        // before calling the bus dead.
        transport.transact("ATSP0", 3_000)
        protocol = "0"
        currentHeader = UNKNOWN_HEADER
        return if (baselineAnswers()) BusRecovery.RECOVERED else BusRecovery.SILENT
    }

    /** Setup lost to a warm/cold start, redone the way [connect] does it. */
    private fun reinitAfterReset() {
        transport.transact("ATE0", 1_500)
        transport.transact("ATL0", 1_500)
        transport.transact("ATS1", 1_500)
        transport.transact("ATH1", 1_500)
        transport.transact("ATSP6", 3_000)
        protocol = "6"
        // A reset restores the documented 200 ms response timeout and an
        // unknown header, so the cached values would skip the setup the next
        // read needs.
        stUnits = 0x32
        currentHeader = UNKNOWN_HEADER
        gatewayFraming = GatewayFraming.NONE
        gatewaySub = null
        Thread.sleep(250)
    }

    /** 0100 answered with 41: the bus, not just the adapter, is alive. */
    private fun baselineAnswers(): Boolean {
        val probe = transport.transact("0100", 5_000) ?: return false
        return IsoTp.messages(probe).any { m ->
            m.isNotEmpty() && (m[0].toInt() and 0xFF) == 0x41
        }
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
        return transactPending(read, timeoutMs, gatewaySub = sub and 0xFF) {
            transport.transact(cmd, it)
        }
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
        stUnits = units
        transport.transact("ATST" + "%02X".format(units), 2_000)
    }

    /** Raw hex reply for an arbitrary header/command — used by PID detection. */
    fun readRaw(header: String?, command: String, timeoutMs: Int = 3_500): String? {
        if (!open) return null
        setHeader(header)
        return transactPending(command, timeoutMs) { transport.transact(command, it) }
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
        // The adapter can swallow an AT SH: it has just aborted a command
        // ("STOPPED") or its buffer is still draining, and the acknowledge
        // never arrives. Recording the header as set in that case is how
        // every later request goes out under the old one — mode 01 sent to
        // 701 comes back as "7F 01 11", four gauges blank, and the header
        // cache insists it is already 7DF so nothing ever corrects it.
        var ok = writeHeader(header)
        if (!ok) {
            Thread.sleep(120)
            ok = writeHeader(header)
        }
        if (!ok) {
            // Say it out loud: the next request goes out under whatever
            // address the adapter still holds, and a mode 01 read there
            // answers "7F 01 11", which reads like the truck refusing.
            noteSetup(
                "header: the adapter did not accept AT SH ${header ?: "7DF"} — " +
                    "requests may go out under the previous address",
            )
        }
        currentHeader = if (ok) header else UNKNOWN_HEADER
        // BLE clones need a beat to settle after an address change; reading
        // immediately returns a shifted/empty frame.
        Thread.sleep(250)
    }

    /** True only when the adapter acknowledged the address change. */
    private fun writeHeader(header: String?): Boolean {
        val cmd = if (header == null) "AT SH 7DF" else "AT SH $header"
        val r = transport.transact(cmd, 3_000) ?: return false
        val t = r.uppercase()
        return !t.contains("?") && !t.contains("STOPPED") && !t.contains("ERROR")
    }

    /** Force the header out again, even if the cache says it is already set. */
    fun reassertHeader(header: String?) {
        currentHeader = UNKNOWN_HEADER
        setHeader(header)
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
