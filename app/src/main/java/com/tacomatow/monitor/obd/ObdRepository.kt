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

    /** Bluetooth Classic (SPP) dongles: OBDII / ELM327 / VGate-SPP. */
    fun connect(adapter: BluetoothAdapter, device: BluetoothDevice, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        beginSession(device.name ?: device.address, { SppTransport(adapter, device) }, onDone)
    }

    /** Bluetooth LE dongles: the plug-ins Torque shows as "odbBLE" / "odbble". */
    fun connectBle(context: android.content.Context, device: BluetoothDevice, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        beginSession(device.name ?: device.address, { BleTransport(context.applicationContext, device) }, onDone)
    }

    private fun beginSession(
        label: String,
        transport: () -> ElmTransport,
        onDone: (Boolean, String?) -> Unit = { _, _ -> },
    ) {
        stopPoll()
        _state.update { it.copy(error = null, demoMode = false, deviceName = label) }
        scope.launch {
            val c = Elm327Client(transport())
            val ok = try {
                withContext(Dispatchers.IO) { c.connect() }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) { onDone(false, "Bluetooth permission denied") }
                _state.update { it.copy(error = "Bluetooth permission denied") }
                return@launch
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                c.close()
                _state.update { it.copy(connected = false, error = "Couldn't talk to the ELM327. Classic dongles must be paired first; BLE ones (odbble) connect straight from scan. Ignition ON either way.") }
                withContext(Dispatchers.Main) { onDone(false, "ELM init failed") }
                return@launch
            }
            client = c
            _state.update { it.copy(connected = true, error = null) }
            withContext(Dispatchers.Main) { onDone(true, null) }
            startPoll(c)
        }
    }

    private fun startPoll(c: Elm327Client) {
        pollJob?.cancel()
        pollJob = scope.launch {
            // First sweep: mark unsupported PIDs (oil 015C on many 2GR-FKS) once.
            val supported = mutableMapOf<String, Boolean>()
            while (isActive && c.isConnected()) {
                val out = mutableMapOf<String, GaugeReading>()
                for (def in TacomaPids.ALL) {
                    if (!isActive) break
                    val v = withContext(Dispatchers.IO) {
                        try { c.readPid(def) } catch (_: Exception) { null }
                    }
                    if (v == null && supported[def.id] != false) {
                        // One silent retry before calling it unsupported.
                        delay(200)
                        val retry = withContext(Dispatchers.IO) {
                            try { c.readPid(def) } catch (_: Exception) { null }
                        }
                        if (retry == null) supported[def.id] = false
                        else { supported[def.id] = true; out[def.id] = gauge(def, retry) }
                        if (retry == null) out[def.id] = GaugeReading(def.id, def.label, null, def.unit, "NO DATA")
                    } else if (v != null) {
                        supported[def.id] = true
                        out[def.id] = gauge(def, v)
                    } else {
                        out[def.id] = GaugeReading(def.id, def.label, null, def.unit, "NO DATA")
                    }
                    delay(120) // give the ELM bus a breath between header flips
                }
                val volts = withContext(Dispatchers.IO) {
                    try { c.readVoltage() } catch (_: Exception) { null }
                }
                _state.update {
                    it.copy(readings = out, voltage = volts, lastUpdateMs = System.currentTimeMillis())
                }
                appendLog(out, volts)
                delay(600)
            }
            _state.update { it.copy(connected = false) }
        }
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
