package com.vmm5.notes.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vmm5.notes.R
import com.vmm5.notes.ble.StickClient
import com.vmm5.notes.data.Db
import com.vmm5.notes.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The background sync, after the Pebble app's connected-device service:
 * a foreground service that loops scan -> connect -> pull -> rest, so the
 * notes come off the stick whenever it is in range, app open or not.
 *
 * The stick advertises all the time it is idle, so after a successful pull
 * the loop rests for a while rather than reconnecting at once; a "Sync
 * now" from the UI cuts the rest short.
 *
 * Started by the phone's radio (ACTION_AUTO: firmware 2.12+ advertises a
 * "new notes" bit and the standing scan filters on it), the service pulls
 * once and stops: no ten-minute window, no lingering status. Opened from
 * the app or Sync now, it keeps the window as before.
 */
class SyncService : Service() {
    companion object {
        const val TAG = "SyncService"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_NOTES = "notes"
        const val ACTION_SYNC_NOW = "com.vmm5.notes.SYNC_NOW"
        const val ACTION_STOP = "com.vmm5.notes.STOP"
        const val ACTION_AUTO = "com.vmm5.notes.AUTO"
        private const val REST_AFTER_PULL_MS = 60_000L
        private const val REST_AFTER_NOTHING_MS = 20_000L
        private const val REST_AFTER_ERROR_MS = 15_000L

        val running = MutableStateFlow(false)

        fun hasPermissions(c: Context): Boolean {
            val needed = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return needed.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
        }

        fun start(c: Context, syncNow: Boolean = false, auto: Boolean = false) {
            if (!hasPermissions(c)) return
            val i = Intent(c, SyncService::class.java).apply { action = if (auto) ACTION_AUTO else if (syncNow) ACTION_SYNC_NOW else null }
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) = c.startService(Intent(c, SyncService::class.java).setAction(ACTION_STOP))

        fun channels(c: Context) = Notifier.channels(c)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeNow = false
    private var warnedLow = false
    // Woken by the stick's "new notes" bit and nothing else asked for the
    // service since: pull once, then stop.
    private var autoOnly = false
    private var autoTries = 0
    private var statusChannel = Notifier.CHANNEL_STATUS_QUIET
    // The scanning window: the phone looks for the stick until this time,
    // then stops and takes the notification down. Opening the app, Sync
    // now, or a pull opens it again. (The stick powers itself off after 10
    // idle minutes, so scanning all day finds nothing anyway.)
    private var windowUntil = 0L
    private lateinit var client: StickClient
    private lateinit var syncer: Syncer

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            Syncer.state.value = Syncer.state.value.copy(bluetoothOff = st != BluetoothAdapter.STATE_ON)
            if (st == BluetoothAdapter.STATE_ON) wakeNow = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        channels(this)
        client = StickClient(this)
        syncer = Syncer(this)
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        statusChannel = kotlinx.coroutines.runBlocking { Notifier.statusChannel(this@SyncService) }
        val idle = loop == null || loop?.isActive != true
        if (intent?.action == ACTION_AUTO) {
            if (idle) { autoOnly = true; autoTries = 0 }
        } else {
            autoOnly = false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Where.allowed(this) && Where.allowedInBackground(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, notification(if (autoOnly) "Syncing new notes" else "Watching for the stick"), type)
        } else {
            startForeground(1, notification(if (autoOnly) "Syncing new notes" else "Watching for the stick"))
        }
        running.value = true
        // While the service itself scans, the radio's standing watch is
        // paused; it is set again when the service stops.
        StickWatcher.unregister(this)
        windowUntil = System.currentTimeMillis() + Prefs.SCAN_WINDOW_MS
        if (intent?.action == ACTION_SYNC_NOW || intent?.action == ACTION_AUTO) wakeNow = true
        if (loop == null || loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val prefs = Prefs(this)
        while (scope.isActive) {
            val manual = wakeNow
            wakeNow = false
            if (!manual && !prefs.backgroundSync()) {
                Syncer.state.value = Syncer.state.value.copy(phase = SyncState.Phase.Idle, detail = "Background sync is off")
                update("Background sync is off")
                waitFor(REST_AFTER_NOTHING_MS); continue
            }
            if (!client.enabled) {
                Syncer.state.value = Syncer.state.value.copy(phase = SyncState.Phase.Idle, detail = "Bluetooth is off", bluetoothOff = true)
                update("Bluetooth is off")
                waitFor(REST_AFTER_NOTHING_MS); continue
            }
            if (!manual && !prefs.scanAlways() && System.currentTimeMillis() > windowUntil) {
                // Window over: stop looking until the app is opened again.
                Syncer.state.value = Syncer.state.value.copy(phase = SyncState.Phase.Idle, detail = "Paused — open the app or tap Sync to look again")
                Db.get(this).log("sync", "Stopped looking after 10 quiet minutes; the phone's radio will wake the app when the stick is next in range")
                StickWatcher.register(this)
                stopSelf()
                return
            }
            Syncer.state.value = Syncer.state.value.copy(bluetoothOff = false)
            update("Looking for the stick")
            val rest = try {
                val pulled = syncer.pull(client, lowPower = !manual)
                if (pulled > 0) { Notifier.synced(this, pulled); windowUntil = System.currentTimeMillis() + Prefs.SCAN_WINDOW_MS }
                if (syncer.lowBattery && !warnedLow) { warnedLow = true; Notifier.lowBattery(this) }
                if (!syncer.lowBattery) warnedLow = false
                if (autoOnly && !prefs.scanAlways()) {
                    // Woken for new notes and they are in: done until the stick has more.
                    Db.get(this).log("sync", if (pulled > 0) "Synced on the stick's signal; going quiet until it has more"
                        else "The stick signalled but had nothing new; going quiet")
                    stopSelf()
                    return
                }
                update(if (pulled > 0) "Pulled $pulled" else "Watching for the stick")
                if (pulled > 0 || Syncer.state.value.phase == SyncState.Phase.Done) REST_AFTER_PULL_MS else REST_AFTER_NOTHING_MS
            } catch (e: Exception) {
                if (autoOnly && ++autoTries >= 3) {
                    // Keep the window open after repeated failures: the stick
                    // still signals, and the user may want to look.
                    autoOnly = false
                }
                Log.w(TAG, "pull failed", e)
                client.close()
                Db.get(this).log("error", "Sync failed: ${e.message}")
                Syncer.state.value = Syncer.state.value.copy(phase = SyncState.Phase.Error, current = null,
                    detail = e.message ?: "pull failed", lastError = e.message)
                update("Pull failed: ${e.message}")
                REST_AFTER_ERROR_MS
            }
            waitFor(rest)
        }
    }

    /** Sleeps, but a Sync now cuts it short. */
    private suspend fun waitFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !wakeNow) delay(500)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sync = PendingIntent.getService(this, 1, Intent(this, SyncService::class.java).setAction(ACTION_SYNC_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, statusChannel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Marshy's Musings")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Sync now", sync)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    override fun onDestroy() {
        running.value = false
        StickWatcher.register(this)
        runCatching { unregisterReceiver(btReceiver) }
        loop?.cancel()
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON)) {
            StickWatcher.register(context)
        }
    }
}
