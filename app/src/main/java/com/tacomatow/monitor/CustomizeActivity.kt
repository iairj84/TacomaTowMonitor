package com.tacomatow.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tacomatow.monitor.obd.LidReading
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.ui.TacomaTheme
import kotlinx.coroutines.launch

/**
 * READ-ONLY discovery of Toyota customize blocks (C-BEST).
 *
 * This screen only sends `21 <LID>` reads. Writing a block (`3B`) is a
 * separate, deliberate step that needs a verified per-model table and a
 * saved backup — neither of which exists until this scan has run on the
 * actual truck.
 */
class CustomizeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TacomaTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CustomizeScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun CustomizeScreen(onBack: () -> Unit) {
    val state by ObdHolder.repository.state.collectAsState()
    val results = remember { mutableStateListOf<LidReading>() }
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                    "READ-ONLY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Customize explorer", style = MaterialTheme.typography.headlineSmall)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("What this does", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Asks the body ECU (0x750), combination meter (0x7C0), A/C amplifier (0x7C4) " +
                                "and smart-key ECU (0x7C6) which customization blocks they expose, using " +
                                "Toyota's local-identifier read (21 <LID>). These are the blocks Carista " +
                                "changes for chimes, locks and light delays.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Nothing here writes to the truck. A write (3B) needs a verified block " +
                                "layout and a saved backup, which only exists once this scan has run.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Before scanning",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ignition ON, engine OFF, battery healthy. The adapter must be connected " +
                                "(return to the dashboard and connect first).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            message = null
                            results.clear()
                            done = 0
                            total = com.tacomatow.monitor.obd.CustomizeExplorer.totalProbes
                            scanning = true
                            scope.launch {
                                val found = ObdHolder.repository.sweepCustomize { d, t ->
                                    done = d
                                    total = t
                                }
                                results.addAll(found)
                                scanning = false
                                if (found.isEmpty()) {
                                    message = "No adapter session — connect on the dashboard first."
                                } else {
                                    val hits = found.count { it.supported }
                                    message = if (hits == 0) {
                                        "No LIDs answered. This dongle may not pass multi-frame body-ECU replies."
                                    } else {
                                        "$hits of ${found.size} probes answered. Bodies shown below."
                                    }
                                }
                            }
                        },
                        enabled = !scanning && state.connected,
                    ) { Text(if (scanning) "Scanning…" else "Scan every LID") }

                    Spacer(Modifier.size(12.dp))

                    Text(
                        if (!state.connected) "Not connected"
                        else if (scanning) "$done / $total" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (scanning && total > 0) {
                item {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            message?.let { msg ->
                item {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Supported blocks first, grouped by ECU.
            val hits = results.filter { it.supported }
            if (hits.isNotEmpty()) {
                item { SectionHeading("BLOCKS THAT ANSWERED") }
                items(hits, key = { "${it.header}-${it.lid}" }) { reading ->
                    ReadingRow(reading)
                }
            }

            val misses = results.filterNot { it.supported }
            if (misses.isNotEmpty()) {
                item {
                    TextButton(onClick = { /* collapsed: raw misses view */ }) {
                        Text("${misses.size} blocks did not answer (unsupported or silent)")
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ReadingRow(reading: LidReading) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${reading.displayLid}  ·  ${reading.ecuLabel}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    reading.header,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                reading.payloadHex ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            reading.raw?.let { raw ->
                Text(
                    "raw: $raw",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
