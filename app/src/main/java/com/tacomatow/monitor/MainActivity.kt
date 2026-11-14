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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tacomatow.monitor.obd.GaugeReading
import com.tacomatow.monitor.obd.ObdForegroundService
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.obd.ObdUiState
import com.tacomatow.monitor.ui.LocalTowPalette
import com.tacomatow.monitor.ui.TacomaTheme
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
            val systemDark = isSystemInDarkTheme()
            // 0 = follow system, 1 = force dark, 2 = force light
            var themeMode by rememberSaveable { mutableIntStateOf(0) }
            val dark = when (themeMode) {
                1 -> true
                2 -> false
                else -> systemDark
            }
            TacomaTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TowHome(
                        dark = dark,
                        onToggleTheme = { themeMode = if (dark) 2 else 1 },
                    )
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

    /** Remembered adapter so the app can reconnect without a picker. */
    private val prefs by lazy { getSharedPreferences("tacoma", MODE_PRIVATE) }

    private fun rememberDevice(device: BluetoothDevice) {
        prefs.edit()
            .putString("last_mac", device.address)
            .putString("last_name", device.name ?: device.address)
            .apply()
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun TowHome(dark: Boolean, onToggleTheme: () -> Unit) {
        val state by ObdHolder.repository.state.collectAsState()
        var showPicker by remember { mutableStateOf(false) }
        var connectMsg by remember { mutableStateOf<String?>(null) }
        val lastMac = remember { prefs.getString("last_mac", null) }
        val lastName = remember { prefs.getString("last_name", null) }
        var autoTried by remember { mutableStateOf(false) }
        val context = LocalContext.current

        // Auto-reconnect to the dongle used last time, once, on launch.
        LaunchedEffect(lastMac) {
            val mac = lastMac ?: return@LaunchedEffect
            if (autoTried || state.connected || state.demoMode) return@LaunchedEffect
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return@LaunchedEffect
            autoTried = true
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@LaunchedEffect
            val device = try {
                adapter.getRemoteDevice(mac)
            } catch (_: IllegalArgumentException) {
                null
            } ?: return@LaunchedEffect
            ObdHolder.repository.connectAny(this@MainActivity, adapter, device) { ok, why ->
                connectMsg = if (ok) null else why
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Header(dark = dark, onToggleTheme = onToggleTheme)

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                ConnectionPanel(
                    state = state,
                    lastName = lastName,
                    onConnect = { showPicker = true },
                    onReconnect = {
                        val mac = lastMac ?: return@ConnectionPanel
                        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                        if (adapter == null) {
                            connectMsg = "No Bluetooth adapter on this phone."
                            return@ConnectionPanel
                        }
                        val device = try {
                            adapter.getRemoteDevice(mac)
                        } catch (_: IllegalArgumentException) { null }
                        if (device == null) {
                            connectMsg = "Saved adapter is no longer available — pick another."
                            showPicker = true
                            return@ConnectionPanel
                        }
                        ObdHolder.repository.connectAny(this@MainActivity, adapter, device) { ok, why ->
                            connectMsg = if (ok) null else why
                        }
                    },
                    onDemo = { ObdHolder.repository.startDemo() },
                    onDisconnect = { ObdHolder.repository.disconnect() },
                )

                connectMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    NoticeCard(it)
                }

                Spacer(Modifier.height(14.dp))
                GaugeCluster(state, onDemo = { ObdHolder.repository.startDemo() })

                Spacer(Modifier.height(14.dp))
                ActionRow(state)

                state.logging.let {
                    if (it) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Recording this pull to CSV in app storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.error?.let {
                    Spacer(Modifier.height(10.dp))
                    NoticeCard(it)
                }

                if (state.triedConnect) {
                    Spacer(Modifier.height(10.dp))
                    AdapterLog(state.diagnostics)
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            startActivity(Intent(this@MainActivity, CustomizeActivity::class.java))
                        },
                        enabled = state.connected,
                    ) {
                        Text("Customize explorer")
                    }
                    TextButton(
                        onClick = {
                            startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                        },
                        enabled = state.connected,
                    ) {
                        Text("Diagnostics")
                    }
                }

                Spacer(Modifier.height(28.dp).navigationBarsPadding())
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
                        if (ok) rememberDevice(device)
                    }
                    ObdHolder.repository.connectAny(this@MainActivity, adapter, device, done)
                },
                onDismiss = { showPicker = false },
            )
        }
    }

    @Composable
    private fun Header(dark: Boolean, onToggleTheme: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TACOMA TOW",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Transmission monitor",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    @Composable
    private fun ConnectionPanel(
        state: ObdUiState,
        lastName: String?,
        onConnect: () -> Unit,
        onReconnect: () -> Unit,
        onDemo: () -> Unit,
        onDisconnect: () -> Unit,
    ) {
        val palette = LocalTowPalette.current
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.connected) palette.ok else palette.idle),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.connected) (state.deviceName ?: "Connected") else "No adapter",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when {
                            state.demoMode -> "Demo data — no dongle attached"
                            state.connected -> "Live from the ELM327"
                            lastName != null -> "Last used: $lastName"
                            else -> "2019 Tacoma V6 · 3.5L"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else if (lastName != null) {
                    Button(onClick = onReconnect) { Text("Reconnect") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onConnect) { Text("Change") }
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = onDemo) { Text("Demo") }
                }
            }
        }
    }

    @Composable
    private fun ActionRow(state: ObdUiState) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    startForegroundService(Intent(this@MainActivity, ObdForegroundService::class.java))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Keep polling")
            }
            FilledTonalButton(
                onClick = { ObdHolder.repository.toggleLogging() },
                modifier = Modifier.weight(1f),
                colors = if (state.logging) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(
                    imageVector = if (state.logging) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(if (state.logging) "Stop log" else "Log trip")
            }
        }
    }

    @Composable
    private fun NoticeCard(message: String) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                message,
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    // ---------- gauges ----------

    private fun statusWord(status: String?): String = when (status) {
        "CRIT" -> "STOP — COOL DOWN"
        "WARN" -> "EASE OFF"
        "HOLD" -> "HOLDING"
        "NO DATA" -> "NO DATA"
        else -> "NORMAL"
    }

    /** Large transmission gauge: the number you glance at while pulling. */
    @Composable
    private fun HeroGauge(
        g: GaugeReading?,
        title: String,
        warnF: Float,
        critF: Float,
    ) {
        val palette = LocalTowPalette.current
        val status = g?.status
        val value = g?.valueF
        val accent = when (status) {
            "CRIT" -> palette.crit
            "WARN" -> palette.warn
            "HOLD", "NO DATA" -> palette.idle
            else -> palette.ok
        }
        val surface = when (status) {
            "CRIT" -> palette.critSurface
            "WARN" -> palette.warnSurface
            "HOLD", "NO DATA" -> palette.idleSurface
            else -> palette.okSurface
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = surface),
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            statusWord(status),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value?.let { "%.0f".format(it) } ?: "—",
                        style = MaterialTheme.typography.displayLarge,
                        color = if (value == null) palette.idle else accent,
                    )
                    Text(
                        "°F",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 14.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val span = (critF + 20f) - 100f
                val frac = value?.let { ((it - 100f) / span).coerceIn(0f, 1f) } ?: 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "WARN ${"%.0f".format(warnF)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.warn,
                    )
                    Text(
                        "CRIT ${"%.0f".format(critF)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.crit,
                    )
                }
            }
        }
    }

    @Composable
    private fun MiniGauge(g: GaugeReading, modifier: Modifier = Modifier) {
        val palette = LocalTowPalette.current
        val value = g.valueF
        val accent = when (g.status) {
            "CRIT" -> palette.crit
            "WARN" -> palette.warn
            "HOLD", "NO DATA" -> palette.idle
            else -> palette.ok
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = modifier,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    g.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value?.let {
                            if (g.unit == "rpm") it.toInt().toString() else "%.0f".format(it)
                        } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (value == null) palette.idle else accent,
                    )
                    Text(
                        " ${g.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp, start = 3.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun GaugeCluster(state: ObdUiState, onDemo: () -> Unit) {
        if (!state.connected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Nothing connected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Connect your ELM327 with the ignition on. Paired classic dongles appear first; " +
                            "BLE ones like odbble are found by scanning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDemo) { Text("Preview with demo data") }
                }
            }
            return
        }

        val pan = state.readings["trans_pan"]
        val conv = state.readings["trans_conv"]
        if (pan == null && conv == null) {
            Text(
                "Detecting the transmission temperature request…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroGauge(pan, "Transmission pan", 220f, 240f)
            HeroGauge(conv, "Converter out", 230f, 250f)

            // Only render cards that exist — an empty tile with a dash is noise.
            val minis = listOf("coolant", "rpm", "oil", "speed")
                .mapNotNull { state.readings[it] }
            minis.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEach { reading ->
                        MiniGauge(reading, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Updated " + (state.lastUpdateMs.takeIf { it > 0 }
                        ?.let { android.text.format.DateFormat.format("HH:mm:ss", it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.voltage?.let { "Adapter %.1f V".format(it) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun AdapterLog(lines: List<String>) {
        var open by remember { mutableStateOf(false) }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ADAPTER LOG",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { open = !open }) {
                        Text(if (open) "Hide" else "Show")
                    }
                }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    if (lines.isEmpty()) {
                        Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- adapter picker ----------

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

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ADAPTER",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text("Choose your ELM327", style = MaterialTheme.typography.headlineSmall)
                    }
                    IconButton(onClick = { stopScan(); onDismiss() }) {
                        Text("✕", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { SectionLabel("PAIRED (BLUETOOTH CLASSIC)") }
                    if (bonded.isEmpty()) {
                        item {
                            Text(
                                "No paired devices. Pair the dongle in Android Bluetooth settings first (PIN 0000 or 1234), then reopen this screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(bonded) { d ->
                            PickerRow(
                                title = d.name ?: "Unnamed device",
                                subtitle = d.address,
                                trailing = "PAIRED",
                                onClick = { stopScan(); onPick(d, false) },
                            )
                        }
                    }

                    item { Spacer(Modifier.height(6.dp)) }
                    item { SectionLabel("NEARBY (BLUETOOTH LE)") }
                    item {
                        Text(
                            "BLE dongles need no pairing. Scan and tap the one in your truck.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                            android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed({ stopScan() }, 12_000)
                                        }
                                    } catch (se: SecurityException) {
                                        scanMsg = "Bluetooth permission denied."
                                    }
                                },
                                enabled = !scanning,
                            ) { Text(if (scanning) "Scanning…" else "Scan") }
                            if (scanning) {
                                OutlinedButton(onClick = { stopScan() }) { Text("Stop") }
                            }
                        }
                    }
                    scanMsg?.let { msg ->
                        item {
                            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    val likely = bleHits.filter { looksLikeObd(it.name) }
                    val other = bleHits.filterNot { looksLikeObd(it.name) }
                    if (bleHits.isNotEmpty() && likely.isEmpty()) {
                        item {
                            Text(
                                "No obvious OBD names nearby — listed strongest signal first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (likely.isNotEmpty()) {
                        item { SectionLabel("LIKELY OBD ADAPTERS") }
                    }
                    items(likely, key = { it.device.address }) { h ->
                        PickerRow(
                            title = h.name ?: "Unknown device",
                            subtitle = h.device.address,
                            trailing = "${rssiBars(h.rssi)}  ${h.rssi} dBm",
                            onClick = { stopScan(); onPick(h.device, true) },
                        )
                    }
                    if (other.isNotEmpty()) {
                        item {
                            OutlinedButton(onClick = { showAllBle = !showAllBle }) {
                                Text(
                                    if (showAllBle) "Hide ${other.size} other devices"
                                    else "Show ${other.size} other devices"
                                )
                            }
                        }
                    }
                    if (showAllBle) {
                        items(other, key = { it.device.address }) { h ->
                            PickerRow(
                                title = h.name ?: "Unknown device",
                                subtitle = h.device.address,
                                trailing = "${rssiBars(h.rssi)}  ${h.rssi} dBm",
                                onClick = { stopScan(); onPick(h.device, true) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    @Composable
    private fun PickerRow(title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

