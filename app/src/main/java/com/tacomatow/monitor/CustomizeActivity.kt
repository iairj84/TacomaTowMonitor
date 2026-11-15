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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tacomatow.monitor.obd.BlockChange
import com.tacomatow.monitor.obd.ChangeRecord
import com.tacomatow.monitor.obd.CatalogSetting
import com.tacomatow.monitor.obd.CustomizeCatalogue
import com.tacomatow.monitor.obd.CustomizeExplorer
import com.tacomatow.monitor.obd.CustomizeKnownBlocks
import com.tacomatow.monitor.obd.CustomizeMapping
import com.tacomatow.monitor.obd.CustomizeRecordStore
import com.tacomatow.monitor.obd.CustomizeSnapshotStore
import com.tacomatow.monitor.obd.LidReading
import com.tacomatow.monitor.obd.ObdHolder
import com.tacomatow.monitor.obd.SnapshotDiff
import com.tacomatow.monitor.obd.VehicleProfile
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
    var picked by remember { mutableStateOf<CatalogSetting?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
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

    val storedProfile = remember { VehicleProfileStore.load(context) }
    var yearText by remember { mutableStateOf(storedProfile.year?.toString() ?: "") }
    var trimText by remember { mutableStateOf(storedProfile.trim) }
    var calibrationText by remember { mutableStateOf(storedProfile.calibration) }
    var readingCal by remember { mutableStateOf(false) }

    fun profile() = VehicleProfile(
        year = yearText.toIntOrNull()?.takeIf { it in 1980..2100 },
        trim = trimText.trim(),
        calibration = calibrationText.trim(),
    )

    fun persistProfile() = VehicleProfileStore.save(context, profile())

    val currentProfile = profile()

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

    fun runScan(fast: Boolean) {
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
        CustomizeExplorer.diff(it.readings, current, probedKeys)
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
                            "Ignition ON, engine OFF, battery healthy. The adapter must be connected " +
                                "(return to the dashboard and connect first).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { runScan(false) },
                            enabled = !scanning && state.connected,
                        ) { Text(if (scanning) "Scanning…" else "Scan every LID") }

                        Spacer(Modifier.size(8.dp))

                        OutlinedButton(
                            onClick = { runScan(true) },
                            enabled = !scanning && state.connected && knownBlocks.isNotEmpty(),
                        ) { Text("Re-scan known") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !state.connected -> "Not connected"
                            scanning -> "$done / $total"
                            knownBlocks.isEmpty() ->
                                "Scan once to learn which blocks this truck answers, then re-scans " +
                                    "only ask those."
                            else -> {
                                val n = knownBlocks.values.sumOf { it.size }
                                "$n blocks have answered on this truck — Re-scan known reads only those."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

                        item {
                Column {
                    Text(
                        "THIS VEHICLE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = yearText,
                            onValueChange = { t ->
                                yearText = t.filter { it.isDigit() }.take(4)
                                persistProfile()
                            },
                            label = { Text("Year") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = trimText,
                            onValueChange = { t -> trimText = t; persistProfile() },
                            label = { Text("Trim") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = calibrationText,
                            onValueChange = { t -> calibrationText = t; persistProfile() },
                            label = { Text("Calibration ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                readingCal = true
                                scope.launch {
                                    val cal = ObdHolder.repository.readCalibrationId()
                                    if (cal != null) {
                                        calibrationText = cal
                                        persistProfile()
                                        message = "Calibration read from the ECU: $cal"
                                    } else {
                                        message = "This ECU didn't publish a CALID (09 04) — type it instead."
                                    }
                                    readingCal = false
                                }
                            },
                            enabled = state.connected && !readingCal,
                        ) { Text(if (readingCal) "Reading…" else "Read") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Stamped on every recorded change so an import can be checked against this " +
                            "truck. Read asks the ECU for 09 04; if nothing answers, type the value.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Column {
                    ExposedDropdownMenuBox(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it },
                    ) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { text ->
                                label = text
                                picked = null
                                menuOpen = true
                                syncSnapshotLabel()
                            },
                            label = { Text("Setting") },
                            placeholder = { Text("Pick one, or type what you changed") },
                            singleLine = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen)
                            },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            val matches = CustomizeCatalogue.search(label)
                            if (matches.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No catalogue match — the text above is used") },
                                    onClick = { menuOpen = false },
                                )
                            }
                            matches.forEach { setting ->
                                DropdownMenuItem(
                                    text = { Text("${setting.category} · ${setting.name}") },
                                    onClick = {
                                        label = setting.name
                                        picked = setting
                                        menuOpen = false
                                        syncSnapshotLabel()
                                    },
                                )
                            }
                        }
                    }
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
                    picked?.values?.takeIf { it.isNotEmpty() }?.let { options ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Known values: ${options.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                val saved = CustomizeSnapshotStore.Snapshot(
                                    at = System.currentTimeMillis(),
                                    readings = results.toList(),
                                    label = composedLabel(),
                                )
                                snapshot = saved
                                CustomizeSnapshotStore.save(context, saved)
                            },
                            enabled = !scanning && results.any { it.supported },
                        ) { Text("Save snapshot") }

                        Spacer(Modifier.size(8.dp))

                        if (snapshot != null) {
                            TextButton(onClick = {
                                snapshot = null
                                label = ""
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
                            "To map a setting: name it, save a snapshot, then change that ONE setting " +
                                "on the factory radio (Menu → Setup → Vehicle Customization) and scan " +
                                "again. The block that moved is that setting."
                        } else {
                            val at = stamp.format(Date(saved.at))
                            "\"${saved.label.ifBlank { "Unnamed change" }}\" · snapshot from $at · " +
                                "${saved.readings.count { it.supported }} blocks. Change the setting " +
                                "on the radio, then scan again."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }


            val snap = snapshot
            if (snap != null && results.isNotEmpty() && !scanning) {
                item {
                    val shown = diff ?: CustomizeExplorer.diff(emptyList(), emptyList())
                    val shownLabel = snap.label.ifBlank { "Unnamed change" }
                    DiffCard(
                        diff = shown,
                        label = shownLabel,
                        onShare = {
                            val text = CustomizeExplorer.describe(
                                diff = shown,
                                savedLabel = "${stamp.format(Date(snap.at))} — $shownLabel",
                                currentLabel = stamp.format(Date()),
                            )
                            share("Tacoma customize diff", text)
                        },
                        onRecord = {
                            val at = System.currentTimeMillis()
                            val added = shown.changes.flatMap { c ->
                                c.byteChanges.map { b ->
                                    ChangeRecord(
                                        label = shownLabel,
                                        header = c.header,
                                        ecuLabel = c.ecuLabel,
                                        lid = c.lid,
                                        byteIndex = b.index,
                                        before = b.before,
                                        after = b.after,
                                        at = at,
                                        year = currentProfile.year,
                                        trim = currentProfile.trim,
                                        calibration = currentProfile.calibration,
                                    )
                                }
                            }
                            if (added.isNotEmpty()) {
                                records.addAll(added)
                                CustomizeRecordStore.save(context, records.toList())
                            }
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
private fun DiffCard(
    diff: SnapshotDiff,
    label: String,
    onShare: () -> Unit,
    onRecord: () -> Unit,
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
                Button(onClick = onRecord) { Text("Save to records") }
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

            diff.changes.forEach { change ->
                Spacer(Modifier.height(10.dp))
                ChangeRow(change)
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
private fun ChangeRow(change: BlockChange) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${change.displayLid}  ·  ${change.ecuLabel}",
                style = MaterialTheme.typography.titleSmall,
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
