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
                onPick = { device ->
                    showPicker = false
                    val mgr = getSystemService(BluetoothManager::class.java)
                    val adapter: BluetoothAdapter? = mgr?.adapter
                    if (adapter == null) {
                        connectMsg = "No Bluetooth adapter on this phone."
                        return@BluetoothPicker
                    }
                    ObdHolder.repository.connect(adapter, device) { ok, why ->
                        connectMsg = if (ok) null else (why ?: "Connect failed")
                    }
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

    @SuppressLint("MissingPermission")
    @Composable
    private fun BluetoothPicker(onPick: (BluetoothDevice) -> Unit, onDismiss: () -> Unit) {
        val mgr = getSystemService(BluetoothManager::class.java)
        val bonded: List<BluetoothDevice> = try {
            mgr?.adapter?.bondedDevices?.toList().orEmpty()
        } catch (se: SecurityException) {
            emptyList()
        }
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Pick your paired ELM327", style = MaterialTheme.typography.headlineSmall)
            Text("Usually named OBDII / ELM327 / VGate. Pair it in system Bluetooth first (PIN 0000 or 1234).",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bonded) { d ->
                    Card(onClick = { onPick(d) }) {
                        Text("${d.name ?: "(no name)"}\n${d.address}", Modifier.padding(12.dp))
                    }
                }
            }
            if (bonded.isEmpty()) Text("No paired devices found.")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
