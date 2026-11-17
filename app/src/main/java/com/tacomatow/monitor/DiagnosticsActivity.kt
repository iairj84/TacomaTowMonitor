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
import androidx.compose.ui.platform.LocalContext
import com.tacomatow.monitor.obd.DidProbe
import com.tacomatow.monitor.obd.DidSweep
import com.tacomatow.monitor.obd.DidVerdict
import com.tacomatow.monitor.obd.DtcDescriptions
import com.tacomatow.monitor.obd.DtcReport
import com.tacomatow.monitor.obd.FreezeFrame
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
    val context = LocalContext.current
    var header by remember { mutableStateOf("701") }
    var command by remember { mutableStateOf("221627") }
    var reply by remember { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf(listOf<Pair<String, String?>>()) }
    var busy by remember { mutableStateOf(false) }
    var dtc by remember { mutableStateOf<DtcReport?>(null) }
    var dtcBusy by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<FreezeFrame.Report?>(null) }
    var frameBusy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }

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
                    Text("Trouble codes", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Reads the check-engine light plus stored (03), pending (07) and " +
                            "permanent (0A) codes, and the freeze frame (02) those codes " +
                            "recorded. Read-only: clearing codes writes to the truck, so " +
                            "that button does not exist here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (!state.connected) {
                        Text(
                            "Connect the adapter on the dashboard first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        dtc?.let { report ->
                            val silent = report.milOn == null &&
                                report.confirmed.isEmpty() && report.pending.isEmpty() &&
                                report.permanent.isEmpty()
                            Text(
                                when {
                                    report.milOn == true ->
                                        "Check-engine light is ON" +
                                            (report.milCount?.let { " ($it stored)" } ?: "")
                                    report.milOn == false -> "Check-engine light is off"
                                    else -> "Light state: no reply"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            DtcGroup("Stored", report.confirmed)
                            DtcGroup("Pending", report.pending)
                            DtcGroup("Permanent", report.permanent)
                            if (silent) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "No reply at all — the bus may be asleep (ignition off), or " +
                                        "this adapter cannot reach the engine ECU.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            report.note?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    dtcBusy = true
                                    scope.launch {
                                        dtc = ObdHolder.repository.readTroubleCodes()
                                        dtcBusy = false
                                    }
                                },
                                enabled = state.connected && !dtcBusy,
                            ) { Text(if (dtcBusy) "Reading…" else if (dtc == null) "Read codes" else "Re-read") }
                            Button(
                                onClick = {
                                    frameBusy = true
                                    scope.launch {
                                        // A frame with no code list cannot say whether its
                                        // code is stored or pending, so read the codes too
                                        // when this button is the first thing tapped.
                                        if (dtc == null) dtc = ObdHolder.repository.readTroubleCodes()
                                        frame = ObdHolder.repository.readFreezeFrame()
                                        frameBusy = false
                                    }
                                },
                                enabled = state.connected && !frameBusy,
                            ) {
                                Text(
                                    if (frameBusy) "Reading…"
                                    else if (frame == null) "Freeze frame" else "Re-read frame",
                                )
                            }
                        }
                        frame?.let { report -> FrameGroup(report, dtc) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

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

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Transcript records every command and reply as text. It is the one thing a " +
                            "protocol bug can never be argued with, and it replays byte for byte in a test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            if (recording) {
                                val text = ObdHolder.repository.stopTranscript()
                                recording = false
                                if (text != null) {
                                    AdapterLogShare.share(
                                        context,
                                        text,
                                        subject = "Tacoma Tow Monitor — transcript",
                                    )
                                }
                            } else {
                                ObdHolder.repository.startTranscript()
                                recording = true
                            }
                        },
                        enabled = state.connected || recording,
                    ) {
                        Text(
                            when {
                                recording -> "Stop and share transcript"
                                else -> "Record transcript"
                            },
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

            Spacer(Modifier.height(12.dp))
            DidSweepCard()

            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
}

/**
 * Sweeps a bounded set of mode 22 identifiers and reports what each one did.
 * Turns "trans temp doesn't work" into a list of identifiers that the truck
 * actually answers.
 */
@Composable
private fun DidSweepCard() {
    val state by ObdHolder.repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sweeping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var probes by remember { mutableStateOf(listOf<DidProbe>()) }
    val total = remember { DidSweep.dids().size }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Capability sweep", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Probes $total mode 22 identifiers at header 0x701 and records which answer. " +
                    "Read-only, and takes about a minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        sweeping = true
                        probes = emptyList()
                        progress = "Starting…"
                        scope.launch {
                            probes = ObdHolder.repository.sweepDids { done, all, did ->
                                progress = "0x$did · $done/$all"
                            }
                            sweeping = false
                        }
                    },
                    enabled = state.connected && !sweeping,
                ) { Text(if (sweeping) "Sweeping…" else "Start sweep") }

                if (probes.isNotEmpty()) {
                    TextButton(
                        onClick = { AdapterLogShare.share(context, DidSweep.describe(probes)) },
                    ) { Text("Share results") }
                }
            }

            if (sweeping) {
                Spacer(Modifier.height(8.dp))
                Text(
                    progress,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (probes.isNotEmpty()) {
                val data = probes.count { it.verdict == DidVerdict.DATA }
                val refused = probes.count { it.verdict == DidVerdict.REFUSED }
                val silent = probes.count { it.verdict == DidVerdict.SILENT }

                Spacer(Modifier.height(10.dp))
                Text(
                    "data $data · refused $refused · silent $silent",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(6.dp))

                probes.filter { it.verdict != DidVerdict.SILENT }.forEach { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "22${p.did}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            if (p.verdict == DidVerdict.DATA) (p.payloadHex ?: "data")
                            else (p.refusal ?: "refused"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        data > 0 ->
                            "Your adapter does carry manufacturer data — at least these identifiers " +
                                "answered. Anything missing is absent from the calibration, not the dongle."
                        refused > 0 ->
                            "The ECU answered and refused, which proves the whole path works: request " +
                                "format, adapter and addressing are all fine. These identifiers simply " +
                                "aren't published on this truck."
                        else ->
                            "Nothing came back at all. That is the adapter-limit signature: it accepts " +
                                "the session but cannot carry manufacturer replies."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The recorded conditions, one block per frame. The code is tagged stored or
 * pending against the scan already on screen: an ECU that keeps a frame per
 * code is the only way a pending code has one at all.
 */
@Composable
private fun FrameGroup(report: FreezeFrame.Report, codes: DtcReport?) {
    Spacer(Modifier.height(12.dp))
    Text("Freeze frame", style = MaterialTheme.typography.titleSmall)
    Text(
        "What the truck was doing when the code set — recorded by the ECU itself, read with " +
            "mode 02. Read-only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (report.isEmpty) {
        Spacer(Modifier.height(6.dp))
        Text(
            report.note ?: "No frame stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // A pending code only has a frame where the ECU keeps one per code. Say so
    // rather than leaving the gap to be inferred from the list above.
    val unframed = codes?.pending.orEmpty()
        .filter { p -> report.snapshots.none { it.dtc == p } }
    if (unframed.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Pending ${unframed.joinToString(", ")} has no recorded frame — most ECUs keep a " +
                "frame only for the code that set it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    report.snapshots.forEach { snap ->
        Spacer(Modifier.height(8.dp))
        val code = snap.dtc
        val tag = when {
            code == null -> "no code recorded"
            codes?.confirmed?.contains(code) == true -> "$code · stored"
            codes?.pending?.contains(code) == true -> "$code · pending"
            else -> code
        }
        Text(
            "Frame ${snap.frame} — $tag",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        code?.let {
            Text(
                DtcDescriptions.describe(it)
                    ?: "Manufacturer-specific — check a Toyota reference, not a generic list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
        if (snap.readings.isEmpty()) {
            Text(
                "The ECU named this frame's code but answered none of its values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        snap.readings.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.label, style = MaterialTheme.typography.bodySmall)
                Text(
                    r.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
    report.note?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DtcGroup(label: String, codes: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (codes.isEmpty()) {
            Text(
                "none",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            codes.forEach { code ->
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    DtcDescriptions.describe(code)
                        ?: "Manufacturer-specific — check a Toyota reference, not a generic list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }
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
