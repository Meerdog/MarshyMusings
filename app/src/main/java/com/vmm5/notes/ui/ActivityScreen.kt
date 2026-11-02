package com.vmm5.notes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Event
import com.vmm5.notes.data.Storage
import kotlinx.coroutines.launch

/** What the app did, newest first, grouped by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val db = remember { Db.get(context) }
    val storage = remember { Storage(context) }
    val events by db.events().recent().collectAsStateWithLifecycle(emptyList())

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Activity") }, actions = {
        // The whole log, with versions and settings, for diagnosing a problem.
        androidx.compose.material3.TextButton(onClick = { scope.launch { com.vmm5.notes.data.Diagnostics.share(context) } }) { Text("Export log") }
    }) }) { pad ->
        if (events.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TangoImage(Tango.Sleep, 100.dp)
                Spacer(Modifier.height(12.dp))
                Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                Text("Pulls, deletions, transcriptions and shares show up here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val byDay = events.groupBy { storage.day(it.at) }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            byDay.forEach { (day, list) ->
                item(key = "d-$day") {
                    Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                items(list.size, key = { list[it].id }) { i -> EventRow(list[i], storage) }
            }
        }
    }
}

@Composable
private fun EventRow(e: Event, storage: Storage) {
    val (icon, tint) = when (e.kind) {
        "pulled" -> Icons.Filled.Bluetooth to MaterialTheme.colorScheme.primary
        "deleted_stick" -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        "transcribed" -> Icons.Filled.Notes to MaterialTheme.colorScheme.tertiary
        "shared" -> Icons.AutoMirrored.Filled.Send to MaterialTheme.colorScheme.secondary
        "deleted" -> Icons.Filled.Delete to MaterialTheme.colorScheme.onSurfaceVariant
        "discarded" -> Icons.Filled.DeleteSweep to MaterialTheme.colorScheme.onSurfaceVariant
        "battery" -> Icons.Filled.BatteryStd to MaterialTheme.colorScheme.onSurfaceVariant
        "error" -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Filled.Sync to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
            Text(storage.time(e.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
