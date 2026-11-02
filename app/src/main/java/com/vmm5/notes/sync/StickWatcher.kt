package com.vmm5.notes.sync

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import com.vmm5.notes.ble.StickClient
import com.vmm5.notes.data.Db
import kotlinx.coroutines.launch

/**
 * The standing watch, the way a bike computer's app wakes when the bike
 * turns on: the OS is handed a scan filter for the stick's service UUID
 * and a pending intent, and keeps scanning on the app's behalf, in the
 * Bluetooth chip, after the app has gone. When the stick is first seen
 * the intent fires, StickSeenReceiver starts the sync service, and the
 * service runs its usual window (pull, rest, watch for more, stop).
 *
 * Needs the scan permission, and is lost when Bluetooth is turned off, so
 * it is registered again on boot, on Bluetooth coming back, and whenever
 * the service stops.
 */
object StickWatcher {
    private const val TAG = "StickWatcher"
    private const val REQUEST = 7

    private fun intent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQUEST, Intent(c, StickSeenReceiver::class.java).setAction(StickSeenReceiver.ACTION),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Volatile var firstMatch = true

    fun register(c: Context) {
        if (!SyncService.hasPermissions(c)) return
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.state != BluetoothAdapter.STATE_ON) return
        val scanner = adapter.bluetoothLeScanner ?: return
        try {
            val pi = intent(c)
            runCatching { scanner.stopScan(pi) }
            // Firmware 2.12+ says in its advertisement whether it holds new
            // notes; the radio then wakes the app only for those. Older
            // firmware: any advertisement (the app finds out on connecting).
            // 2.14+: its own stick's id too, so another phone's stick never
            // wakes the app; with no stick paired yet, a stick that belongs
            // to no phone wakes it (to ask "use it with this phone?").
            val watch = c.getSharedPreferences("watch", Context.MODE_PRIVATE)
            val flag = watch.getBoolean("flag", false)
            val ids = watch.getBoolean("ids", false)
            val paired = Pairing.pairedId(c)
            val m5 = byteArrayOf('M'.code.toByte(), '5'.code.toByte())
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(StickClient.SERVICE)).apply {
                when {
                    ids && paired != null -> setManufacturerData(StickClient.MFG_ID,
                        m5 + byteArrayOf(0x01, (paired and 0xFF).toByte(), (paired shr 8).toByte()),
                        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte()))
                    ids -> setManufacturerData(StickClient.MFG_ID, m5 + byteArrayOf(0x00), byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x02))
                    flag -> setManufacturerData(StickClient.MFG_ID, StickClient.MFG_HAS_NEW, StickClient.MFG_HAS_NEW_MASK)
                }
            }.build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .apply {
                    // Fire once when the stick appears rather than on every
                    // advertisement, where the radio can do that; otherwise
                    // every advertisement, debounced in the receiver.
                    if (firstMatch && adapter.isOffloadedFilteringSupported && adapter.isOffloadedScanBatchingSupported) {
                        setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH or ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                        setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    }
                }
                .build()
            val r = scanner.startScan(listOf(filter), settings, pi)
            Log.i(TAG, "registered (result $r, firstMatch $firstMatch, new-notes filter $flag, stick ${paired?.let { "%04X".format(it) } ?: "none"})")
            if (r != 0 && firstMatch) { firstMatch = false; register(c) }
        } catch (e: Exception) {
            Log.w(TAG, "could not register", e)
        }
    }

    fun unregister(c: Context) {
        val scanner = c.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(intent(c)) }
    }
}

class StickSeenReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.vmm5.notes.STICK_SEEN"
        private const val TAG = "StickSeen"
        private const val DEBOUNCE_MS = 15_000L
        @Volatile private var lastAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val type = intent.getIntExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1)
        if (type == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return
        val now = System.currentTimeMillis()
        if (now - lastAt < DEBOUNCE_MS || SyncService.running.value) return
        lastAt = now
        Log.i(TAG, "stick in range, starting sync")
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                Db.get(context).log("sync", "The phone's radio saw the stick signal new notes; syncing")
                try {
                    SyncService.start(context, auto = true)
                } catch (e: Exception) {
                    // Android 12+ may refuse a foreground service from the
                    // background when the app's battery use is restricted.
                    Log.w(TAG, "could not start the service", e)
                    Db.get(context).log("error", "Android would not let the app start in the background (set battery use to Unrestricted); tap the notification to sync")
                    Notifier.cannotStart(context)
                }
            } finally { pending.finish() }
        }
    }
}
