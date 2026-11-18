package com.tacomatow.monitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.FileProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tacomatow.monitor.obd.BlockChange
import com.tacomatow.monitor.obd.ChangeRecord
import com.tacomatow.monitor.obd.CustomizeExplorer
import com.tacomatow.monitor.obd.CustomizeKnownBlocks
import com.tacomatow.monitor.obd.CustomizeNoise
import com.tacomatow.monitor.obd.CustomizeNoiseStore
import com.tacomatow.monitor.obd.CustomizeMapping
import com.tacomatow.monitor.obd.CustomizeRecordStore
import com.tacomatow.monitor.obd.CustomizeSnapshotStore
import com.tacomatow.monitor.obd.LidReading
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.obd.SnapshotDiff
import com.tacomatow.monitor.obd.VehicleProfileStore
import com.tacomatow.monitor.ui.TacomaTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeScreen(onBack: () -> Unit) {
    val state by ObdHolder.repository.state.collectAsState()
    val results = remember { mutableStateListOf<LidReading>() }
    var scanning by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    // True while the dongle is released for the other app. Mirrors the
    // repository's persisted handoff flag, which is the source of truth
    // across process death.
    var handedOff by remember { mutableStateOf(ObdHolder.repository.isHandoffArmed()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stamp = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject))
    }

    // A body write may only land after an ignition cycle, so the snapshot has
    // to outlive this screen (and the process).
    val restored = remember { CustomizeSnapshotStore.load(context) }
    var snapshot by remember { mutableStateOf(restored) }
    var label by remember { mutableStateOf(restored?.label ?: "") }
    var valueFrom by remember { mutableStateOf("") }
    var valueTo by remember { mutableStateOf("") }
    // There-and-back: Scan 1 banks the baseline (A), Scan 2 stores the
    // changed sweep (B) without banking, Scan 3 reads the reverted sweep (C)
    // and only a block that tracked A->B->A counts. The frozen diff below is
    // the last leg; the confirmed answer comes from ToggleCheck.
    var changeDiff by remember { mutableStateOf<SnapshotDiff?>(null) }
    var midReadings by remember { mutableStateOf<List<LidReading>?>(null) }
    var toggleCheck by remember { mutableStateOf<com.tacomatow.monitor.obd.ToggleCheck?>(null) }
    // Which ranked mover is currently shown as the answer (0 = most likely).
    var answerIndex by remember { mutableIntStateOf(0) }
    var changeLabel by remember { mutableStateOf("") }
    var changeBaselineAt by remember { mutableStateOf(0L) }
    var changeCurrentAt by remember { mutableStateOf(0L) }
    var savedAnswerKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val records = remember {
        mutableStateListOf<ChangeRecord>().apply { addAll(CustomizeRecordStore.load(context)) }
    }

    /** Name as it will be stamped on the snapshot and on a record. */
    fun composedLabel(): String {
        val base = label.trim().ifBlank { "Unnamed change" }
        val from = valueFrom.trim()
        val to = valueTo.trim()
        return if (from.isEmpty() && to.isEmpty()) {
            base
        } else {
            "$base: ${from.ifBlank { "?" }} → ${to.ifBlank { "?" }}"
        }
    }

    fun syncSnapshotLabel() {
        snapshot?.let { snap ->
            val updated = snap.copy(label = composedLabel())
            snapshot = updated
            CustomizeSnapshotStore.save(context, updated)
        }
    }

    // Truck identity lives in Settings now. Reload it whenever this screen
    // returns to the front, so a change made in Settings applies here.
    var currentProfile by remember { mutableStateOf(VehicleProfileStore.load(context)) }
    fun profile() = currentProfile

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text == null) {
            message = "Couldn't read that file."
            return@rememberLauncherForActivityResult
        }
        val parsed = CustomizeMapping.parse(text)
        if (parsed.error != null) {
            message = parsed.error
            return@rememberLauncherForActivityResult
        }
        val (merged, skipped) = CustomizeMapping.merge(records.toList(), parsed.records)
        records.clear()
        records.addAll(merged)
        CustomizeRecordStore.save(context, merged)
        val added = parsed.records.size - skipped
        val local = profile()
        val foreign = parsed.records.count { local.conflictsWith(it) }
        message = buildString {
            append("Imported $added ")
            append(if (added == 1) "entry" else "entries")
            if (skipped > 0) append(", skipped $skipped already present")
            append(".")
            if (foreign > 0) {
                append(" $foreign came from a different vehicle (this one: ${local.summary()}).")
            }
        }
    }

    // Keyed per vehicle: editing the profile loads that truck's shortlist (or a
    // clean one), so a stale list can't silently narrow what gets scanned.
    val profileKey = currentProfile.key()
    var knownBlocks by remember(profileKey) {
        mutableStateOf(CustomizeKnownBlocks.load(context, currentProfile))
    }
    var lastScanFast by remember { mutableStateOf(false) }
    // Per-bit noise masks learned on this truck: which bits moved between
    // scans with nothing changed. Diffs subtract only those bits, so a
    // setting in a steady byte of a lively block is still found. What was
    // subtracted stays visible under the diff.
    var noiseBlocks by remember(profileKey) {
        mutableStateOf(CustomizeNoiseStore.load(context))
    }

    /**
     * Three scans with nothing changed, then everything that moved between
     * them is noise by definition. Three rather than two because a block that
     * held still for a moment would otherwise survive as a candidate.
     */
    fun runNoiseLearn() {
        if (scanning) return
        message = null
        scanning = true
        scope.launch {
            val useKnown = knownBlocks.isNotEmpty()
            val plan = if (useKnown) knownBlocks.values.sumOf { it.size } else CustomizeExplorer.totalProbes
            val runs = mutableListOf<List<LidReading>>()
            for (pass in 1..3) {
                done = 0
                total = plan
                val run = ObdHolder.repository.sweepCustomize(
                    known = if (useKnown) knownBlocks else null,
                ) { d, t ->
                    done = d
                    total = t
                }
                if (run.none { it.supported }) {
                    scanning = false
                    message = "Nothing answered on pass $pass — connect on the dashboard and retry."
                    return@launch
                }
                runs += run
            }
            val learned = CustomizeNoise.masksAcross(runs)
            noiseBlocks = learned
            CustomizeNoiseStore.save(context, learned)
            if (!useKnown) knownBlocks = CustomizeKnownBlocks.record(context, currentProfile, runs.last())
            results.clear()
            results.addAll(runs.last())
            changeDiff = null; answerIndex = 0; savedAnswerKey = null; midReadings = null; toggleCheck = null
            lastScanFast = useKnown
            scanning = false
            val answering = runs.last().count { it.supported }
            message = if (learned.isEmpty()) {
                "$answering blocks read 3 times with nothing changed and none moved — nothing to subtract."
            } else {
                val bits = learned.values.sumOf { mask -> mask.count { it != 0 } }
                "${learned.size} of $answering blocks moved on their own across 3 scans " +
                    "($bits drifting byte(s)). Diffs now ignore those bits only — " +
                    "steady bits in the same blocks still count."
            }
        }
    }

    // This screen owns the adapter while it is open. The dashboard sweep would
    // otherwise keep firing 22/1627 reads between probes of a scan: one serial
    // channel, two callers, so replies interleave and the scan crawls.
    DisposableEffect(Unit) {
        ObdHolder.repository.setPollingPaused(true)
        onDispose { ObdHolder.repository.setPollingPaused(false) }
    }

    fun runScan(fast: Boolean, then: (List<LidReading>) -> Unit = {}) {
        message = null
        results.clear()
        done = 0
        total = if (fast) knownBlocks.values.sumOf { it.size } else CustomizeExplorer.totalProbes
        scanning = true
        lastScanFast = fast
        scope.launch {
            val found = ObdHolder.repository.sweepCustomize(
                known = if (fast) knownBlocks else null,
            ) { d, t ->
                done = d
                total = t
            }
            results.addAll(found)
            // A full sweep is what teaches the shortlist; keep it up to date.
            if (!fast) knownBlocks = CustomizeKnownBlocks.record(context, currentProfile, found)
            scanning = false
            val hits = found.count { it.supported }
            message = when {
                found.isEmpty() && fast -> "No known blocks answered — run a full scan."
                found.isEmpty() -> "No adapter session — connect on the dashboard first."
                fast -> "$hits of ${found.size} known blocks answered. Only a full scan can catch a " +
                    "block that has just started answering."
                hits == 0 -> "No LIDs answered. This dongle may not pass multi-frame body-ECU replies."
                else -> "$hits of ${found.size} probes answered. Bodies shown below."
            }
            then(found)
        }
    }

    // Recompute whenever the sweep result set or the saved snapshot changes.
    val current = results.toList()
    // A fast re-scan never asked about the omitted probes, so their absence
    // must not be reported as "stopped answering".
    val probedKeys: Set<Pair<String, Int>>? = if (lastScanFast) {
        knownBlocks.entries.flatMap { (header, lids) -> lids.map { header to it } }.toSet()
    } else {
        null
    }
    val diff: SnapshotDiff? = snapshot?.let {
        CustomizeExplorer.diff(it.readings, current, probedKeys, noiseBlocks)
    }

    /**
     * Hand the dongle to the other app, then disconnect and suspend
     * auto-reconnect. The last scan is already the baseline, so there is
     * nothing to resolve first — scan again when you return.
     */
    fun resetToggle() {
        midReadings = null
        toggleCheck = null
        changeDiff = null; answerIndex = 0; savedAnswerKey = null
    }

    fun pressHandoff() {
        if (scanning || handedOff) return
        val snap = snapshot
        if (snap != null && results.isNotEmpty()) {
            val banked = CustomizeSnapshotStore.Snapshot(
                at = System.currentTimeMillis(),
                readings = results.toList(),
                label = snap.label,
            )
            snapshot = banked
            CustomizeSnapshotStore.save(context, banked)
        }
        ObdHolder.repository.handoffRelease()
        handedOff = true
        message = "Dongle released — change the setting in the other app, then come " +
            "back here. Reconnect and re-scan run on their own."
    }

    /**
     * Back from the other app: reconnect, re-scan the known blocks, and diff
     * against the baseline. Recording a block banks the new scan as the next
     * baseline, so each setting is one tap out and one tap back.
     */
    fun returnFromHandoff() {
        if (scanning) return
        handedOff = false
        val snap = snapshot
        scanning = true
        done = 0
        total = 0
        message = "Back — reconnecting…"
        ObdHolder.repository.handoffResume { ok, why ->
            if (!ok) {
                scanning = false
                message = why ?: "Couldn't get the adapter back — reconnect on the dashboard, then re-scan."
                return@handoffResume
            }
            if (snap == null || knownBlocks.isEmpty()) {
                scanning = false
                message = "Reconnected. Tap Scan for change to save the baseline, then hand off to map a setting."
                return@handoffResume
            }
            runScan(fast = true) { found ->
                val hits = found.count { it.supported }
                if (hits == 0) return@runScan
                val keys = knownBlocks.entries
                    .flatMap { (header, lids) -> lids.map { header to it } }.toSet()
                val after = CustomizeExplorer.diff(snap.readings, found, keys, noiseBlocks)
                val banked = CustomizeSnapshotStore.Snapshot(
                    at = System.currentTimeMillis(),
                    readings = found,
                    label = snap.label,
                )
                snapshot = banked
                CustomizeSnapshotStore.save(context, banked)
                midReadings = null; toggleCheck = null
                if (after.identical) {
                    changeDiff = null; answerIndex = 0; savedAnswerKey = null
                    message = "Reconnected, $hits blocks re-read, nothing moved — this scan " +
                        "is banked as the baseline."
                } else {
                    changeDiff = after; answerIndex = 0; savedAnswerKey = null
                    changeLabel = snap.label
                    changeBaselineAt = snap.at
                    changeCurrentAt = banked.at
                    val top = after.topCandidate
                    message = if (top != null) {
                        "Reconnected, $hits blocks re-read. Your setting is probably ${top.displayLid} ${top.ecuLabel} — tap Save below, or Not this for the next candidate."
                    } else {
                        "Reconnected, $hits blocks re-read — see what moved below."
                    }
                }
            }
        }
    }

    // Returning from the other app fires ON_START — including on a fresh
    // launch after process death, where the persisted handoff flag is the
    // only trace that a hand-off was in flight.
    val handoffOwner = LocalLifecycleOwner.current
    DisposableEffect(handoffOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                currentProfile = VehicleProfileStore.load(context)
                if (ObdHolder.repository.isHandoffArmed()) {
                    returnFromHandoff()
                }
            }
        }
        handoffOwner.lifecycle.addObserver(obs)
        onDispose { handoffOwner.lifecycle.removeObserver(obs) }
    }

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
                                "Toyota's local-identifier read (21 <LID>). These are the blocks a " +
                                "dealer tool changes for chimes, locks and light delays.",
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
                            "Parked, engine RUNNING (radio needs it), battery healthy. The adapter must be connected " +
                                "(return to the dashboard and connect first).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item {
                Column {
                    Text(
                        "STEP 1 — FIND BLOCKS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (knownBlocks.isEmpty()) "First visit: run the full scan once." else "Truck known — a re-scan is enough.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                changeDiff = null; answerIndex = 0; savedAnswerKey = null
                                runScan(false)
                            },
                            enabled = !scanning && state.connected,
                        ) { Text(if (scanning) "Scanning…" else "Scan every LID") }

                        Spacer(Modifier.size(8.dp))

                        OutlinedButton(
                            onClick = {
                                changeDiff = null; answerIndex = 0; savedAnswerKey = null
                                runScan(true)
                            },
                            enabled = !scanning && state.connected && knownBlocks.isNotEmpty(),
                        ) { Text("Re-scan known") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !state.connected -> "Not connected"
                            scanning -> "$done / $total"
                            knownBlocks.isEmpty() ->
                                "Full scan asks 0x01–0xFF on each module (about 1,020 probes, " +
                                    "a few minutes parked) and learns which blocks this truck " +
                                    "answers — then re-scans only ask those."
                            else -> {
                                val n = knownBlocks.values.sumOf { it.size }
                                "$n blocks have answered on this truck — Re-scan known reads only those."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (handedOff) {
                            Button(
                                onClick = { returnFromHandoff() },
                                enabled = !scanning,
                            ) { Text("Reconnect now") }
                        } else {
                            OutlinedButton(
                                onClick = { pressHandoff() },
                                enabled = !scanning && state.connected,
                            ) { Text("Hand off") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (handedOff) {
                            "Dongle is free for the other app. Reconnect and re-scan run when " +
                                "you come back — or tap Reconnect now."
                        } else {
                            "Hand off releases the dongle so another app can use it, and " +
                                "suspends auto-reconnect until you return. Each Scan for change is " +
                                "already the baseline for the next one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "STEP 2 — LEARN NOISE (ONCE)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Do this once with nothing changing. Bits that move on their own get ignored from now on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { runNoiseLearn() },
                            enabled = !scanning && state.connected,
                        ) { Text("Learn noise · 3 scans") }
                        Spacer(Modifier.size(8.dp))
                        if (noiseBlocks.isNotEmpty()) {
                            TextButton(onClick = {
                                noiseBlocks = emptyMap()
                                CustomizeNoiseStore.clear(context)
                                message = "Noise set cleared — every bit counts again."
                            }) { Text("Clear") }
                        }
                    }
                    Text(
                        if (noiseBlocks.isEmpty()) {
                            "Some bits move on their own (fuel, dimmer, doors). Scan 3 times " +
                                "with nothing changed and those bits are subtracted from every " +
                                "diff — steady bits in the same blocks still count."
                        } else {
                            val bits = noiseBlocks.values.sumOf { mask -> mask.count { it != 0 } }
                            "${noiseBlocks.size} blocks have bits known to move on their own " +
                                "($bits drifting byte(s) ignored). Re-learn after a battery pull or a big " +
                                "temperature change."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Text(
                    "Truck: ${currentProfile.summary()} — set in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Column {
                    Text(
                        "STEP 3 — NAME IT, CHANGE IT, SCAN",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Name the setting, tap Scan for change, change that ONE setting on the radio, tap Scan again — the app tells you which block is yours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { text ->
                            label = text
                            syncSnapshotLabel()
                        },
                        label = { Text("What you changed") },
                        placeholder = { Text("e.g. Auto-lock all doors") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = valueFrom,
                            onValueChange = { valueFrom = it; syncSnapshotLabel() },
                            label = { Text("Value from") },
                            placeholder = { Text("e.g. On") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = valueTo,
                            onValueChange = { valueTo = it; syncSnapshotLabel() },
                            label = { Text("Value to") },
                            placeholder = { Text("e.g. Off") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                if (snapshot == null) {
                                    if (results.none { it.supported }) {
                                        message = "Run Step 1 first — there is nothing to save a baseline from yet."
                                        return@OutlinedButton
                                    }
                                    val saved = CustomizeSnapshotStore.Snapshot(
                                        at = System.currentTimeMillis(),
                                        readings = results.toList(),
                                        label = composedLabel(),
                                    )
                                    snapshot = saved
                                    CustomizeSnapshotStore.save(context, saved)
                                    resetToggle()
                                    changeLabel = saved.label
                                    changeBaselineAt = saved.at
                                    message = "Baseline saved (1/3) — change that ONE setting on the radio, then tap Scan for change again."
                                } else if (knownBlocks.isEmpty()) {
                                    message = "Run Step 1 first — no known blocks to re-scan yet."
                                } else if (midReadings == null) {
                                    val old = snapshot!!
                                    val oldLabel = composedLabel()
                                    val keys = knownBlocks.entries
                                        .flatMap { (header, lids) -> lids.map { header to it } }.toSet()
                                    runScan(true) { found ->
                                        if (found.none { it.supported }) return@runScan
                                        val d = CustomizeExplorer.diff(old.readings, found, keys, noiseBlocks)
                                        val now = System.currentTimeMillis()
                                        midReadings = found
                                        toggleCheck = null
                                        changeDiff = if (d.identical) null else d
                                        answerIndex = 0; savedAnswerKey = null
                                        changeLabel = oldLabel
                                        changeBaselineAt = old.at
                                        changeCurrentAt = now
                                        message = if (d.identical) {
                                            "Nothing moved yet — change that ONE setting on the radio, then scan again."
                                        } else {
                                            "${d.changes.size} block(s) moved (2/3) — now put that setting BACK to \"${valueFrom.ifBlank { "its first value" }}\" and tap Scan for change again. Only the block that follows you back counts."
                                        }
                                    }
                                } else {
                                    val old = snapshot!!
                                    val mid = midReadings!!
                                    val oldLabel = composedLabel()
                                    val keys = knownBlocks.entries
                                        .flatMap { (header, lids) -> lids.map { header to it } }.toSet()
                                    runScan(true) { found ->
                                        if (found.none { it.supported }) return@runScan
                                        val check = com.tacomatow.monitor.obd.confirmToggle(old.readings, mid, found, keys, noiseBlocks)
                                        val now = System.currentTimeMillis()
                                        toggleCheck = check
                                        changeDiff = check.diffAB
                                        answerIndex = 0; savedAnswerKey = null
                                        changeLabel = oldLabel
                                        changeBaselineAt = old.at
                                        changeCurrentAt = now
                                        val banked = CustomizeSnapshotStore.Snapshot(
                                            at = now,
                                            readings = found,
                                            label = oldLabel,
                                        )
                                        snapshot = banked
                                        CustomizeSnapshotStore.save(context, banked)
                                        midReadings = null
                                        message = if (check.confirmed.isNotEmpty()) {
                                            val top = check.topConfirmed!!
                                            "Confirmed (3/3): ${top.displayLid} ${top.ecuLabel} followed you there and back — tap Save below, or Not this for the next confirmed block."
                                        } else {
                                            "Nothing followed you both ways — that setting may live in the radio itself, or truck state drowned it. Try again without opening doors or touching HVAC."
                                        }
                                    }
                                }
                            },
                            enabled = !scanning && state.connected &&
                                (snapshot != null && knownBlocks.isNotEmpty() || snapshot == null && results.any { it.supported }),
                        ) { Text("Scan for change") }

                        Spacer(Modifier.size(8.dp))

                        if (snapshot != null || changeDiff != null || toggleCheck != null || midReadings != null) {
                            TextButton(onClick = {
                                snapshot = null
                                label = ""
                                resetToggle()
                                changeLabel = ""
                                CustomizeSnapshotStore.clear(context)
                            }) {
                                Text("Clear")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val saved = snapshot
                    Text(
                        if (saved == null) {
                            "To map a setting (engine RUNNING, parked): name it, Scan (1/3 baseline), change that ONE setting " +
                                "on the factory radio, Scan (2/3), put it BACK, Scan (3/3). Only the block that follows both ways counts."
                        } else if (midReadings != null && toggleCheck == null) {
                            "Waiting on the put-back scan — change \"${saved.label.ifBlank { "that setting" }}\" back to its first value, then Scan again."
                        } else {
                            val at = stamp.format(Date(saved.at))
                            "\"${saved.label.ifBlank { "Unnamed change" }}\" · snapshot from $at · " +
                                "${saved.readings.count { it.supported }} blocks banked. Name the next setting and scan again."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }


            val snap = snapshot
            val frozen = changeDiff
            val check = toggleCheck
            val mid = midReadings
            if (check != null && !scanning) {
                // There-and-back verdict: only blocks that tracked the toggle count.
                val ranked = check.rankedConfirmed
                val match = ranked.getOrNull(answerIndex.coerceAtLeast(0))
                if (match != null) {
                item {
                    val shownLabel = changeLabel.ifBlank { "Unnamed change" }
                    val isSaved = savedAnswerKey == (match.header to match.lid)
                    val answer = match.toBlockChange()
                    AnswerCard(
                        answer = answer,
                        rank = ranked.indexOf(match) + 1,
                        ofTotal = ranked.size,
                        others = check.unconfirmed,
                        label = "$shownLabel (confirmed there-and-back)",
                        saved = isSaved,
                        noiseText = run {
                            val n = (check.diffAB.noise + check.diffBC.noise).distinctBy { it.header to it.lid }
                            if (n.isNotEmpty()) "Ignored — moves on its own: " + n.joinToString(", ") { it.displayLid + " " + it.ecuLabel } else null
                        },
                        onShare = {
                            share(
                                "Tacoma customize there-and-back",
                                com.tacomatow.monitor.obd.describeToggle(
                                    check = check,
                                    label = shownLabel,
                                    baselineAt = stamp.format(Date(changeBaselineAt)),
                                    changedAt = stamp.format(Date(changeCurrentAt)),
                                ),
                            )
                        },
                        onSave = {
                            val at = System.currentTimeMillis()
                            val added = match.byteChanges.map { b ->
                                ChangeRecord(
                                    label = shownLabel,
                                    header = match.header,
                                    ecuLabel = match.ecuLabel,
                                    lid = match.lid,
                                    byteIndex = b.index,
                                    before = b.before,
                                    after = b.after,
                                    at = at,
                                    year = currentProfile.year,
                                    trim = currentProfile.trim,
                                    calibration = currentProfile.calibration,
                                )
                            }
                            if (added.isNotEmpty()) {
                                records.addAll(added)
                                CustomizeRecordStore.save(context, records.toList())
                                savedAnswerKey = match.header to match.lid
                                message = "Saved $shownLabel → ${match.displayLid} ${match.ecuLabel}. Change the next setting and scan again."
                            }
                        },
                        onNotThis = {
                            savedAnswerKey?.let { key ->
                                val kept = records.filterNot { it.header to it.lid == key && it.label == shownLabel }
                                if (kept.size != records.size) {
                                    records.clear(); records.addAll(kept)
                                    CustomizeRecordStore.save(context, kept)
                                }
                                savedAnswerKey = null
                            }
                            answerIndex = (answerIndex + 1) % ranked.size
                            val next = ranked[answerIndex]
                            message = "Trying next confirmed: ${next.displayLid} ${next.ecuLabel}."
                        },
                    )
                }
                } else if (check.confirmed.isEmpty() && !scanning) {
                    item {
                        val shownLabel = changeLabel.ifBlank { "Unnamed change" }
                        NoConfirmCard(
                            label = shownLabel,
                            movedOnce = check.diffAB.changes.size,
                            unconfirmed = check.unconfirmed,
                            onShare = {
                                share(
                                    "Tacoma customize there-and-back",
                                    com.tacomatow.monitor.obd.describeToggle(
                                        check = check,
                                        label = shownLabel,
                                        baselineAt = stamp.format(Date(changeBaselineAt)),
                                        changedAt = stamp.format(Date(changeCurrentAt)),
                                    ),
                                )
                            },
                            onRetry = {
                                toggleCheck = null; changeDiff = null; answerIndex = 0
                                message = "Cleared the miss — baseline is still banked. Change ONE setting and scan again."
                            },
                        )
                    }
                }
            } else if (mid != null && frozen != null && !scanning) {
                item {
                    val shownLabel = changeLabel.ifBlank { "Unnamed change" }
                    WaitingPutBackCard(
                        label = shownLabel,
                        moved = frozen.changes.size,
                        onShare = {
                            share("Tacoma customize diff", CustomizeExplorer.describe(frozen, "baseline", "changed"))
                        },
                    )
                }
            }

            if (records.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            "RECORDED CHANGES (${records.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val file = CustomizeMapping.write(context, records.toList())
                                if (file == null) {
                                    message = "Couldn't write the mapping file."
                                } else {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.updates",
                                        file,
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = CustomizeMapping.MIME
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Tacoma customize mapping")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Export mapping"))
                                }
                            }) { Text("Export JSON") }
                            TextButton(onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }) { Text("Import") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                share(
                                    "Tacoma customize records",
                                    CustomizeRecordStore.describe(records.toList()) {
                                        stamp.format(Date(it))
                                    },
                                )
                            }) { Text("Share as text") }
                            TextButton(onClick = {
                                records.clear()
                                CustomizeRecordStore.clear(context)
                            }) { Text("Clear") }
                        }
                    }
                }
                items(
                    records.reversed(),
                    key = { "${it.header}-${it.lid}-${it.byteIndex}-${it.at}" },
                ) { record ->
                    RecordRow(record, conflicts = currentProfile.conflictsWith(record))
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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeading("BLOCKS THAT ANSWERED")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            share(
                                "Tacoma customize survey",
                                CustomizeExplorer.describeSurvey(
                                    hits,
                                    currentProfile.summary(),
                                ),
                            )
                        }) { Text("Share") }
                    }
                }
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
private fun RecordRow(record: ChangeRecord, conflicts: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(record.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    record.displayLid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                record.describe(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (conflicts) "${record.vehicleLabel()} — not this truck"
                else record.vehicleLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = if (conflicts) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnswerCard(
    answer: BlockChange,
    rank: Int,
    ofTotal: Int,
    others: List<BlockChange>,
    label: String,
    saved: Boolean,
    noiseText: String?,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onNotThis: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("YOUR SETTING IS PROBABLY", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onShare) { Text("Share") }
            }
            Text(
                "${answer.displayLid}  ·  ${answer.ecuLabel}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "\"$label\"",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "before  ${answer.beforeHex}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "after   ${answer.afterHex}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            answer.byteChanges.forEach { b ->
                Text(
                    b.describe(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                b.binaryText()?.let { bits ->
                    Text(
                        bits,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (answer.changedBitCount == 1) "1 bit moved — that is what one option looks like."
                else "${answer.changedBitCount} bits moved — if this disagrees with the setting, tap Not this below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var showDetails by remember { mutableStateOf(false) }
            if (others.isNotEmpty() || noiseText != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details" else "Details (${others.size} other mover${if (others.size == 1) "" else "s"})")
                }
            }
            if (showDetails) {
                if (others.isNotEmpty()) {
                    Text(
                        "Moved once but did not follow you back (truck state): " + others.joinToString(", ") { it.displayLid + " " + it.ecuLabel },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                noiseText?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave, enabled = !saved) { Text(if (saved) "Saved ✓" else "Save ${answer.displayLid}") }
                if (ofTotal > 1) {
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onNotThis) { Text("Not this? ($rank/$ofTotal)") }
                }
            }
        }
    }
}

@Composable
private fun WaitingPutBackCard(
    label: String,
    moved: Int,
    onShare: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("STEP 2 OF 3 — NOW PUT IT BACK", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onShare) { Text("Share") }
            }
            Text("\"$label\"", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "$moved block(s) moved. Put that setting BACK to its first value, then tap Scan for change again. Only the block that returns too counts — the rest is running-engine state.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoConfirmCard(
    label: String,
    movedOnce: Int,
    unconfirmed: List<BlockChange>,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("NO CONFIRMED MOVER", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onShare) { Text("Share") }
            }
            Text("\"$label\"", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "$movedOnce block(s) moved once but none followed you back — nothing saved. That setting may live in the radio itself, or doors/HVAC moved mid-scan. Baseline is still banked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unconfirmed.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details" else "Details (${unconfirmed.size} one-time mover${if (unconfirmed.size == 1) "" else "s"})")
                }
                if (showDetails) {
                    Text(
                        unconfirmed.joinToString(", ") { it.displayLid + " " + it.ecuLabel },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun DiffCard(
    diff: SnapshotDiff,
    label: String,
    onShare: () -> Unit,
    onRecord: (BlockChange) -> Unit,
    onSkip: (BlockChange) -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (diff.identical) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.primaryContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (diff.identical) "NO CHANGES SINCE SNAPSHOT" else "CHANGED SINCE SNAPSHOT",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onShare) { Text("Share") }
            }
            Text(
                "\"$label\"",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${diff.compared} blocks compared",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diff.changes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val top = diff.topCandidate
                Text(
                    if (diff.changes.size == 1 && top?.isSingleBit == true) {
                        "1 block moved by 1 bit — that is what one option looks like. Record it if the byte below matches the setting you changed."
                    } else if (diff.changes.size == 1) {
                        "1 block moved. Check the bit count below — 1 bit is one option, several bits is usually vehicle state."
                    } else if (diff.hasSpread) {
                        "${diff.changes.size} blocks in ${diff.ecusChanged} modules moved. One setting moves one block — the rest is vehicle state (key, doors, lights, blower). " +
                            (top?.let { "Most likely yours: ${it.displayLid} ${it.ecuLabel} (${it.changedBitCount} bit${if (it.changedBitCount == 1) "" else "s"}). " } ?: "") +
                            "Not sure? Put the setting back to its first value and Scan again — the block that moves twice is yours."
                    } else {
                        "${diff.changes.size} blocks moved in one module. " +
                            (top?.let { "Most likely yours: ${it.displayLid} (${it.changedBitCount} bit${if (it.changedBitCount == 1) "" else "s"}). " } ?: "") +
                            "Not sure? Put the setting back and Scan again — the block that moves twice is yours."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (diff.hasSpread) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (diff.identical) {
                Text(
                    "Nothing moved. Either that setting isn't in these blocks — audio and display " +
                        "options live in the radio itself — or the change didn't stick. Cycle the " +
                        "ignition and scan again before concluding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            diff.changes.forEachIndexed { index, change ->
                Spacer(Modifier.height(10.dp))
                ChangeRow(
                    change = change,
                    rank = index + 1,
                    isTop = change == diff.topCandidate && diff.changes.size > 1,
                    onRecord = { onRecord(change) },
                    onSkip = { onSkip(change) },
                )
            }

            if (diff.noise.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ignored — these bits moved too, but they move on their own: " +
                        diff.noise.joinToString(", ") {
                            it.displayLid + " " + it.ecuLabel + " (" + it.byteChanges.joinToString(", ") { b -> b.describe() } + ")"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (diff.unbaselined > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${diff.unbaselined} answering blocks were never probed in the baseline — " +
                        "it covers fewer blocks than this scan. Tap Scan for change to save a fresh baseline so they " +
                        "can be compared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (diff.gained.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Answered only after the change: " +
                        diff.gained.joinToString(", ") { it.displayLid + " " + it.ecuLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (diff.lost.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Answered only before the change: " +
                        diff.lost.joinToString(", ") { it.displayLid + " " + it.ecuLabel },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChangeRow(
    change: BlockChange,
    rank: Int = 1,
    isTop: Boolean = false,
    onRecord: () -> Unit,
    onSkip: () -> Unit = {},
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (isTop) "#${rank} ${change.displayLid}  ·  ${change.ecuLabel} — MOST LIKELY"
                else "#${rank} ${change.displayLid}  ·  ${change.ecuLabel}",
                style = MaterialTheme.typography.titleSmall,
                color = if (isTop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                change.header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "before  ${change.beforeHex}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "after   ${change.afterHex}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
        change.byteChanges.forEach { b ->
            Text(
                b.describe(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            b.binaryText()?.let { bits ->
                Text(
                    bits,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRecord) {
                Text("Record ${change.displayLid}")
            }
            TextButton(onClick = onSkip) {
                Text("Not mine")
            }
        }
        Text(
            when (val bits = change.changedBitCount) {
                1 -> "1 bit in 1 byte — looks like one option."
                else -> "$bits bits across ${change.byteChanges.size} byte(s) — more like doors/lights/blower state."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
