package com.tacomatow.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tacomatow.monitor.obd.GaugeReading
import com.tacomatow.monitor.obd.ObdForegroundService
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.obd.ObdUiState
import java.io.File

class MainActivity : ComponentActivity() {

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user sees error text if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdHolder.repository.attachLogDir(File(filesDir, "tow-logs"))
        requestBtPermissions()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TowHome()
                }
            }
        }
    }

    private fun requestBtPermissions() {
        val needs = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needs += Manifest.permission.BLUETOOTH_CONNECT
            needs += Manifest.permission.BLUETOOTH_SCAN
            needs += Manifest.permission.POST_NOTIFICATIONS
        } else {
            needs += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needs.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) btPermissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun TowHome() {
        // (Unusual name keeps this sample's screen distinct; it is the tow dashboard.)
        val state by ObdHolder.repository.state.collectAsState()
        var showPicker by remember { mutableStateOf(false) }
        var connectMsg by remember { mutableStateOf<String?>(null) }

        // One scrollable column: gauges, buttons and the adapter log all reachable.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Tacoma Tow Monitor", style = MaterialTheme.typography.headlineSmall)
            Text(
                "2019 Tacoma V6 A/T · ELM327 · trans pan / converter, coolant, oil",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            ConnectionBar(
                state = state,
                onPick = { showPicker = true },
                onDemo = { ObdHolder.repository.startDemo() },
                onDisconnect = { ObdHolder.repository.disconnect() },
            )
            connectMsg?.let { Text(it, color = Color(0xFFB3261E)) }
            Spacer(Modifier.height(12.dp))

            GaugeGrid(state)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    startForegroundService(Intent(this@MainActivity, ObdForegroundService::class.java))
                }) { Text("Keep alive") }
                OutlinedButton(onClick = { ObdHolder.repository.toggleLogging() }) {
                    Text(if (state.logging) "Stop trip log" else "Log tow trip")
                }
            }
            if (state.logging) {
                Text("Logging CSV to app files/tow-logs — copy it off after the trip.",
                    style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFB3261E))
            }
            if (state.triedConnect) {
                Spacer(Modifier.height(8.dp))
                AdapterLog(state.diagnostics)
            }
        }

        if (showPicker) {
            BluetoothPicker(
                onPick = { device, _ ->
                    showPicker = false
                    val mgr = getSystemService(BluetoothManager::class.java)
                    val adapter: BluetoothAdapter? = mgr?.adapter
                    if (adapter == null) {
                        connectMsg = "No Bluetooth adapter on this phone."
                        return@BluetoothPicker
                    }
                    val done: (Boolean, String?) -> Unit = { ok, why ->
                        connectMsg = if (ok) null else (why ?: "Connect failed")
                    }
                    // Same call either way: classic is tried, then BLE falls in
                    // behind it. BLE-only dongles show up in the bonded list too,
                    // which is exactly how the wrong radio got picked before.
                    ObdHolder.repository.connectAny(this@MainActivity, adapter, device, done)
                },
                onDismiss = { showPicker = false },
            )
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun ConnectionBar(
        state: ObdUiState,
        onPick: () -> Unit,
        onDemo: () -> Unit,
        onDisconnect: () -> Unit,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.connected) {
                Text("● ${state.deviceName ?: "Connected"}", modifier = Modifier.weight(1f).padding(top = 12.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                Button(onClick = onPick, modifier = Modifier.weight(1f)) { Text("Connect ELM327") }
                OutlinedButton(onClick = onDemo) { Text("Demo") }
            }
        }
    }

    @Composable
    private fun AdapterLog(lines: List<String>) {
        var open by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { open = !open }) {
                Text(if (open) "Hide adapter log" else "Show adapter log")
            }
            if (open) {
                if (lines.isEmpty()) {
                    Text("No log yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            lines.forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Status colour for a gauge: calm green, hot amber, critical red, muted grey. */
    private fun statusColor(status: String?): Color = when (status) {
        "CRIT" -> Color(0xFFC62828)
        "WARN" -> Color(0xFFB26A00)
        "HOLD" -> Color(0xFF6A6A6A)
        "NO DATA" -> Color(0xFF9E9E9E)
        else -> Color(0xFF2E7D32)
    }

    private fun statusWord(status: String?): String = when (status) {
        "CRIT" -> "CRITICAL — STOP AND COOL"
        "WARN" -> "HOT — EASE OFF"
        "HOLD" -> "HOLDING"
        "NO DATA" -> "NO DATA"
        else -> "OK"
    }

    /** Background tint that reads at a glance from the driver's seat. */
    private fun statusTint(status: String?): Color = when (status) {
        "CRIT" -> Color(0xFFFBE3E3)
        "WARN" -> Color(0xFFFFF1D6)
        "HOLD" -> Color(0xFFEFEFEF)
        "NO DATA" -> Color(0xFFF2F2F2)
        else -> Color(0xFFE8F3E8)
    }

    /**
     * A single tow gauge. [hero] makes the transmission temps enormous —
     * they are the numbers being watched while dragging a trailer, so the
     * value dominates and the bar shows position between cold and critical.
     */
    @Composable
    private fun TowGauge(
        g: GaugeReading?,
        title: String,
        warnF: Float,
        critF: Float,
        hero: Boolean = false,
    ) {
        val value = g?.valueF
        val status = g?.status
        val numberSize = if (hero) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = statusTint(status)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (hero) 14.dp else 10.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4A4A4A))
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text(
                        text = value?.let { "%.0f".format(it) } ?: "—",
                        style = numberSize,
                        color = if (value == null) Color(0xFF9E9E9E) else statusColor(status),
                    )
                    Text(
                        text = " °F",
                        style = if (hero) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5A5A5A),
                        modifier = Modifier.padding(bottom = if (hero) 8.dp else 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (hero) {
                        Text(
                            statusWord(status),
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(status),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Scale runs 100°F (cold) to crit+20 (danger).
                val span = (critF + 20f) - 100f
                val frac = value?.let { ((it - 100f) / span).coerceIn(0f, 1f) } ?: 0f
                val track = if (hero) 12.dp else 6.dp
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(track)
                        .clip(RoundedCornerShape(track / 2))
                        .background(Color(0x22000000)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(track)
                            .clip(RoundedCornerShape(track / 2))
                            .background(statusColor(status)),
                    )
                }
                if (hero) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("100", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6A6A6A))
                        Text("warn %.0f".format(warnF), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6A6A6A))
                        Text("crit %.0f".format(critF), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6A6A6A))
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactGauge(g: GaugeReading?, modifier: Modifier = Modifier) {
        val value = g?.valueF
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = statusTint(g?.status)),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text((g?.label ?: "—").uppercase(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF4A4A4A))
                Text(
                    value?.let { if (g?.unit == "rpm") it.toInt().toString() else "%.0f".format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = when {
                        value == null -> Color(0xFF9E9E9E)
                        g?.status == "CRIT" -> Color(0xFFC62828)
                        g?.status == "WARN" -> Color(0xFFB26A00)
                        else -> Color(0xFF1B1B1B)
                    },
                )
                Text(g?.unit ?: "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6A6A6A))
            }
        }
    }

    @Composable
    private fun GaugeGrid(state: ObdUiState) {
        if (!state.connected) {
            Card {
                Text(
                    "Tap Connect: paired classic dongles appear first, or scan for BLE ones (odbble). " +
                        "Turn the ignition ON so the adapter can answer. Tap Demo to preview the layout.",
                    Modifier.padding(16.dp),
                )
            }
            return
        }
        val pan = state.readings["trans_pan"]
        val conv = state.readings["trans_conv"]
        if (pan == null && conv == null) {
            Text("Talking to the adapter… first sweep takes a few seconds (transmission request detection runs first).")
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Transmission first: the two numbers that decide how hard you can pull.
            TowGauge(pan, "Transmission pan", 220f, 240f, hero = true)
            TowGauge(conv, "Torque converter out", 230f, 250f, hero = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactGauge(state.readings["coolant"], Modifier.weight(1f))
                CompactGauge(state.readings["rpm"], Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactGauge(state.readings["oil"], Modifier.weight(1f))
                CompactGauge(state.readings["speed"], Modifier.weight(1f))
            }

            Text(
                "Adapter ${state.voltage?.let { "%.1fV".format(it) } ?: ""} · updated " +
                    (state.lastUpdateMs.takeIf { it > 0 }
                        ?.let { android.text.format.DateFormat.format("HH:mm:ss", it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Pan past 220°F: back off the throttle. Past 240°F: pull over and idle to cool. " +
                    "The converter always runs hotter — watch the gap widen on a long grade.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** One BLE sighting: advertised name if there is one, live signal level. */
    private data class BleHit(val device: BluetoothDevice, val name: String?, val rssi: Int)

    private val OBD_KEYWORDS = listOf(
        "obd", "obd2", "elm", "vgate", "vlink", "veepeak", "odb", "odble",
        "kiwi", "plx", "lemur", "konnwei", "tonwon", "icar", "obdlink",
        "bafx", "vcheck", "foseal", "panlong", "vpeak",
    )

    private fun looksLikeObd(name: String?): Boolean {
        val n = name?.lowercase().orEmpty()
        return OBD_KEYWORDS.any { n.contains(it) }
    }

    private fun rssiBars(rssi: Int): String = when {
        rssi >= -55 -> "▂▄▆█"
        rssi >= -67 -> "▂▄▆"
        rssi >= -80 -> "▂▄"
        else -> "▂"
    }

    @Composable
    private fun BleRow(h: BleHit, onTap: () -> Unit) {
        Card(onClick = onTap, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        h.name ?: "Unknown device",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(h.device.address, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${rssiBars(h.rssi)} ${h.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun BluetoothPicker(onPick: (BluetoothDevice, Boolean) -> Unit, onDismiss: () -> Unit) {
        val mgr = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = mgr?.adapter
        val bonded: List<BluetoothDevice> = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (se: SecurityException) {
            emptyList()
        }
        var scanning by remember { mutableStateOf(false) }
        var bleHits by remember { mutableStateOf(listOf<BleHit>()) }
        var showAllBle by remember { mutableStateOf(false) }
        var scanMsg by remember { mutableStateOf<String?>(null) }
        val scanCallback = remember {
            object : android.bluetooth.le.ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                    val d = result.device ?: return
                    // Advertised name is far more reliable than the cached device name.
                    val name = try {
                        result.scanRecord?.deviceName ?: d.name
                    } catch (se: SecurityException) { null }
                    val hit = BleHit(d, name, result.rssi)
                    bleHits = (bleHits.filterNot { it.device.address == d.address } + hit)
                        .sortedByDescending { it.rssi }
                }
                override fun onScanFailed(code: Int) {
                    scanMsg = "BLE scan failed ($code). Is Bluetooth on?"
                    scanning = false
                }
            }
        }
        fun stopScan() {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            scanning = false
        }
        // ONE scrollable list: two LazyColumns in a Column starve each other
        // of height, which is what made the old picker wonky.
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Pick your ELM327", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Classic (OBDII / ELM327 / VGate) — pair in system Bluetooth first (PIN 0000 or 1234), then pick below.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (bonded.isEmpty()) {
                    item { Text("No paired classic devices.", style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(bonded) { d ->
                        Card(onClick = { stopScan(); onPick(d, false) }) {
                            Text("${d.name ?: "(no name)"}\nTap to connect · ${d.address}", Modifier.padding(12.dp))
                        }
                    }
                }
                item {
                    Text("BLE adapters (odbble and friends need NO pairing — just scan and tap). If nothing appears, turn Location on: some phones require it for BLE scans.",
                        style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                bleHits = emptyList()
                                scanMsg = null
                                try {
                                    val s = adapter?.bluetoothLeScanner
                                    if (s == null) {
                                        scanMsg = "No BLE scanner — is Bluetooth on?"
                                    } else {
                                        s.startScan(
                                            null,
                                            android.bluetooth.le.ScanSettings.Builder()
                                                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                                                .build(),
                                            scanCallback,
                                        )
                                        scanning = true
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                            { stopScan() }, 12_000
                                        )
                                    }
                                } catch (se: SecurityException) {
                                    scanMsg = "Bluetooth permission denied."
                                }
                            },
                            enabled = !scanning,
                        ) { Text(if (scanning) "Scanning…" else "Scan for BLE adapters") }
                        if (scanning) OutlinedButton(onClick = { stopScan() }) { Text("Stop") }
                    }
                }
                scanMsg?.let { msg ->
                    item { Text(msg, color = Color(0xFFB3261E)) }
                }
                run {
                    val likely = bleHits.filter { looksLikeObd(it.name) }
                    val other = bleHits.filterNot { looksLikeObd(it.name) }
                    if (bleHits.isNotEmpty() && likely.isEmpty()) {
                        item {
                            Text("No obvious OBD names in range — your dongle may advertise only a MAC address. Closest (strongest signal) first:",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (likely.isNotEmpty()) {
                        item {
                            Text("Likely OBD adapters — tap to connect:",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    items(likely, key = { it.device.address }) { h ->
                        BleRow(h) { stopScan(); onPick(h.device, true) }
                    }
                    if (other.isNotEmpty()) {
                        item {
                            OutlinedButton(onClick = { showAllBle = !showAllBle }) {
                                Text(if (showAllBle) "Hide ${other.size} other Bluetooth devices" else "Show ${other.size} other Bluetooth devices")
                            }
                        }
                    }
                    if (showAllBle) {
                        items(other, key = { it.device.address }) { h ->
                            BleRow(h) { stopScan(); onPick(h.device, true) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { stopScan(); onDismiss() }) { Text("Close") }
        }
    }
}
