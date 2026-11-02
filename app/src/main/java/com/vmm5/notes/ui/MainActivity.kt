package com.vmm5.notes.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import android.content.Intent
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.vmm5.notes.sync.SyncService
import com.vmm5.notes.sync.StickWatcher
import com.vmm5.notes.work.Transcription

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VmmTheme { Root(intent?.action == com.vmm5.notes.ui.RecordWidget.ACTION_RECORD) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == RecordWidget.ACTION_RECORD) setContent { VmmTheme { Root(true) } }
    }
}

private data class Tab(val route: String, val label: String, val on: androidx.compose.ui.graphics.vector.ImageVector, val off: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("notes", "Notes", Icons.Filled.List, Icons.Outlined.List),
    Tab("record", "Record", Icons.Filled.Mic, Icons.Outlined.Mic),
    Tab("activity", "Activity", Icons.Filled.History, Icons.Outlined.History),
    Tab("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun Root(startRecording: Boolean = false) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SyncService.hasPermissions(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = SyncService.hasPermissions(context)
        if (granted) { SyncService.start(context, syncNow = true); StickWatcher.register(context) }
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) { perms += Manifest.permission.BLUETOOTH_SCAN; perms += Manifest.permission.BLUETOOTH_CONNECT }
            else perms += Manifest.permission.ACCESS_FINE_LOCATION
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            ask.launch(perms.toTypedArray())
        } else SyncService.start(context, syncNow = true)
        StickWatcher.register(context)
        Transcription.enqueuePending(context)
    }

    // "Found a stick nearby. Use it with this phone?" (see Pairing).
    val candidate by com.vmm5.notes.sync.Pairing.candidate.collectAsState()
    candidate?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("Found a stick nearby") },
            text = { Text("Stick %04X hasn't been used with a phone yet. Use it with this phone? Its notes will sync here, and other phones will leave it alone.".format(id)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { com.vmm5.notes.sync.Pairing.approve(context, id) }) { Text("Use it") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { com.vmm5.notes.sync.Pairing.reject(context, id) }) { Text("Not mine") } },
        )
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    val selected = route == t.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) t.on else t.off, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = if (startRecording) "record" else "notes", modifier = Modifier.padding(bottom = if (showBar) pad.calculateBottomPadding() else 0.dp)) {
            composable("notes") { NotesScreen(granted, onOpen = { nav.navigate("note/$it") }) }
            composable("record") { RecordScreen(autoStart = startRecording, onDone = { id -> nav.navigate("note/$id") { popUpTo("notes") } }) }
            composable("activity") { ActivityScreen() }
            composable("settings") { SettingsScreen() }
            composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DetailScreen(it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
        }
    }
}

