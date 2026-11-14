package com.tacomatow.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.ui.TacomaTheme
import kotlinx.coroutines.launch

/**
 * Raw ELM327 console. Exists so a "no data" can be proven rather than
 * guessed at: it reports the adapter's identity and protocol, and sends
 * whatever header/command you type, showing the exact reply.
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TacomaTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DiagnosticsScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val state by ObdHolder.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var header by remember { mutableStateOf("701") }
    var command by remember { mutableStateOf("221627") }
    var reply by remember { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf(listOf<Pair<String, String?>>()) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "RAW CONSOLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Adapter diagnostics", style = MaterialTheme.typography.headlineSmall)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Adapter identity", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (!state.connected) {
                        Text(
                            "Connect the adapter on the dashboard first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (identity.isEmpty()) {
                        Text(
                            "Run the check to read ATI / ATDPN / ATRV.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        identity.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    value ?: "—",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                identity = ObdHolder.repository.adapterIdentity()
                                busy = false
                            }
                        },
                        enabled = state.connected && !busy,
                    ) { Text(if (busy) "Reading…" else "Read identity") }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Send a command", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Header is the 11-bit target (701, 7E0, 750…). Leave blank for the default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        label = { Text("Header") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true
                                reply = null
                                scope.launch {
                                    reply = ObdHolder.repository.sendRaw(header, command)
                                    busy = false
                                }
                            },
                            enabled = state.connected && !busy,
                        ) { Text(if (busy) "Sending…" else "Send") }
                        TextButton(onClick = { reply = null }) { Text("Clear") }
                    }
                    reply?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Useful probes", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Each line sets the header and command, then sends it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Preset("ATI", null) { h, c -> header = h; command = c }
                    Preset("ATDPN", null) { h, c -> header = h; command = c }
                    Preset("221627 @701 — trans pan DID", "701" to "221627") { h, c -> header = h; command = c }
                    Preset("221627 @7E0 — same DID, engine ECU", "7E0" to "221627") { h, c -> header = h; command = c }
                    Preset("2114 @750 — body block (locks)", "750" to "2114") { h, c -> header = h; command = c }
                    Preset("21A7 @7C0 — meter block (chime)", "7C0" to "21A7") { h, c -> header = h; command = c }
                    Preset("0100 @default — supported PIDs (sanity)", "" to "0100") { h, c -> header = h; command = c }
                }
            }

            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun Preset(label: String, preset: Pair<String, String>?, apply: (String, String) -> Unit) {
    TextButton(
        onClick = { preset?.let { (h, c) -> apply(h, c) } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
