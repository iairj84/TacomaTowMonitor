package com.tacomatow.monitor.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tacomatow.monitor.obd.write.WritePolicy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sin
import kotlin.random.Random

data class GaugeReading(
    val pidId: String,
    val label: String,
    val valueF: Float?,
    val unit: String,
    /** null = ok, "WARN", "CRIT", "NO DATA" */
    val status: String? = null,
)

data class ObdUiState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val demoMode: Boolean = false,
    val readings: Map<String, GaugeReading> = emptyMap(),
    val voltage: Float? = null,
    val lastUpdateMs: Long = 0L,
    val error: String? = null,
    val logging: Boolean = false,
    /** Adapter setup trail, newest last — for the in-app log. */
    val diagnostics: List<String> = emptyList(),
    /** True once a session got far enough to have an adapter trail worth showing. */
    val triedConnect: Boolean = false,
    /**
     * PIDs the ECU explicitly refused, e.g. "Trans Pan — 7F 22 31". A refusal
     * is evidence (the truck has no such identifier), so it is shown rather
     * than silently leaving an empty gauge card.
     */
    val refusals: List<String> = emptyList(),
    /**
     * Connected to the adapter, but nothing on the powertrain bus answered.
     * The adapter runs off the battery, so it links long before the key is on;
     * blank gauges here mean the truck is asleep, not that the app is broken.
     */
    val powertrainAsleep: Boolean = false,
)

/**
 * Owns the ELM327 socket + polling loop. Single writer for [state].
 * Polled sequentially (Tacoma 701 header flips per PID) at ~1 PID / 150ms
 * so a full 4-gauge tow sweep refreshes about every 1s on the phone.
 * The car screen throttles separately to ~5s (AA host rules).
 */
class ObdRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(ObdUiState())
    val state: StateFlow<ObdUiState> = _state.asStateFlow()

    private var client: Elm327Client? = null
    private var appContext: android.content.Context? = null
    private var lastMac: String = ""
    /** Set at connect: the adapter named itself as an ELM327-class device. */
    @Volatile private var identityOk = true
    private var pollJob: Job? = null
    /** Detected transmission definitions — replaced at connect time. */
    private var panDef: PidDef = TacomaPids.TRANS_PAN
    private var convDef: PidDef = TacomaPids.TRANS_CONVERTER
    private val detectLog = mutableListOf<String>()

    /**
     * Gentle session: one probe, no extended-session request, no protocol
     * retry, slower polling. Aggressive manufacturer probing can leave a
     * Toyota diagnostic gateway refusing traffic until the bus sleeps, so
     * this keeps a session cheap for trucks that have already been kicked
     * around by earlier attempts.
     */
    @Volatile private var gentle = false

    fun setGentle(value: Boolean) {
        gentle = value
    }

    fun isGentle(): Boolean = gentle

    /**
     * Driver-chosen extras. Transmission pan/converter are always polled (the
     * point of the app); everything else — coolant, oil, RPM, speed, adapter
     * voltage — can be switched off in Settings so a slow dongle spends its
     * time on what matters.
     */
    @Volatile private var enabled: Set<String> = setOf("coolant", "oil", "rpm", "speed", "voltage")

    /** True once a real transmission candidate answered this session. */
    @Volatile private var transDetected = false

    fun setMonitors(ids: Set<String>) {
        enabled = ids.toSet()
    }

    /**
     * The channel this adapter proved on a previous session, so the hunt
     * happens once per dongle. Returned to the transport as its first
     * candidate; a proven channel answers immediately, an absent one is
     * skipped without costing anything.
     */
    private fun rememberedChannel(mac: String): UartChannel? {
        val ctx = appContext ?: return null
        val raw = ctx.getSharedPreferences("tacoma", android.content.Context.MODE_PRIVATE)
            .getString("ble_chan_$mac", null) ?: return null
        return try {
            val (s, w, n) = raw.split("|")
            UartChannel(UUID.fromString(s), UUID.fromString(w), UUID.fromString(n))
        } catch (_: Exception) {
            null
        }
    }

    /** The stored channel for an adapter, if any — for display and forgetting. */
    fun rememberedChannelFor(mac: String): UartChannel? = rememberedChannel(mac)

    /** Drop the stored channel so the next session hunts from scratch. */
    fun forgetChannel(mac: String) {
        appContext?.getSharedPreferences("tacoma", android.content.Context.MODE_PRIVATE)
            ?.edit()?.remove("ble_chan_$mac")?.apply()
    }

    /** Written only when a channel identified itself — guesses never overwrite. */
    private fun saveChannel(mac: String, ch: UartChannel) {
        appContext?.getSharedPreferences("tacoma", android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("ble_chan_$mac", "${ch.service}|${ch.write}|${ch.notify}")
            ?.apply()
    }
    /**
     * True while another screen owns the adapter. The poll loop must not
     * restart behind it: one serial channel shared by two callers interleaves
     * replies and roughly doubles every scan.
     */
    @Volatile private var pollingPaused = false

    fun setPollingPaused(paused: Boolean) {
        pollingPaused = paused
        if (paused) {
            stopPoll()
        } else {
            val c = client
            val s = _state.value
            if (s.connected && !s.demoMode && c != null && pollJob == null) startPoll(c)
        }
    }

    /**
     * Take sole use of the channel: cancel gauge polling and WAIT for it, so a
     * read already in flight can't land in the middle of a sweep.
     */
    private suspend fun takeAdapter() {
        val job = pollJob
        pollJob = null
        job?.cancelAndJoin()
    }

    private var oilMisses = 0
    private var logFile: File? = null
    private var logDir: File? = null

    fun attachLogDir(dir: File) {
        logDir = dir
    }

    fun startDemo() {
        stopPoll()
        _state.update {
            it.copy(
                connected = true,
                demoMode = true,
                deviceName = "Demo — no adapter",
                error = null,
                refusals = emptyList(),
            )
        }
        pollJob = scope.launch {
            var t = 0.0
            while (isActive) {
                t += 0.12
                // Simulate a tow climb: pan ramps 185 -> 225 with thermostat wobble.
                val pan = 195f + (12f * sin(t * 0.25).toFloat() + t.toFloat() % 30f) + Random.nextFloat() * 2f
                val conv = pan + 8f + Random.nextFloat() * 6f
                val coolant = 198f + 4f * sin(t * 0.4).toFloat()
                val rpm = 2100f + 400f * sin(t * 0.7).toFloat()
                publishDemo(pan, conv, coolant, rpm)
                delay(1_000)
            }
        }
    }

    private fun publishDemo(pan: Float, conv: Float, coolant: Float, rpm: Float) {
        val e = enabled
        val all = mapOf(
            "trans_pan" to gauge(TacomaPids.TRANS_PAN, pan),
            "trans_conv" to gauge(TacomaPids.TRANS_CONVERTER, conv),
            "coolant" to gauge(TacomaPids.COOLANT, coolant),
            "oil" to GaugeReading("oil", "Engine Oil", null, "°F", "NO DATA"),
            "rpm" to GaugeReading("rpm", "RPM", rpm, "rpm"),
        )
        _state.update {
            it.copy(
                readings = all.filterKeys { k -> k == "trans_pan" || k == "trans_conv" || e.contains(k) },
                voltage = if (e.contains("voltage")) 13.8f else null,
                powertrainAsleep = false,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Connect to any picked dongle, regardless of which radio it uses.
     * Classic RFCOMM is tried first (fast, and the common case), then BLE.
     * SPP dies immediately on BLE-only hardware, so this fallback is cheap —
     * and it means the user never has to know which list to pick from.
     */
    fun connectAny(
        context: android.content.Context,
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        onDone: (Boolean, String?) -> Unit = { _, _ -> },
    ) {
        appContext = context.applicationContext
        lastMac = device.address
        beginSession(
            label = device.name ?: device.address,
            transports = listOf(
                "classic" to { SppTransport(adapter, device) as ElmTransport },
                "ble" to {
                    BleTransport(
                        context.applicationContext,
                        device,
                        rememberedChannel(device.address),
                    ) as ElmTransport
                },
            ),
            onDone = onDone,
        )
    }

    private fun beginSession(
        label: String,
        transports: List<Pair<String, () -> ElmTransport>>,
        onDone: (Boolean, String?) -> Unit = { _, _ -> },
    ) {
        stopPoll()
        // A previous session's client must not survive: an auto-reconnect in
        // flight plus a fresh pick left two sessions fighting over one dongle.
        try { client?.close() } catch (_: Exception) { }
        client = null
        // A new session may be a different truck, or the same one after a
        // battery pull. Reusing the last detection's definitions would poll
        // DIDs this vehicle may not publish, so detection runs again.
        resetDetection()
        oilMisses = 0
        // Remembered so a dropped link can be rebuilt without the driver
        // touching anything — including after a stop, which is when a BLE
        // dongle is most likely to come back holding a dead session.
        lastSession = { beginSession(label, transports, onDone) }
        autoReconnect = true
        _state.update {
            it.copy(error = null, demoMode = false, deviceName = label, diagnostics = emptyList())
        }
        scope.launch {
            val trail = mutableListOf<String>()
            var failed: Elm327Client? = null
            var winner: Elm327Client? = null
            var winnerKind = ""

            for ((kind, make) in transports) {
                val c = Elm327Client(make())
                val ok = try {
                    withContext(Dispatchers.IO) { c.connect() }
                } catch (e: SecurityException) {
                    withContext(Dispatchers.Main) { onDone(false, "Bluetooth permission denied") }
                    _state.update { it.copy(error = "Bluetooth permission denied") }
                    return@launch
                } catch (e: Exception) {
                    false
                }
                trail += c.transcript()
                if (ok) {
                    winner = c
                    winnerKind = kind
                    break
                }
                c.close()
                failed = c
                trail += "$kind: not usable, trying next transport"
            }

            val won = winner
            if (won == null) {
                val stage = failed?.transportStage().orEmpty()
                val opened = failed?.didOpenTransport() == true
                val msg = when {
                    !opened ->
                        "Couldn't open a Bluetooth channel to ${label} over classic or BLE. If Torque was open, force-close it — these dongles accept one connection at a time."
                    stage == "no-answer-on-probe" ->
                        "Paired over BLE but the dongle stayed silent on every data channel. Force-close Torque and any other OBD app, then retry."
                    stage == "link-timeout" ->
                        "Couldn't establish the BLE link. Move the phone closer and make sure nothing else is connected to the dongle."
                    else ->
                        "Reached the adapter, but it never answered AT commands. Turn the ignition ON so it can reply, then retry."
                }
                _state.update {
                    it.copy(
                        connected = false,
                        error = msg,
                        triedConnect = true,
                        diagnostics = trail,
                    )
                }
                withContext(Dispatchers.Main) { onDone(false, "ELM init failed") }
                return@launch
            }

            won.onLinkLost { scheduleReconnect() }
            identityOk = won.identityVerified()
            won.confirmedBleChannel()?.let { saveChannel(lastMac, it) }
            client = won
            _state.update {
                it.copy(
                    connected = true,
                    error = null,
                    deviceName = "$label (${if (winnerKind == "ble") "BLE" else "classic"})",
                    triedConnect = true,
                    diagnostics = trail,
                    // Fresh session, fresh evidence.
                    refusals = emptyList(),
                )
            }
            withContext(Dispatchers.Main) { onDone(true, null) }
            startPoll(won)
        }
    }

    /**
     * Ask the truck which ATF-temperature request it actually answers.
     * Sends every candidate once and keeps the first that returns a
     * believable temperature, so a wrong header degrades to the next guess
     * instead of a permanently blank gauge.
     */
    /** One candidate probe, logging the reply. Null when nothing believable came back. */
    private suspend fun probeTransCandidate(
        c: Elm327Client,
        cand: TacomaPids.TransCandidate,
        id: String,
        label: String,
        warnF: Float,
        critF: Float,
    ): PidDef? {
        val cmd = cand.mode + cand.pid
        val raw = withContext(Dispatchers.IO) {
            try { c.readRaw(cand.header, cmd, 3_000) } catch (_: Exception) { null }
        }
        val bytes = raw?.let { c.payloadFor(cmd, it) }
        val f = bytes?.let { TacomaPids.decodeTrans(cand.kind, it) }
        detectLog.add(
            "${cand.label}: ${raw?.take(40) ?: "<no reply>"} -> " +
                (f?.let { "%.0f°F".format(it) } ?: "-"),
        )
        return if (f != null && f > 20f && f < 400f) {
            detectLog.add("using ${cand.label}")
            TacomaPids.defFor(cand, id, label, warnF, critF)
        } else {
            null
        }
    }

    private suspend fun detectTransmission(c: Elm327Client) {
        detectLog.clear()
        transDetected = false
        panDef = TacomaPids.TRANS_PAN
        convDef = TacomaPids.TRANS_CONVERTER
        detectLog.add("protocol: ATSP${c.activeProtocol()}")

        if (gentle) {
            detectLog.add("gentle: single probe, no 10 03, no protocol retry")
            val foundPan = probeTransCandidate(
                c, TacomaPids.TRANS_CANDIDATES.first(), "trans_pan", "Trans Pan", 220f, 240f,
            )
            if (foundPan != null) {
                panDef = foundPan
                transDetected = true
                probeTransCandidate(
                    c, TacomaPids.CONVERTER_CANDIDATES.first(), "trans_conv", "T-Conv Out", 230f, 250f,
                )?.let { convDef = it }
            }
            _state.update { it.copy(diagnostics = (it.diagnostics + detectLog).takeLast(400)) }
            return
        }

        // Manufacturer reads are frequently refused in the default session.
        val sessionOk = withContext(Dispatchers.IO) {
            try { c.enterExtendedSession() } catch (_: Exception) { false }
        }
        detectLog.add(if (sessionOk) "session: 10 03 accepted" else "session: 10 03 refused")

        var panFound = false
        for (cand in TacomaPids.TRANS_CANDIDATES) {
            val cmd = cand.mode + cand.pid
            val raw = withContext(Dispatchers.IO) {
                try { c.readRaw(cand.header, cmd) } catch (_: Exception) { null }
            }
            val bytes = raw?.let { c.payloadFor(cmd, it) }
            val f = bytes?.let { TacomaPids.decodeTrans(cand.kind, it) }
            detectLog.add(
                "pan ${cand.label}: ${raw?.take(40) ?: "<no reply>"} -> " +
                    (f?.let { "%.0f°F".format(it) } ?: "-")
            )
            if (f != null && f > 20f && f < 400f) {
                panDef = TacomaPids.defFor(cand, "trans_pan", "Trans Pan", 220f, 240f)
                detectLog.add("pan: using ${cand.label}")
                panFound = true
                break
            }
            delay(120)
        }

        // Nothing answered on the pinned CAN protocol. Torque defaults to
        // auto-search, and cheap clones sometimes only behave there, so give
        // that a shot before declaring the truck unsupported.
        if (!panFound) {
            detectLog.add("retry: switching to auto protocol (ATSP0)")
            val switched = withContext(Dispatchers.IO) {
                try { c.setAutoProtocol() } catch (_: Exception) { false }
            }
            if (switched) {
                for (cand in listOf(
                    TacomaPids.TransCandidate(null, "22", "1627", "1627", "22/1627@functional(auto)"),
                    TacomaPids.TransCandidate("701", "22", "1627", "1627", "22/1627@701(auto)"),
                )) {
                    val cmd = cand.mode + cand.pid
                    val raw = withContext(Dispatchers.IO) {
                        try { c.readRaw(cand.header, cmd) } catch (_: Exception) { null }
                    }
                    val bytes = raw?.let { c.payloadFor(cmd, it) }
                    val f = bytes?.let { TacomaPids.decodeTrans(cand.kind, it) }
                    detectLog.add(
                        "pan ${cand.label}: ${raw?.take(40) ?: "<no reply>"} -> " +
                            (f?.let { "%.0f°F".format(it) } ?: "-")
                    )
                    if (f != null && f > 20f && f < 400f) {
                        panDef = TacomaPids.defFor(cand, "trans_pan", "Trans Pan", 220f, 240f)
                        detectLog.add("pan: using ${cand.label}")
                        panFound = true
                        break
                    }
                    delay(120)
                }
            } else {
                detectLog.add("auto search never settled (SEARCHING...) — restoring ATSP6")
            }
            // Never leave the adapter in auto-search: every later command would
            // be answered with SEARCHING... and the dashboard would look dead.
            withContext(Dispatchers.IO) {
                try { c.setCanProtocol() } catch (_: Exception) { }
            }
            detectLog.add("protocol: ATSP6 restored")
        }

        for (cand in TacomaPids.CONVERTER_CANDIDATES) {
            val cmd = cand.mode + cand.pid
            val raw = withContext(Dispatchers.IO) {
                try { c.readRaw(cand.header, cmd) } catch (_: Exception) { null }
            }
            val bytes = raw?.let { c.payloadFor(cmd, it) }
            val f = bytes?.let { TacomaPids.decodeTrans(cand.kind, it) }
            detectLog.add(
                "conv ${cand.label}: ${raw?.take(40) ?: "<no reply>"} -> " +
                    (f?.let { "%.0f°F".format(it) } ?: "-")
            )
            if (f != null && f > 20f && f < 400f) {
                convDef = TacomaPids.defFor(cand, "trans_conv", "T-Conv Out", 230f, 250f)
                detectLog.add("conv: using ${cand.label}")
                break
            }
            delay(120)
        }
        transDetected = panFound
        _state.update { it.copy(diagnostics = (it.diagnostics + detectLog).takeLast(400)) }
    }

    /**
     * Cheap re-probe for the transmission DIDs.
     *
     * The adapter is powered from the OBD connector, so a session can open
     * while the truck's computers are still asleep — connect at launch with the
     * key off and the first sweep finds nothing. Detection used to run exactly
     * once, so the gauges stayed blank for the whole drive even after the key
     * went on. Two probes, retried occasionally, is the whole fix.
     */
    private suspend fun redetectTransmission(c: Elm327Client) {
        val pan = probeTransCandidate(
            c, TacomaPids.TRANS_CANDIDATES.first(), "trans_pan", "Trans Pan", 220f, 240f,
        ) ?: return
        panDef = pan
        transDetected = true
        probeTransCandidate(
            c, TacomaPids.CONVERTER_CANDIDATES.first(), "trans_conv", "T-Conv Out", 230f, 250f,
        )?.let { convDef = it }
        _state.update {
            it.copy(
                                    diagnostics = (it.diagnostics +
                        "late detect: truck woke up, transmission PIDs active").takeLast(400),
                powertrainAsleep = false,
            )
        }
    }

    /**
     * One-shot capability check: identity, protocol, baseline OBD, session
     * control, a transmission read and a body-ECU read — then a verdict.
     */
    suspend fun runAdapterCheck(): AdapterReport {
        val c = client ?: return AdapterReport.notConnected()
        takeAdapter()
        try {
            suspend fun ask(header: String?, cmd: String, ms: Int = 4_000): String? =
                runBlockingProbe(c, header, cmd, ms)
            val identity = ask(null, "ATI", 2_500)
            val description = ask(null, "AT@1", 2_500)
            val protocol = ask(null, "ATDPN", 2_500)
            val baseline = ask(null, "0100", 4_000)
            val session = ask(null, "1003", 2_500)
            val trans = ask("701", "221627", 4_000)
            // Gateway-routed: bare "21 14" to 750 names no sub-target and is
            // never answered. Ask Main Body at 0x40, with the adapter framing
            // the sub-target itself.
            val body = withContext(Dispatchers.IO) {
                try {
                    c.readGatewayLid(Elm327Client.MAIN_BODY_SUB, 0x14, 3_000)
                } catch (_: Exception) {
                    null
                } finally {
                    try { c.endGateway() } catch (_: Exception) { }
                }
            }
            // Ask the baseline again AFTER the manufacturer probes. Some clones
            // wedge on mode 21/22 and stop answering anything for a few seconds,
            // which made this check blame the connection. Comparing before and
            // after proves it.
            val baselineAfter = ask(null, "0100", 4_000)
            val firmware = AdapterFirmware.classify(identity, description)
            val report = AdapterCheck.evaluate(
                identity, protocol, baseline, session, trans, body, baselineAfter,
            ).copy(firmware = firmware)
            // How the gateway probe was framed, when it needed framing: a
            // clone that refuses CEA or AT TA answers nothing, and the verdict
            // above would blame the truck for it.
            val framingNotes = c.gatewayReport()
            return if (framingNotes.isEmpty()) report else report.copy(steps = report.steps + framingNotes)
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    private suspend fun runBlockingProbe(
        c: Elm327Client,
        header: String?,
        command: String,
        timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            c.readRaw(header?.takeIf { it.isNotBlank() }, command, timeoutMs)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Walks the bounded mode 22 set at the transmission ECU and classifies each
     * identifier: data, refused (7F) or silence. Read-only.
     */
    suspend fun sweepDids(onProgress: (done: Int, total: Int, did: String) -> Unit): List<DidProbe> {
        val c = client ?: return emptyList()
        takeAdapter()
        val dids = DidSweep.dids()
        return try {
            withContext(Dispatchers.IO) {
                try { c.enterExtendedSession() } catch (_: Exception) { }
                var lastKeepAlive = System.currentTimeMillis()
                val out = mutableListOf<DidProbe>()
                dids.forEachIndexed { index, did ->
                    if (System.currentTimeMillis() - lastKeepAlive > 4_000) {
                        try { c.sendTesterPresent() } catch (_: Exception) { }
                        lastKeepAlive = System.currentTimeMillis()
                    }
                val cmd = "22$did"
                val raw = try {
                    c.readRaw(DidSweep.HEADER, cmd, 1_200)
                } catch (_: Exception) {
                    null
                }
                val bytes = raw?.let { c.payloadFor(cmd, it) }
                val refusal = raw?.let { c.negativeResponse(it) }
                val verdict = when {
                    bytes != null -> DidVerdict.DATA
                    refusal != null -> DidVerdict.REFUSED
                    else -> DidVerdict.SILENT
                }
                out += DidProbe(
                    did = did,
                    raw = raw,
                    verdict = verdict,
                    payloadHex = bytes?.let { CustomizeExplorer.hex(it) },
                    refusal = refusal,
                )
                    withContext(Dispatchers.Main) { onProgress(index + 1, dids.size, did) }
                    delay(60)
                }
                out
            }
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    /**
     * Raw command for the diagnostics console. Takes the adapter exclusively
     * (a probe mid-poll would go out under the wrong header) and refuses
     * anything that writes to the truck — mode 04, 2E/2F/31, 3B and friends
     * never go out, including from here. See [WritePolicy.consoleRefusal].
     */
    suspend fun sendRaw(header: String?, command: String): String? {
        val c = client ?: return null
        WritePolicy.consoleRefusal(header, command)?.let { return it }
        takeAdapter()
        return try {
            withContext(Dispatchers.IO) {
                try {
                    c.readRaw(header?.takeIf { it.isNotBlank() }, command, 5_000)
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    /** Adapter identity, current protocol and bus voltage. */
    suspend fun adapterIdentity(): List<Pair<String, String?>> {
        val c = client ?: return emptyList()
        takeAdapter()
        return try {
            listOf(
                "Adapter" to withContext(Dispatchers.IO) { try { c.readRaw(null, "ATI", 3_000) } catch (_: Exception) { null } },
                "Protocol" to withContext(Dispatchers.IO) { try { c.readRaw(null, "ATDPN", 3_000) } catch (_: Exception) { null } },
                "Voltage" to withContext(Dispatchers.IO) { try { c.readRaw(null, "ATRV", 3_000) } catch (_: Exception) { null } },
            )
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    /**
     * Check-engine scan: MIL state plus stored, pending and permanent codes.
     * Takes the adapter for the few seconds this needs, then resumes polling.
     * Read-only — there is deliberately no clear function anywhere in the app.
     */
    suspend fun readTroubleCodes(): DtcReport {
        val c = client ?: return DtcReport(null, null, emptyList(), emptyList(), emptyList(), "Not connected.")
        takeAdapter()
        return try {
            withContext(Dispatchers.IO) {
                val mil = try { c.readMil() } catch (_: Exception) { null }
                DtcReport(
                    milOn = mil?.first,
                    milCount = mil?.second,
                    confirmed = try { c.readDtcs("03") } catch (_: Exception) { emptyList() },
                    pending = try { c.readDtcs("07") } catch (_: Exception) { emptyList() },
                    permanent = try { c.readDtcs("0A") } catch (_: Exception) { emptyList() },
                )
            }
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    /**
     * Best-effort calibration identifier (mode 09 PID 04).
     *
     * Stamped on every recorded mapping so an import can be checked against
     * this truck. Returns null when nothing publishes CALIDs — several Toyota
     * body ECUs only answer this on the powertrain ECU.
     */
    suspend fun readCalibrationId(): String? {
        val c = client ?: return null
        takeAdapter()
        return try {
            withContext(Dispatchers.IO) {
                val raw = try { c.readRaw(null, "0904", 3_000) } catch (_: Exception) { null }
                    ?: return@withContext null
                val payload = c.payloadFor("0904", raw) ?: return@withContext null
                if (payload.size < 2) return@withContext null
                // [calid count][16 ASCII bytes per CALID]
                val text = payload.drop(1)
                    .map { (it.toInt() and 0xFF).toChar() }
                    .filter { it.code in 32..126 }
                    .joinToString("")
                text.take(16).ifBlank { null }
            }
        } finally {
            if (_state.value.connected) startPoll(c)
        }
    }

    /**
     * Read-only sweep of Toyota customize local identifiers.
     * Pauses gauge polling while it runs so replies can't interleave, then
     * restarts it. Never writes to any ECU.
     */
    suspend fun sweepCustomize(
        known: Map<String, Set<Int>>? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<LidReading> {
        val c = client ?: return emptyList()
        takeAdapter()
        // known != null means a fast re-scan: only blocks that have answered
        // before, and a shorter timeout because we expect a reply. A full sweep
        // is still the only way to notice a block that has just started
        // answering, so it stays available.
        val plan = CustomizeExplorer.TARGETS.flatMap { target ->
            val lids = known?.get(target.header)
            when {
                known == null -> CustomizeExplorer.LID_RANGE.map { target to it }
                lids.isNullOrEmpty() -> emptyList()
                else -> lids.sorted().map { target to it }
            }
        }
        val total = plan.size
        // A full sweep is 252 probes and most of them are blocks that do not
        // exist. The expensive part was never the bus: it was waiting out the
        // ELM's 200 ms response window on every silent block. Shorten it for
        // the duration of the sweep and restore it afterwards.
        val timeout = if (known == null) 900 else 700
        val pacing = if (known == null) 40L else 25L
        val out = mutableListOf<LidReading>()
        try {
            withContext(Dispatchers.IO) {
                try { c.setResponseTimeout(120) } catch (_: Exception) { }
            }
            // Try to open the extended session so body ECUs answer at all.
            withContext(Dispatchers.IO) {
                try { c.enterExtendedSession() } catch (_: Exception) { false }
            }
            onProgress(0, total)
            val sessionedSubs = mutableSetOf<String>()
            var lastKeepAlive = System.currentTimeMillis()
            for ((target, lid) in plan) {
                // The gateway needs a session on the sub-target itself, once
                // per sweep — best effort, and silent either way.
                if (target.sub != null && sessionedSubs.add(target.header + "/" + target.sub)) {
                    withContext(Dispatchers.IO) {
                        try {
                            c.enterGatewaySession(target.sub)
                        } catch (_: Exception) {
                        }
                    }
                }
                if (System.currentTimeMillis() - lastKeepAlive > 4_000) {
                    // Keep the session alive on the module being swept: a
                    // broadcast ping would not refresh a 750/40 session, and
                    // the header flip it caused cost two 250 ms waits.
                    withContext(Dispatchers.IO) {
                        try {
                            c.sendTesterPresent(target.header, target.sub)
                        } catch (_: Exception) {
                        }
                    }
                    lastKeepAlive = System.currentTimeMillis()
                }
                // Leaving the gateway must drop its framing: with extended
                // addressing still on, every later 7C0 request would carry a
                // 0x40 that module never asked for, and its replies would be
                // filtered out. A no-op when nothing is framed.
                if (target.sub == null) {
                    withContext(Dispatchers.IO) {
                        try { c.endGateway() } catch (_: Exception) { }
                    }
                }
                // Gateway reads are framed by the adapter (AT CEA), so the
                // command stays a plain KWP read. Prefixing the sub-target by
                // hand made the ELM prepend its own length byte, and the
                // gateway saw sub-target 0x04.
                val readCommand = CustomizeExplorer.readCommand(lid)
                val raw = withContext(Dispatchers.IO) {
                    try {
                        if (target.sub != null) {
                            c.readGatewayLid(target.sub, lid, timeout)
                        } else {
                            c.readRaw(target.header, readCommand, timeout)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val payload = raw?.let { c.payloadFor(readCommand, it, target.sub) }
                out += LidReading(
                    header = target.header,
                    ecuLabel = target.label,
                    lid = lid,
                    raw = raw,
                    payloadHex = CustomizeExplorer.hex(payload),
                )
                onProgress(out.size, total)
                delay(pacing)
            }
        } finally {
            // Record what the gateway negotiated: a clone that refuses CEA
            // or AT TA answers nothing, which reads as "no settings here".
            val framingNotes = c.gatewayReport()
            if (framingNotes.isNotEmpty()) {
                _state.update {
                    it.copy(diagnostics = (it.diagnostics + framingNotes).takeLast(400))
                }
            }
            // NonCancellable: leaving the screen cancels this coroutine, and a
            // plain withContext would throw instead of running — leaving the
            // adapter on CAF0/CEA with a 758 receive filter, which breaks every
            // later request with no trace of why.
            withContext(NonCancellable + Dispatchers.IO) {
                // Hand the adapter back before the poll loop resumes: gateway
                // framing changes the header, formatting and flow control.
                try { c.endGateway() } catch (_: Exception) { }
                try { c.setResponseTimeout(null) } catch (_: Exception) { }
            }
            if (_state.value.connected) startPoll(c)
        }
        return out
    }

    /** Forget the detected transmission DIDs — they belong to one session. */
    private fun resetDetection() {
        transDetected = false
        panDef = TacomaPids.TRANS_PAN
        convDef = TacomaPids.TRANS_CONVERTER
        detectLog.clear()
    }

    private fun startPoll(c: Elm327Client) {
        if (pollingPaused) return
        pollJob?.cancel()
        pollJob = scope.launch {
            // Detection runs only until it succeeds: every code scan or sweep
            // used to resend 10 03 and re-probe all candidates, and the log
            // grew without bound. A truck that wakes up after connect is
            // covered by the 25 s re-probe instead.
            if (!transDetected) detectTransmission(c)
            // BLE clones lose the odd reply. A single miss must never wipe a
            // gauge the driver is watching, so keep the last good value and
            // mark it HOLD until it goes properly stale.
            val lastGood = mutableMapOf<String, Pair<Float, Long>>()
            val misses = mutableMapOf<String, Int>()
            // PIDs the vehicle flatly doesn't publish are parked for a while.
            // Without this, three dead PIDs cost ~20s per sweep and starve the
            // ones that do work (speed and coolant went blank because of it).
            val parkedUntil = mutableMapOf<String, Long>()
            val refusalMap = mutableMapOf<String, String>()
            var lastRedetect = System.currentTimeMillis()
            var asleepNoted = false
            var notedSetup = 0
            while (isActive && c.isConnected()) {
                // Nothing on the powertrain bus? The truck is probably asleep,
                // which is what a session opened before the key went on looks
                // like. Keep asking, politely, so starting the truck recovers
                // on its own.
                if (!transDetected && System.currentTimeMillis() - lastRedetect > 25_000) {
                    lastRedetect = System.currentTimeMillis()
                    try { redetectTransmission(c) } catch (_: Exception) {}
                }
                val out = mutableMapOf<String, GaugeReading>()
                // Transmission definitions are whatever detection found. The
                // extras follow the driver's Settings; trans always polls.
                val enabledNow = enabled
                val pollList = listOf(panDef, convDef) + listOf(
                    TacomaPids.COOLANT, TacomaPids.OIL, TacomaPids.RPM, TacomaPids.SPEED,
                ).filter { enabledNow.contains(it.id) }
                for (def in pollList) {
                    if (!isActive) break
                    val now0 = System.currentTimeMillis()
                    if ((parkedUntil[def.id] ?: 0L) > now0) continue
                    val attempt = withContext(Dispatchers.IO) {
                        try {
                            c.readPidAttempt(def)
                        } catch (_: Exception) {
                            Elm327Client.PidAttempt(null, false)
                        }
                    }
                    val v = attempt.value
                    attempt.refusal?.let { neg ->
                        if (!identityOk) {
                            // The adapter never said it was an ELM327, so these
                            // frames are not evidence about the truck. Back off
                            // like a miss, but never tell the driver the ECU
                            // refused anything.
                            val n = (misses[def.id] ?: 0) + 1
                            misses[def.id] = n
                            if (n >= 2) parkedUntil[def.id] = now0 + 120_000
                        } else {
                            val label = if (def.id == "trans_pan") "Trans Pan"
                            else if (def.id == "trans_conv") "T-Conv Out"
                            else def.label
                            refusalMap[def.id] = "$label — $neg"
                            // A refusal is usually a property of the calibration,
                            // so stop spending time on it — but not for mode 01,
                            // where "7F 01 11" means the request reached the
                            // wrong address rather than that the truck lacks the
                            // PID. Parking that for ten minutes blanks four
                            // gauges over an adapter hiccup.
                            if (def.mode != "01") parkedUntil[def.id] = now0 + 600_000
                        }
                    }
                    if (v == null && attempt.definitiveMiss) {
                        val n = (misses[def.id] ?: 0) + 1
                        misses[def.id] = n
                        if (n >= 2) parkedUntil[def.id] = now0 + 120_000
                    } else if (v != null) {
                        misses[def.id] = 0
                        parkedUntil.remove(def.id)
                        // The PID answered, so whatever refusal was shown is
                        // no longer true. Without this the card keeps listing
                        // a gauge that has been reading fine for minutes.
                        refusalMap.remove(def.id)
                    }
                    val now = System.currentTimeMillis()
                    if (v != null) lastGood[def.id] = v to now
                    // Engine oil temp is simply absent on most 2GR-FKS ECUs;
                    // stop showing a dead card once that is established.
                    if (def.id == "oil") {
                        if (v == null) oilMisses++ else oilMisses = 0
                        if (oilMisses >= 2) continue
                    }
                    out[def.id] = readingFor(def, v, lastGood[def.id], now)
                    delay(if (gentle) 350 else 150) // BLE needs a beat between header flips
                }
                val volts = if (enabledNow.contains("voltage")) withContext(Dispatchers.IO) {
                    try { c.readVoltage() } catch (_: Exception) { null }
                } else null
                val anyPower = out.values.any { it.valueF != null }
                if (!transDetected && !anyPower && !asleepNoted) {
                    asleepNoted = true
                    _state.update { s ->
                        s.copy(
                            diagnostics = s.diagnostics +
                                "bus: no reply on any powertrain address — ignition off or ECUs asleep",
                        )
                    }
                }
                if (anyPower) asleepNoted = false
                // Surface a refused header: without it the log shows four
                // gauges "refused by the ECU" and no reason why.
                val setup = c.setupReport()
                if (setup.size > notedSetup) {
                    val fresh = setup.drop(notedSetup)
                    notedSetup = setup.size
                    _state.update { s ->
                        s.copy(diagnostics = (s.diagnostics + fresh).takeLast(400))
                    }
                }
                _state.update {
                    it.copy(
                        readings = out,
                        voltage = volts,
                        lastUpdateMs = System.currentTimeMillis(),
                        refusals = refusalMap.values.toList(),
                        powertrainAsleep = !transDetected && !anyPower,
                    )
                }
                appendLog(out, volts)
                delay(if (gentle) 1_500 else 800)
            }
            // Reached only when the loop stops. A cancel — the sweep, the
            // console or the adapter check taking the adapter — is not a lost
            // session, and marking it disconnected used to blank the gauges
            // and hide the connect button for a link that was never gone.
            if (!c.isConnected()) _state.update { it.copy(connected = false) }
        }
    }

    private fun readingFor(
        def: PidDef,
        v: Float?,
        cached: Pair<Float, Long>?,
        now: Long,
    ): GaugeReading {
        if (v != null) return gauge(def, v)
        if (cached != null && now - cached.second < 15_000) {
            return GaugeReading(def.id, def.label, cached.first, def.unit, "HOLD")
        }
        return GaugeReading(def.id, def.label, null, def.unit, "NO DATA")
    }

    private fun gauge(def: PidDef, v: Float): GaugeReading {
        val status = when {
            def.critF != null && v >= def.critF -> "CRIT"
            def.warnF != null && v >= def.warnF -> "WARN"
            else -> null
        }
        return GaugeReading(def.id, def.label, v, def.unit, status)
    }

    /** Rebuilt on a dropped link; null until an adapter has been picked. */
    private var lastSession: (() -> Unit)? = null
    private var reconnectJob: Job? = null
    @Volatile private var autoReconnect = false

    /**
     * The link dropped on its own: key off at the store, phone out of range,
     * dongle browned out. Nobody should have to walk back to the OBD port for
     * that, so the session is rebuilt in the background — the transport's warm
     * start (ATWS) clears whatever state the dongle was left in, which is the
     * job the unplug was doing.
     */
    private fun scheduleReconnect() {
        if (!autoReconnect) return
        if (reconnectJob?.isActive == true) return
        val again = lastSession ?: return
        reconnectJob = scope.launch {
            try { client?.close() } catch (_: Exception) {}
            client = null
            stopPoll()
            _state.update { it.copy(connected = false, error = "Link dropped — reconnecting…") }
            var wait = 1_500L
            repeat(8) {
                delay(wait)
                if (!autoReconnect) return@launch
                if (client != null) return@launch
                again()
                var waited = 0
                while (client == null && waited < 30_000 && autoReconnect) {
                    delay(500)
                    waited += 500
                }
                if (client != null) return@launch
                wait = (wait * 1.5).toLong().coerceAtMost(8_000)
            }
            _state.update {
                it.copy(error = "Couldn't get the adapter back on its own — tap it to retry.")
            }
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopPoll()
        resetDetection()
        try { client?.close() } catch (_: Exception) {}
        client = null
        _state.update {
            it.copy(connected = false, demoMode = false, readings = emptyMap(), refusals = emptyList())
        }
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- Tow-trip CSV logging (phone storage, user stops/starts it) ----

    /** The file of the trip that just finished, for a post-drive summary. */
    private var lastClosedLog: File? = null

    /** Summary of the trip that just finished, computed off the UI thread. */
    suspend fun readTripSummary(thresholdF: Float = 220f): TripSummary? {
        val f = lastClosedLog ?: return null
        return withContext(Dispatchers.IO) { TripSummaries.parse(f, thresholdF) }
    }

    fun toggleLogging(): File? {
        val cur = _state.value
        if (cur.logging) {
            _state.update { it.copy(logging = false) }
            lastClosedLog = logFile
            logFile = null
            return null
        }
        val dir = logDir ?: return null
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "tacoma-tow-$stamp.csv")
        f.writeText("time,trans_pan_f,trans_conv_f,coolant_f,oil_f,rpm,speed_mph,volts\n")
        logFile = f
        _state.update { it.copy(logging = true) }
        return f
    }

    private fun appendLog(readings: Map<String, GaugeReading>, volts: Float?) {
        val f = logFile ?: return
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            fun g(id: String) = readings[id]?.valueF?.toString() ?: ""
            f.appendText("$t,${g("trans_pan")},${g("trans_conv")},${g("coolant")},${g("oil")},${g("rpm")},${g("speed")},${volts ?: ""}\n")
        } catch (_: Exception) { /* logging must never break polling */ }
    }
}
