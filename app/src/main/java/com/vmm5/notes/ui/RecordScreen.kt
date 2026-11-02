package com.vmm5.notes.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.vmm5.notes.audio.PhoneRecorder
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Note
import com.vmm5.notes.data.NoteStatus
import com.vmm5.notes.data.Storage
import com.vmm5.notes.data.Tags
import com.vmm5.notes.sync.Prefs
import com.vmm5.notes.sync.Where
import com.vmm5.notes.work.Transcription
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Record on the phone: one big button, the tag chips above it. The file
 * goes into the same notes folder as the stick's and down the same
 * transcription path. autoStart: opened from the widget or the tile, so
 * recording begins as soon as the microphone permission allows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(autoStart: Boolean, onDone: (Long) -> Unit) {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val prefs = remember { Prefs(context) }
    val storage = remember { Storage(context) }
    val scope = rememberCoroutineScope()
    val recorder = remember { PhoneRecorder(context) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val tag by prefs.phoneTag.collectAsState("rec")
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    var wantStart by remember { mutableStateOf(autoStart) }
    var error by remember { mutableStateOf<String?>(null) }

    fun begin() {
        try {
            val f = File(storage.dir, "phone-${System.currentTimeMillis()}.${PhoneRecorder.extension()}")
            recorder.start(f); recording = true; elapsed = 0; error = null
        } catch (e: Exception) { error = "Could not start recording: ${e.message}" }
    }
    fun finish() {
        val secs = recorder.stop(); recording = false
        val f = recorder.file ?: return
        if (secs == null || secs < 1) { f.delete(); error = "Too short, nothing kept."; return }
        scope.launch {
            val n = prefs.nextPhoneNumber()
            val now = System.currentTimeMillis()
            val fileId = "${storage.day(now)}_P${"%04d".format(n)}_$tag"
            val dest = File(storage.dir, "$fileId.${f.extension}")
            f.renameTo(dest)
            val fix = if (prefs.noteLocation()) Where.lastKnown(context) else null
            val note = Note(number = n, tag = tag, deviceName = "phone-${"%04d".format(n)}.${f.extension}", fileId = fileId, pulledAt = now,
                seconds = secs, bytes = dest.length(), status = NoteStatus.Pending, lat = fix?.lat, lon = fix?.lon, place = fix?.place)
            val id = db.notes().insert(note)
            db.log("recorded", "Recorded ${Tags.label(tag)} P$n on the phone (${secs / 60}:${"%02d".format(secs % 60)})", id)
            Transcription.enqueue(context, id)
            onDone(id)
        }
    }

    LaunchedEffect(granted, wantStart) {
        if (wantStart && granted && !recording) { wantStart = false; begin() }
        if (wantStart && !granted) ask.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(recording) { while (recording) { delay(1000); elapsed++ } }
    DisposableEffect(Unit) { onDispose { if (recorder.running) recorder.cancel() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Record here") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Tag", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tags.all.forEach { t ->
                    FilterChip(selected = tag == t.code, onClick = { scope.launch { prefs.setPhoneTag(t.code) } }, label = { Text(Tags.label(t.code)) }, enabled = !recording)
                }
            }
            Spacer(Modifier.height(36.dp))
            Box(Modifier.size(140.dp).clip(CircleShape).background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .clickable { if (!granted) { wantStart = true; ask.launch(Manifest.permission.RECORD_AUDIO) } else if (recording) finish() else begin() },
                contentAlignment = Alignment.Center) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(64.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(if (recording) "${elapsed / 60}:${"%02d".format(elapsed % 60)}" else "Tap to record", style = MaterialTheme.typography.headlineSmall)
            Text(if (recording) "Tap again to stop. The note is transcribed like one from the stick." else "Same path as the stick: transcribed on the phone, tidied, journaled. Real time and place.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            TangoImage(Tango.Groom, 80.dp, modifier = Modifier.padding(top = 28.dp))
        }
    }
}
