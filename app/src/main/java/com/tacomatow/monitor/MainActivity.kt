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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

        Column(Modifier.fillMaxSize().padding(16.dp)) {
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
        }

        if (showPicker) {
            BluetoothPicker(
                onPick = { device, ble ->
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
                    if (ble) ObdHolder.repository.connectBle(this@MainActivity, device, done)
                    else ObdHolder.repository.connect(adapter, device, done)
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
    private fun GaugeGrid(state: ObdUiState) {
        val order = listOf("trans_pan", "trans_conv", "coolant", "oil", "rpm", "speed")
        val cards = order.mapNotNull { state.readings[it] }
        if (!state.connected) {
            Card { Text("Pair your ELM327 in Android Bluetooth settings first, ignition ON, then Connect. Or tap Demo to preview the tow layout.", Modifier.padding(16.dp)) }
            return
        }
        if (cards.isEmpty()) {
            Text("Talking to the adapter… first sweep takes a few seconds (header flips to 701 for trans temps).")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cards) { g ->
                val bg = when (g.status) {
                    "CRIT" -> Color(0xFFF8D7DA)
                    "WARN" -> Color(0xFFFFF3CD)
                    else -> null
                }
                Card(colors = if (bg != null) CardDefaults.cardColors(containerColor = bg) else CardDefaults.cardColors()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(g.label, style = MaterialTheme.typography.labelMedium)
                            Text(
                                g.valueF?.let { v ->
                                    if (g.unit == "rpm") v.toInt().toString() else "%.0f".format(v)
                                } ?: "—",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                        Column {
                            Text(g.unit, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                g.status ?: if (g.valueF == null) "" else "OK",
                                color = when (g.status) {
                                    "CRIT" -> Color(0xFFB3261E)
                                    "WARN" -> Color(0xFF8A6D00)
                                    "NO DATA" -> Color.Gray
                                    else -> Color(0xFF2E7D32)
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text("Adapter ${state.voltage?.let { "%.1fV".format(it) } ?: ""} · updated ${state.lastUpdateMs.takeIf { it > 0 }?.let { android.text.format.DateFormat.format("HH:mm:ss", it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Tow rule of thumb: pan >220°F back off, >240°F stop and cool. Converter runs hotter — watch the spread under load.",
                    style = MaterialTheme.typography.bodySmall)
            }
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
                            Text("${d.name ?: "(no name)"}\n${d.address}", Modifier.padding(12.dp))
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
