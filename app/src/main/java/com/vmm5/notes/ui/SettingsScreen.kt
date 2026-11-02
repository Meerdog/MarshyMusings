package com.vmm5.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.vmm5.notes.BuildConfig
import com.vmm5.notes.R
import com.vmm5.notes.data.Journal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vmm5.notes.sync.Prefs
import com.vmm5.notes.sync.SyncService
import com.vmm5.notes.whisper.Models
import com.vmm5.notes.work.Transcription
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val deleteAfter by prefs.deleteAfterPull.collectAsState(true)
    val background by prefs.backgroundSync.collectAsState(true)
    val modelId by prefs.model.collectAsState(Prefs.DEFAULT_MODEL)
    val downloading by Models.downloading.collectAsState()
    var downloadError by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TangoImage(Tango.Lounge, 64.dp)
                if (!LocalMinimal.current) Spacer(Modifier.width(14.dp))
                Column {
                    Text("Marshy's Musings", style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!LocalMinimal.current) Text("Named for Tango, an orange cat of some standing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Version " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section("Stick")
            val pairedStick by com.vmm5.notes.sync.Pairing.paired.collectAsState()
            val pairStatus by com.vmm5.notes.sync.Pairing.status.collectAsState()
            var choosing by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(pairedStick?.let { "Paired stick: %04X".format(it) } ?: "No stick paired yet") },
                supportingContent = { Text((pairStatus?.let { "$it. " } ?: "") +
                    "This phone syncs only with its own stick, and other phones leave that stick alone (firmware 2.14+). " +
                    "The id is on the stick's Settings screen. To hand a stick on, forget the phone there.") },
                trailingContent = { TextButton(onClick = { choosing = true }) { Text("Change") } },
            )
            if (choosing) StickPicker(pairedStick) { choosing = false }
            val batt by prefs.stickBattery.collectAsState(null)
            val charge by prefs.stickCharge.collectAsState("bat")
            ListItem(
                headlineContent = { Text(when (charge) { "chg" -> "Battery: charging"; "full" -> "Battery: full"; "usb" -> "Battery: on USB power"; else -> batt?.let { "Battery ${it.first}%" } ?: "Battery: not read yet" }) },
                supportingContent = { Text(batt?.let { "${it.second} mV, read ${ago(it.third)}." + (if (charge != "bat") " The percentage is held at ${it.first}% until it is unplugged." else " About 3–4 hours of use on a charge; it powers itself off after 10 idle minutes.") }
                    ?: "Reported on every sync (firmware 2.3.0 or later).") },
            )
            val stickVersion by prefs.stickVersion.collectAsState(null)
            var fwTick by remember { mutableStateOf(0) }
            val bundled = remember { com.vmm5.notes.sync.Updater.bundledVersion(context) }
            val loaded = remember(fwTick) { com.vmm5.notes.sync.FirmwareFile.loadedVersion(context) }
            val target = remember(fwTick) { com.vmm5.notes.sync.Updater.targetVersion(context) }
            var fwMessage by remember { mutableStateOf<String?>(null) }
            val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    fwMessage = try {
                        val v = com.vmm5.notes.sync.FirmwareFile.load(context, uri)
                        scope.launch { com.vmm5.notes.data.Db.get(context).log("sync", "Loaded firmware file $v") }
                        "Loaded firmware $v." + if (bundled != null && com.vmm5.notes.sync.compareVersions(v, bundled) <= 0) " It is not newer than the $bundled this app carries, so Update still sends $bundled." else ""
                    } catch (e: Exception) { "That file was not loaded: ${e.message}" }
                    fwTick++
                }
            }
            val upd by com.vmm5.notes.sync.Updater.progress.collectAsState()
            val canUpdate = target != null && stickVersion != null && stickVersion != target
            ListItem(
                headlineContent = { Text("Firmware: " + (stickVersion ?: "not read yet") + (target?.let { " · update available: $it" }?.takeIf { canUpdate } ?: "")) },
                supportingContent = { Text(when {
                    upd != null && upd!!.error != null -> upd!!.phase + ": " + upd!!.error
                    upd != null && upd!!.total > 0 -> "${upd!!.phase}… ${upd!!.done * 100 / upd!!.total}%"
                    upd != null -> upd!!.phase
                    stickVersion == "before 2.7.0" -> "This stick needs one USB flash of 2.7.0 (new flash layout); after that updates come from here."
                    canUpdate -> "Hold the stick sideways near the phone and tap Update. Takes a minute or two; the stick restarts by itself."
                    else -> "Up to date. New firmware arrives with app updates." }) },
                trailingContent = { if (canUpdate && stickVersion != "before 2.7.0" && (upd == null || upd!!.error != null || upd!!.phase.startsWith("Sent")))
                    TextButton(onClick = { scope.launch { com.vmm5.notes.sync.Updater.run(context) } }) { Text("Update") } },
            )
            upd?.takeIf { it.total > 0 && it.error == null }?.let { LinearProgressIndicator(progress = { it.done.toFloat() / it.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            ListItem(
                headlineContent = { Text("Firmware files") },
                supportingContent = { Text((fwMessage?.let { "$it\n" } ?: "") +
                    "This app carries ${bundled ?: "none"}" + (loaded?.let { "; loaded file: $it" } ?: "") +
                    ". A newer firmware can be shared as a .bin file (the -app.bin or the full image) and loaded here; Update then sends the newest of the two.") },
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { pickFirmware.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Load firmware file") }
                if (loaded != null) TextButton(onClick = { com.vmm5.notes.sync.FirmwareFile.remove(context); fwMessage = null; fwTick++ }) { Text("Remove loaded file") }
            }
            ListItem(
                headlineContent = { Text("Sync in the background") },
                supportingContent = { Text("Keep watching for the stick while the app is closed") },
                trailingContent = { Switch(checked = background, onCheckedChange = { v -> scope.launch { prefs.setBackgroundSync(v); if (v) SyncService.start(context) } }) },
            )
            val scanAlways by prefs.scanAlways.collectAsState(false)
            ListItem(
                headlineContent = { Text("Keep looking all day") },
                supportingContent = { Text(if (scanAlways) "The phone scans for the stick continuously." else "Off: the phone's radio watches for the stick on its own and wakes the app when it appears; the app then looks for 10 minutes and goes quiet again. If wake-ups stop, set this app's battery use to Unrestricted in Android settings.") },
                trailingContent = { Switch(checked = scanAlways, onCheckedChange = { v -> scope.launch { prefs.setScanAlways(v); SyncService.start(context) } }) },
            )
            ListItem(
                headlineContent = { Text("Delete from the stick after pulling") },
                supportingContent = { Text("Each file is removed from the stick once its copy on the phone has been checked. Off: the stick keeps everything; the phone skips what it already has.") },
                trailingContent = { Switch(checked = deleteAfter, onCheckedChange = { v -> scope.launch { prefs.setDeleteAfterPull(v) } }) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Stick settings")
            val have by prefs.stickSettings.collectAsState(Prefs.StickSettings(null, null, null))
            val want by prefs.wantedStickSettings.collectAsState(Prefs.StickSettings(null, null, null))
            val modern = com.vmm5.notes.sync.firmwareAtLeast(stickVersion, 2, 12)
            ListItem(
                headlineContent = { Text("Change the stick from here") },
                supportingContent = { Text(when {
                    !modern -> "Needs firmware 2.12 or later on the stick (update it above first)."
                    want.theme != null || want.bright != null || want.fmt != null -> "Waiting to send. Turn the stick on near the phone and tap Send now, or it goes with the next sync."
                    have.theme == null -> "The stick's settings are read on the next sync."
                    else -> "Matches the stick. Changes are sent on the next sync, or now with Send now." }) },
                trailingContent = { if (modern) TextButton(onClick = { SyncService.start(context, syncNow = true) }) { Text("Send now") } },
            )
            if (modern) {
                ChoiceItem("Theme", Prefs.STICK_THEMES, want.theme ?: have.theme, want.theme != null) { v -> scope.launch { prefs.wantStick(theme = v) } }
                ChoiceItem("Screen brightness", Prefs.STICK_BRIGHTNESS, want.bright ?: have.bright, want.bright != null) { v -> scope.launch { prefs.wantStick(bright = v) } }
                ChoiceItem("Recording format", Prefs.STICK_FORMATS, want.fmt ?: have.fmt, want.fmt != null) { v -> scope.launch { prefs.wantStick(fmt = v) } }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Look")
            val style by prefs.appStyle.collectAsState(0)
            ChoiceItem("App look", listOf("Tango", "Minimal"), style, false,
                supporting = "Tango: warm colours and the pixel cat. Minimal: neutral greys, no cat.") { v -> scope.launch { prefs.setAppStyle(v) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Notifications")
            val notifySynced by prefs.notifySynced.collectAsState(true)
            val maxPerHour by prefs.notifyMaxPerHour.collectAsState(3)
            val notifyBattery by prefs.notifyBattery.collectAsState(false)
            val quietStatus by prefs.quietStatus.collectAsState(true)
            ListItem(
                headlineContent = { Text("When notes are synced") },
                supportingContent = { Text("One notification for a run of syncs: notes that come off the stick within half an hour of the last ones add to it. The stick only wakes the app when it has something new (firmware 2.12+).") },
                trailingContent = { Switch(checked = notifySynced, onCheckedChange = { v -> scope.launch { prefs.setNotifySynced(v) } }) },
            )
            if (notifySynced) {
                val caps = listOf(1, 2, 3, 5, Int.MAX_VALUE)
                ChoiceItem("Sound at most", caps.map { if (it == Int.MAX_VALUE) "Every time" else "$it time${if (it == 1) "" else "s"} an hour" },
                    caps.indexOf(maxPerHour).takeIf { it >= 0 }, false,
                    supporting = "Past that the notification updates without a sound.") { i -> scope.launch { prefs.setNotifyMaxPerHour(caps[i]) } }
            }
            ListItem(
                headlineContent = { Text("When the stick's battery is low") },
                trailingContent = { Switch(checked = notifyBattery, onCheckedChange = { v -> scope.launch { prefs.setNotifyBattery(v) } }) },
            )
            ListItem(
                headlineContent = { Text("Minimise the working status") },
                supportingContent = { Text("Android requires a notification while the app syncs or transcribes. On: it is tucked away with no status bar icon. Off: shown normally.") },
                trailingContent = { Switch(checked = quietStatus, onCheckedChange = { v -> scope.launch { prefs.setQuietStatus(v) } }) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Journal")
            val journal = remember { Journal(context) }
            val entries = remember(tick) { journal.entries }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
                if (uri != null) runCatching { journal.copyTo(uri) }
            }
            ListItem(
                headlineContent = { Text("musings.md") },
                supportingContent = { Text(if (entries == 0) "Every transcript is added here as it is made, under the day's heading. Deleting a note in the app does not remove it from the journal."
                    else "$entries entr${if (entries == 1) "y" else "ies"}, ${(journal.file.length() + 512) / 1024} KB. Every transcript is added as it is made; deleting a note leaves its entry here.") },
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { journal.share(journal.file) }, enabled = entries > 0) { Text("Share") }
                TextButton(onClick = { save.launch("musings.md") }, enabled = entries > 0) { Text("Save a copy") }
                TextButton(onClick = { journal.share() }, enabled = entries > 0) { Text("Share all") }
            }
            journal.files.filter { it != journal.file }.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    supportingContent = { Text("Only the ${f.nameWithoutExtension.removePrefix("musings-")} notes, same format. Hand this one to an AI when you only want that thread.") },
                    trailingContent = { TextButton(onClick = { journal.share(f) }) { Text("Share") } },
                )
            }
            val journalDir by prefs.journalDir.collectAsState(null)
            val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    scope.launch { prefs.setJournalDir(uri.toString()); journal.mirror(uri.toString()) }
                }
            }
            ListItem(
                modifier = Modifier.clickable { pickDir.launch(null) },
                headlineContent = { Text(if (journalDir == null) "Keep a copy in a folder" else "Copy kept in: " + (journal.folderName(journalDir) ?: "chosen folder")) },
                supportingContent = { Text(if (journalDir == null) "Pick a folder once — a Google Drive folder works — and musings.md plus the per-category files are rewritten there after every entry, so Drive syncs them for you."
                    else "Rewritten after every entry. Tap to pick a different folder.") },
                trailingContent = { if (journalDir != null) TextButton(onClick = { scope.launch { prefs.setJournalDir(null) } }) { Text("Stop") } },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("List")
            val listOn by prefs.listEnabled.collectAsState(true)
            val savedPhrase by prefs.listPhrase.collectAsState(Prefs.DEFAULT_LIST_PHRASE)
            var phraseText by remember(savedPhrase) { mutableStateOf(savedPhrase) }
            val listCounts = remember(tick) { com.vmm5.notes.data.ListCapture.count(context) }
            ListItem(
                headlineContent = { Text("Code phrase → musings-list.md") },
                supportingContent = { Text("Say \"$savedPhrase\" and the rest of the sentence becomes a checkbox line in musings-list.md (next to the journals, mirrored to your folder). Matched on the words as spoken, before any tidy-up. ${listCounts.first} open, ${listCounts.second} ticked.") },
                trailingContent = { Switch(checked = listOn, onCheckedChange = { v -> scope.launch { prefs.setListEnabled(v) } }) },
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(value = phraseText, onValueChange = { phraseText = it }, label = { Text("Phrase") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { prefs.setListPhrase(phraseText) } }, enabled = phraseText.trim() != savedPhrase) { Text("Save") }
                TextButton(onClick = { journal.share(com.vmm5.notes.data.ListCapture.file(context)) }, enabled = listCounts.first + listCounts.second > 0) { Text("Share") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Where")
            val noteLocation by prefs.noteLocation.collectAsState(false)
            var locTick by remember { mutableStateOf(0) }
            val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locTick++ }
            val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locTick++ }
            val locAllowed = remember(locTick) { com.vmm5.notes.sync.Where.allowed(context) }
            val bgAllowed = remember(locTick) { com.vmm5.notes.sync.Where.allowedInBackground(context) }
            ListItem(
                headlineContent = { Text("Note where the phone was") },
                supportingContent = { Text(when {
                    !noteLocation -> "Each pulled note gets the phone's last known position and a place name. It is where the phone was when it pulled, not where you spoke."
                    !locAllowed -> "Turned on, but the app has no location permission yet."
                    !bgAllowed -> "Pulls happen in the background, so location needs \"Allow all the time\". Tap below to set it."
                    else -> "On. Uses the phone's last known position; no extra GPS use." }) },
                trailingContent = { Switch(checked = noteLocation, onCheckedChange = { v -> scope.launch { prefs.setNoteLocation(v) }
                    if (v && !com.vmm5.notes.sync.Where.allowed(context)) askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }) },
            )
            if (noteLocation && locAllowed && !bgAllowed && android.os.Build.VERSION.SDK_INT >= 29) Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { askBackground.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow location all the time") }
            }
            if (noteLocation && !locAllowed) Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Grant location") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Tidy up (on-device model)")
            val cleanup by prefs.cleanup.collectAsState(false)
            val nanoStatus by com.vmm5.notes.work.NanoCleaner.status.collectAsState()
            val nanoDl by com.vmm5.notes.work.NanoCleaner.downloading.collectAsState()
            val nanoEvent by com.vmm5.notes.work.NanoCleaner.lastEvent.collectAsState()
            // Re-check every few seconds until it is ready: AICore downloads on its own schedule.
            LaunchedEffect(nanoStatus) {
                com.vmm5.notes.work.NanoCleaner.check()
                while (com.vmm5.notes.work.NanoCleaner.status.value != com.google.mlkit.genai.common.FeatureStatus.AVAILABLE) {
                    kotlinx.coroutines.delay(5000); com.vmm5.notes.work.NanoCleaner.check()
                }
            }
            ListItem(
                headlineContent = { Text("Gemini Nano: " + com.vmm5.notes.work.NanoCleaner.statusLabel(nanoStatus)) },
                supportingContent = { Text(listOfNotNull(
                    nanoEvent,
                    when (nanoStatus) {
                        com.google.mlkit.genai.common.FeatureStatus.AVAILABLE -> "Runs on the phone through Android's AICore; nothing leaves the device."
                        com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> "Tap Download. AICore fetches the model in the background, usually on Wi-Fi; this row keeps checking."
                        com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> "AICore is downloading. Keep Wi-Fi on; this row keeps checking."
                        null -> "Checking…"
                        else -> "AICore says not available. Make sure the Android AICore app is up to date in Play Store, then restart the phone; it can take a while after a reset for AICore to fetch its configuration."
                    }).joinToString("\n")) },
                trailingContent = {
                    if (nanoStatus == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE && nanoDl == null)
                        TextButton(onClick = { scope.launch { runCatching { com.vmm5.notes.work.NanoCleaner.download() } } }) { Text("Download") }
                    else TextButton(onClick = { scope.launch { com.vmm5.notes.work.NanoCleaner.check() } }) { Text("Check") }
                },
            )
            ListItem(
                headlineContent = { Text("Tidy each transcript") },
                supportingContent = { Text("After Whisper, the model rewrites the note with the prompt below. The raw transcript is always kept; any note can be tidied again from its ⋮ menu.") },
                trailingContent = { Switch(checked = cleanup, onCheckedChange = { v -> scope.launch { prefs.setCleanup(v) } }) },
            )
            val cleanedInJournal by prefs.cleanedInJournal.collectAsState(true)
            ListItem(
                headlineContent = { Text("Use the tidied text in journals and shares") },
                supportingContent = { Text("Off: journals and shares carry the words as spoken. The journal also keeps the spoken version folded under the tidied one.") },
                trailingContent = { Switch(checked = cleanedInJournal, onCheckedChange = { v -> scope.launch { prefs.setCleanedInJournal(v) } }) },
            )
            val savedPrompt by prefs.cleanupPrompt.collectAsState(Prefs.DEFAULT_CLEANUP_PROMPT)
            var promptText by remember(savedPrompt) { mutableStateOf(savedPrompt) }
            androidx.compose.material3.OutlinedTextField(value = promptText, onValueChange = { promptText = it }, label = { Text("Prompt") }, minLines = 4,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { scope.launch { prefs.setCleanupPrompt(promptText) } }, enabled = promptText != savedPrompt) { Text("Save prompt") }
                TextButton(onClick = { scope.launch { prefs.setCleanupPrompt("") } }) { Text("Reset to default") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Transcription")
            Text("Runs on the phone with Whisper; nothing is uploaded. The model downloads once (Wi-Fi recommended). Tap a model to use it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Models.all.forEach { m ->
                val have = remember(tick, downloading) { Models.isDownloaded(context, m) }
                val selected = m.id == modelId
                Column {
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { scope.launch { prefs.setModel(m.id) } },
                        leadingContent = { Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                        headlineContent = { Text(m.label) },
                        supportingContent = { Text("${m.megabytes} MB · " + if (have) "downloaded" else "not downloaded") },
                        trailingContent = {
                            if (!have && downloading == null) TextButton(onClick = {
                                downloadError = null
                                scope.launch {
                                    try { Models.download(context, m); tick++; Transcription.enqueuePending(context) }
                                    catch (e: Exception) { downloadError = e.message ?: "download failed" }
                                }
                            }) { Text("Download") }
                            else if (have) TextButton(onClick = { Models.delete(context, m); tick++ }) { Text("Remove") }
                        },
                    )
                    downloading?.takeIf { it.first == m.id }?.let { (_, p) ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Help")
            var credits by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Send a diagnostic log") },
                supportingContent = { Text("Versions, settings, the activity log, transfers and a line per note (status, timings, errors), plus the app's recent system log. No transcripts, audio or locations; file names and list items in the activity log are included. Goes only where you share it.") },
                trailingContent = { TextButton(onClick = { scope.launch { com.vmm5.notes.data.Diagnostics.share(context) } }) { Text("Export") } },
            )
            ListItem(
                modifier = Modifier.clickable { credits = true },
                headlineContent = { Text("Credits and licences") },
                supportingContent = { Text("The open-source code this app is built on, and where its ideas came from.") },
            )
            if (credits) CreditsDialog { credits = false }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("How it works")
            Text(
                "Sideways, the stick is in Bluetooth mode (grey BT by the battery; cyan while the phone is connected). " +
                "KEY2 shows what is on the stick; hold KEY2 for Settings (theme, screen, web mode). " +
                "Recordings are Ogg Opus by default (small, 16 kHz); the picker's last entry switches to WAV when a file has to play anywhere.\n\n" +
                "Notes are kept in this app's private storage until you delete them. Long-press a note to select several, then share, send to Claude, or delete.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

/** A setting with a few named values: tap to choose from a menu. [pending]: chosen here, not yet on the stick. */
@Composable
private fun ChoiceItem(title: String, options: List<String>, selected: Int?, pending: Boolean,
                       supporting: String? = null, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            headlineContent = { Text(title) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = { Text((selected?.let { options.getOrNull(it) } ?: "–") + if (pending) " (to send)" else "",
                color = if (pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/** The NOTICE.md carried in the app's assets, as plain text in a scrolling dialog. */
@Composable
private fun CreditsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val notice = remember { runCatching { context.assets.open("NOTICE.md").bufferedReader().readText() }.getOrDefault("(missing)") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Credits and licences") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(notice.replace("**", "").replace("`", ""), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** Nearby sticks, to pair this phone with one of them. */
@Composable
private fun StickPicker(paired: Int?, onClose: () -> Unit) {
    val context = LocalContext.current
    var sticks by remember { mutableStateOf<List<com.vmm5.notes.ble.StickClient.StickAd>?>(null) }
    LaunchedEffect(Unit) { sticks = runCatching { com.vmm5.notes.ble.StickClient(context).scanAll(6000) }.getOrDefault(emptyList()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Choose a stick") },
        text = {
            Column {
                when {
                    sticks == null -> { Text("Looking for sticks nearby…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                    sticks!!.isEmpty() -> Text("No sticks found. Turn yours on (sideways, not recording) and hold it near the phone. One that is busy syncing will not show; try again in a moment.")
                    else -> sticks!!.forEach { ad ->
                        val usable = ad.id != null
                        ListItem(
                            modifier = Modifier.clickable(enabled = usable && ad.id != paired) {
                                com.vmm5.notes.sync.Pairing.approve(context, ad.id!!); onClose()
                            },
                            headlineContent = { Text("Stick " + ad.label) },
                            supportingContent = { Text(when {
                                !usable -> "Older firmware: update it to 2.14 first"
                                ad.id == paired -> "This phone's"
                                ad.owned -> "Another phone's. It will ask on its screen (KEY1 allows)."
                                else -> "No phone yet"
                            }) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (paired != null) TextButton(onClick = { com.vmm5.notes.sync.Pairing.unpair(context); onClose() }) { Text("Unpair") } },
    )
}

