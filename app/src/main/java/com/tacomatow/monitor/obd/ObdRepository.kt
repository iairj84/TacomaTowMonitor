package com.tacomatow.monitor.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private var pollJob: Job? = null
    /** Detected transmission definitions — replaced at connect time. */
    private var panDef: PidDef = TacomaPids.TRANS_PAN
    private var convDef: PidDef = TacomaPids.TRANS_CONVERTER
    private val detectLog = mutableListOf<String>()
    private var oilMisses = 0
    private var logFile: File? = null
    private var logDir: File? = null

    fun attachLogDir(dir: File) {
        logDir = dir
    }

    fun startDemo() {
        stopPoll()
        _state.update {
            it.copy(connected = true, demoMode = true, deviceName = "Demo — no adapter", error = null)
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
        _state.update {
            it.copy(
                readings = mapOf(
                    "trans_pan" to gauge(TacomaPids.TRANS_PAN, pan),
                    "trans_conv" to gauge(TacomaPids.TRANS_CONVERTER, conv),
                    "coolant" to gauge(TacomaPids.COOLANT, coolant),
                    "oil" to GaugeReading("oil", "Engine Oil", null, "°F", "NO DATA"),
                    "rpm" to GaugeReading("rpm", "RPM", rpm, "rpm"),
                ),
                voltage = 13.8f,
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
        beginSession(
            label = device.name ?: device.address,
            transports = listOf(
                "classic" to { SppTransport(adapter, device) as ElmTransport },
                "ble" to { BleTransport(context.applicationContext, device) as ElmTransport },
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
        oilMisses = 0
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

            client = won
            _state.update {
                it.copy(
                    connected = true,
                    error = null,
                    deviceName = "$label (${if (winnerKind == "ble") "BLE" else "classic"})",
                    triedConnect = true,
                    diagnostics = trail,
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
    private suspend fun detectTransmission(c: Elm327Client) {
        detectLog.clear()
        panDef = TacomaPids.TRANS_PAN
        convDef = TacomaPids.TRANS_CONVERTER
        detectLog.add("protocol: ATSP${c.activeProtocol()}")

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
                break
            }
            delay(120)
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
        _state.update { it.copy(diagnostics = it.diagnostics + detectLog) }
    }

    /**
     * Read-only sweep of Toyota customize local identifiers.
     * Pauses gauge polling while it runs so replies can't interleave, then
     * restarts it. Never writes to any ECU.
     */
    suspend fun sweepCustomize(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<LidReading> {
        val c = client ?: return emptyList()
        pollJob?.cancel()
        val total = CustomizeExplorer.totalProbes
        val out = mutableListOf<LidReading>()
        try {
            for (target in CustomizeExplorer.TARGETS) {
                for (lid in CustomizeExplorer.LID_RANGE) {
                    val cmd = CustomizeExplorer.readCommand(lid)
                    val raw = withContext(Dispatchers.IO) {
                        try { c.readRaw(target.header, cmd, 1_200) } catch (_: Exception) { null }
                    }
                    val payload = raw?.let { c.payloadFor(cmd, it) }
                    out += LidReading(
                        header = target.header,
                        ecuLabel = target.label,
                        lid = lid,
                        raw = raw,
                        payloadHex = CustomizeExplorer.hex(payload),
                    )
                    onProgress(out.size, total)
                    delay(70)
                }
            }
        } finally {
            if (_state.value.connected) startPoll(c)
        }
        return out
    }

    private fun startPoll(c: Elm327Client) {
        pollJob?.cancel()
        pollJob = scope.launch {
            detectTransmission(c)
            // BLE clones lose the odd reply. A single miss must never wipe a
            // gauge the driver is watching, so keep the last good value and
            // mark it HOLD until it goes properly stale.
            val lastGood = mutableMapOf<String, Pair<Float, Long>>()
            while (isActive && c.isConnected()) {
                val out = mutableMapOf<String, GaugeReading>()
                // Transmission definitions are whatever detection found.
                val pollList = listOf(panDef, convDef, TacomaPids.COOLANT, TacomaPids.OIL, TacomaPids.RPM, TacomaPids.SPEED)
                for (def in pollList) {
                    if (!isActive) break
                    val v = withContext(Dispatchers.IO) {
                        try { c.readPid(def) } catch (_: Exception) { null }
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
                    delay(150) // BLE needs a beat between header flips
                }
                val volts = withContext(Dispatchers.IO) {
                    try { c.readVoltage() } catch (_: Exception) { null }
                }
                _state.update {
                    it.copy(readings = out, voltage = volts, lastUpdateMs = System.currentTimeMillis())
                }
                appendLog(out, volts)
                delay(800)
            }
            _state.update { it.copy(connected = false) }
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

    fun disconnect() {
        stopPoll()
        try { client?.close() } catch (_: Exception) {}
        client = null
        _state.update { it.copy(connected = false, demoMode = false, readings = emptyMap()) }
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- Tow-trip CSV logging (phone storage, user stops/starts it) ----

    fun toggleLogging(): File? {
        val cur = _state.value
        if (cur.logging) {
            _state.update { it.copy(logging = false) }
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
