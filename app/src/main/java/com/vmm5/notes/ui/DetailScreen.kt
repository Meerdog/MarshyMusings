package com.vmm5.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vmm5.notes.audio.Player
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.NoteStatus
import com.vmm5.notes.data.Storage
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.LaunchedEffect
import com.vmm5.notes.data.displayTitle
import com.vmm5.notes.data.numberLabel
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.foundation.clickable
import com.vmm5.notes.data.Tags
import com.vmm5.notes.work.Transcription
import com.vmm5.notes.work.TranscribeWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val storage = remember { Storage(context) }
    val note by db.notes().one(id).collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val player = remember { Player() }
    val claude = remember { Share.claudeInstalled(context) }
    var playing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var effect by remember { mutableStateOf(com.vmm5.notes.audio.Effect.Dry) }
    var showRaw by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(note?.status) { while (note?.status == NoteStatus.Transcribing) { kotlinx.coroutines.delay(1000); tick = System.currentTimeMillis() } }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    if (renaming) note?.let { n ->
        var text by remember { mutableStateOf(n.title ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename note") },
            text = { androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Title") },
                placeholder = { Text(Tags.label(n.tag)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { renaming = false; scope.launch { db.notes().update(n.copy(title = text.trim().ifBlank { null })) } }) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.let { "${it.displayTitle()}  ${it.numberLabel()}" } ?: "", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share audio file") }, leadingIcon = { Icon(Icons.Filled.Audiotrack, null) },
                            onClick = { menu = false; note?.let { Share.files(context, listOf(it), audio = true) } })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text(if (note?.cleaned == null) "Tidy up with Gemini Nano" else "Tidy up again") }, leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null) },
                            onClick = { menu = false; note?.let { n -> if (n.status == NoteStatus.Completed) com.vmm5.notes.work.Cleanup.enqueue(context, n.id, force = true) } })
                        DropdownMenuItem(text = { Text("Restart transcription") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { menu = false; note?.let { n -> scope.launch { Transcription.restart(context, n.id) } } })
                        DropdownMenuItem(text = { Text("Delete note", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { pad ->
        val n = note ?: return@Scaffold
        val tag = Tags.of(n.tag)
        Column(Modifier.padding(pad).padding(horizontal = 20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(tag.color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(if (tag.category.isEmpty()) "No tag" else "${tag.category} · ${tag.sub}", style = MaterialTheme.typography.titleMedium)
            }
            Text("${storage.day(n.pulledAt)} ${storage.time(n.pulledAt)} · ${n.seconds / 60}:${"%02d".format(n.seconds % 60)} · ${n.deviceName}" +
                (n.model?.let { " · $it" } ?: "") + (n.transcribeMs?.let { " · transcribed in ${it / 1000} s" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n.title != null) Text("Label: ${Tags.label(n.tag)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n.lat != null && n.lon != null) {
                val where = n.place ?: com.vmm5.notes.sync.Where.format(n.lat, n.lon)
                Text("Pulled near $where", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val uri = android.net.Uri.parse("geo:${n.lat},${n.lon}?q=${n.lat},${n.lon}(Marshy's Musings %23${n.number})")
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                    })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    if (playing) { player.stop(); playing = false }
                    else scope.launch { playing = true; player.play(storage.wav(n.fileId), effect) { playing = false } }
                }) {
                    Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (playing) "Stop" else "Play")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(com.vmm5.notes.audio.Effect.Room to "Room", com.vmm5.notes.audio.Effect.Chorus to "Chorus").forEach { (e, label) ->
                    androidx.compose.material3.FilterChip(selected = effect == e,
                        onClick = { effect = if (effect == e) com.vmm5.notes.audio.Effect.Dry else e; if (playing) { player.stop(); playing = false } },
                        label = { Text(label) })
                }
                if (claude) Button(onClick = { scope.launch { Share.text(context, listOf(n), toClaude = true) } }, enabled = n.status == NoteStatus.Completed) {
                    Icon(Icons.AutoMirrored.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text("To Claude")
                }
                OutlinedButton(onClick = { scope.launch { Share.text(context, listOf(n)) } }, enabled = n.status == NoteStatus.Completed) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }
            HorizontalDivider()
            when (n.status) {
                NoteStatus.Completed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n.cleaned != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.FilterChip(selected = !showRaw, onClick = { showRaw = false }, label = { Text("Tidied") })
                            androidx.compose.material3.FilterChip(selected = showRaw, onClick = { showRaw = true }, label = { Text("As spoken") })
                            Text("${n.cleanedBy ?: ""}${n.cleanMs?.let { " · ${it / 1000} s" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SelectionContainer { Text(if (n.cleaned != null && !showRaw) n.cleaned else n.transcript ?: "", style = MaterialTheme.typography.bodyLarge) }
                }
                NoteStatus.Transcribing -> Column {
                    val since = n.transcribeStartedAt?.let { (maxOf(tick, System.currentTimeMillis()) - it) / 1000 } ?: 0L
                    Text("Transcribing on the phone… ${since / 60}:${"%02d".format(since % 60)}" + (n.progress?.let { " · chunk $it" } ?: ""), color = MaterialTheme.colorScheme.primary)
                    Text("${n.seconds} s of audio; base.en usually takes about a third of that on a recent phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { Transcription.restart(context, n.id) } }) { Text(if (since > 600) "Taking too long — restart" else "Restart") }
                    if (since > 600) Text("If the phone was asleep, the job may have been paused; restarting puts it back at the front of the queue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NoteStatus.Pending -> Text((n.error ?: "Waiting to transcribe") + (n.chunks?.let { " · ${it.split(TranscribeWorker.SEP).size} chunk(s) already done" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                NoteStatus.Failed -> Column {
                    Text("Transcription failed: ${n.error}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { Transcription.restart(context, n.id) } }) { Text("Retry") }
                }
            }
            if (n.sharedAt != null) Text("Shared ${storage.day(n.sharedAt)} ${storage.time(n.sharedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("The audio and transcript are removed from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    note?.let { n -> scope.launch { player.stop(); storage.delete(n.fileId); db.notes().delete(n.id); db.log("deleted", "Deleted ${Tags.label(n.tag)} #${n.number} from the phone"); onBack() } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
