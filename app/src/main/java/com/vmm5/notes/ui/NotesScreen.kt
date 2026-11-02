package com.vmm5.notes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Note
import com.vmm5.notes.data.NoteStatus
import com.vmm5.notes.data.Storage
import com.vmm5.notes.data.Tags
import com.vmm5.notes.data.displayTitle
import com.vmm5.notes.data.numberLabel
import com.vmm5.notes.data.Transfer
import com.vmm5.notes.sync.SyncService
import com.vmm5.notes.sync.SyncState
import com.vmm5.notes.sync.Syncer
import kotlinx.coroutines.launch

/** The feed: sync status, tag filters, the notes; long-press selects. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(granted: Boolean, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val storage = remember { Storage(context) }
    val notes by db.notes().all().collectAsStateWithLifecycle(emptyList())
    val pending by db.transfers().pending().collectAsStateWithLifecycle(emptyList())
    val unshared by db.notes().unsharedCount().collectAsStateWithLifecycle(0)
    val sync by Syncer.stateFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val claude = remember { Share.claudeInstalled(context) }

    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    val shown = notes.filter {
        when (filter) {
            "All" -> true
            "Unshared" -> it.sharedAt == null
            else -> Tags.of(it.tag).category == filter
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Cancel") } },
                    actions = {
                        IconButton(onClick = { selected = shown.map { it.id }.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        if (claude) IconButton(onClick = { scope.launch { Share.text(context, db.notes().byIds(selected.toList()), toClaude = true); selected = emptySet() } }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send to Claude")
                        }
                        IconButton(onClick = { scope.launch { Share.text(context, db.notes().byIds(selected.toList())); selected = emptySet() } }) { Icon(Icons.Filled.Share, "Share") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    expandedHeight = 88.dp,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            TangoImage(Tango.Face, 48.dp)
                            if (!LocalMinimal.current) Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Marshy's Musings", style = MaterialTheme.typography.headlineSmall)
                                SyncLine(sync, granted)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { SyncService.start(context, syncNow = true) }, enabled = granted) { Icon(Icons.Filled.Sync, "Sync now") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting && unshared > 0) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { Share.text(context, db.notes().unshared(), toClaude = claude) } },
                    icon = { Icon(if (claude) Icons.AutoMirrored.Filled.Send else Icons.Filled.Share, null) },
                    text = { Text(if (claude) "Send $unshared new to Claude" else "Share $unshared new") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (sync.phase == SyncState.Phase.Pulling) {
                LinearProgressIndicator(progress = { sync.progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Work", "Run", "Intention", "Unshared").forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) },
                        leadingIcon = if (f in listOf("Work", "Run", "Intention")) ({ Box(Modifier.size(10.dp).background(Tags.all.first { it.category == f }.color, CircleShape)) }) else null)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pending, key = { "t-" + it.deviceName }) { TransferRow(it, sync) }
                if (shown.isEmpty() && pending.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            TangoImage(if (notes.isEmpty()) Tango.Sit else Tango.Loaf, 120.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(if (notes.isEmpty()) "Nothing to muse on yet" else "Nothing matches this filter", style = MaterialTheme.typography.titleMedium)
                            if (notes.isEmpty()) Text(if (LocalMinimal.current) "Record on the stick and keep it near the phone; the notes come across on their own."
                                else "Record on the stick and keep it sideways near the phone; Tango fetches the notes on his own.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
                        }
                    }
                }
                shown.groupBy { storage.day(it.pulledAt) }.forEach { (day, dayNotes) ->
                    item(key = "day-$day") {
                        Text(storage.dayLabel(dayNotes.first().pulledAt), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    items(dayNotes, key = { it.id }) { n ->
                        NoteRow(n, storage, selected = n.id in selected, selecting = selecting,
                            onClick = { if (selecting) selected = if (n.id in selected) selected - n.id else selected + n.id else onOpen(n.id) },
                            onLongClick = { selected = if (n.id in selected) selected - n.id else selected + n.id })
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} note${if (selected.size == 1) "" else "s"}?") },
            text = { Text("The audio and transcript are removed from the phone. The stick's copy was already deleted when it was pulled.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList(); confirmDelete = false; selected = emptySet()
                    scope.launch {
                        db.notes().byIds(ids).forEach { storage.delete(it.fileId); db.notes().delete(it.id) }
                        db.log("deleted", "Deleted ${ids.size} note${if (ids.size == 1) "" else "s"} from the phone")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SyncLine(s: SyncState, granted: Boolean) {
    val (icon, text, tint) = when {
        !granted -> Triple(Icons.Filled.BluetoothDisabled, "Bluetooth permission needed", MaterialTheme.colorScheme.error)
        s.bluetoothOff -> Triple(Icons.Filled.BluetoothDisabled, "Bluetooth is off", MaterialTheme.colorScheme.error)
        s.phase == SyncState.Phase.Pulling -> Triple(Icons.Filled.Bluetooth, s.detail + (s.current?.let { " · $it" } ?: ""), MaterialTheme.colorScheme.primary)
        s.phase == SyncState.Phase.Scanning || s.phase == SyncState.Phase.Connecting -> Triple(Icons.Filled.Bluetooth, s.detail, MaterialTheme.colorScheme.onSurfaceVariant)
        s.phase == SyncState.Phase.Error -> Triple(Icons.Filled.ErrorOutline, s.lastError ?: "Sync failed", MaterialTheme.colorScheme.error)
        s.detail.startsWith("Paused") -> Triple(Icons.Filled.BluetoothDisabled, s.detail, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(Icons.Filled.Bluetooth, (s.lastSyncedAt?.let { "Last synced ${ago(it)}" } ?: "Watching for the stick") +
            (s.stickBattery?.let { " · stick $it%" } ?: ""), if (s.stickBattery != null && s.stickBattery <= 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}

@Composable
fun TransferRow(t: Transfer, s: SyncState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TangoImage(Tango.Stretch, 28.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${Tags.label(t.tag)}  #${t.number}", fontWeight = FontWeight.SemiBold)
                Text(if (s.current == t.deviceName) "Fetching…" else "Waiting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRow(n: Note, storage: Storage, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val tag = Tags.of(n.tag)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (selecting) {
                Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(tag.color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(n.displayTitle(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(n.numberLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(storage.clock(n.pulledAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("${n.seconds / 60}:${"%02d".format(n.seconds % 60)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(6.dp))
                when (n.status) {
                    NoteStatus.Completed -> Text(n.cleaned ?: n.transcript ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    NoteStatus.Transcribing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text("Transcribing…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    NoteStatus.Pending -> Text(n.error ?: "Waiting to transcribe", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NoteStatus.Failed -> Text("Transcription failed: ${n.error}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("pulled " + storage.clock(n.pulledAt).lowercase() + (if (n.title != null) " · " + Tags.label(n.tag) else "") +
                        (n.transcribeMs?.let { " · transcribed in ${it / 1000} s" } ?: "") + (n.place?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    if (n.sharedAt != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("shared", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

