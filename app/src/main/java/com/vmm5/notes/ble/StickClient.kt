package com.vmm5.notes.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Talks to the stick's BLE service (src/BlePortal.h in the firmware):
 *
 *   CTL  write   "l" | "g <name>" | "d <name>" | "x"
 *   DAT  notify  "LIST n free b total b\n" "<name> <bytes>\n"... "END\n"
 *                "FILE <name> <bytes>\n" then raw bytes in MTU-sized chunks
 *                "DELETED <name>\n" | "ERR <reason>\n"
 *
 * Plain Android GATT, wrapped in coroutines: every callback is funnelled
 * into one channel of notifications, and the three operations read from it.
 */
@SuppressLint("MissingPermission")
class StickClient(private val context: Context) {
    companion object {
        const val TAG = "StickClient"
        val SERVICE: UUID = UUID.fromString("7a0b0001-5ec5-4a6a-9c1f-0c1d2e3f4a5b")
        /** The advertisement's manufacturer data (firmware 2.12+): test company id 0xFFFF, "M5", flags; bit 0 = new notes. */
        const val MFG_ID = 0xFFFF
        val MFG_HAS_NEW = byteArrayOf('M'.code.toByte(), '5'.code.toByte(), 0x01)
        val MFG_HAS_NEW_MASK = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01)
        val CTL: UUID = UUID.fromString("7a0b0002-5ec5-4a6a-9c1f-0c1d2e3f4a5b")
        val DAT: UUID = UUID.fromString("7a0b0003-5ec5-4a6a-9c1f-0c1d2e3f4a5b")
        val OTA: UUID = UUID.fromString("7a0b0004-5ec5-4a6a-9c1f-0c1d2e3f4a5b")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "VMM5"
    }

    class StickException(msg: String) : IOException(msg)

    data class Entry(val name: String, val bytes: Long)

    /**
     * A stick as heard in its advertisement. [id], [owned]: firmware 2.14+
     * (null / false before). [hasNew]: firmware 2.12+.
     */
    data class StickAd(val device: BluetoothDevice, val id: Int?, val hasNew: Boolean, val owned: Boolean, val rssi: Int) {
        val label: String get() = id?.let { "%04X".format(it) } ?: "(older firmware)"
    }

    private fun parse(result: ScanResult): StickAd {
        val m = result.scanRecord?.getManufacturerSpecificData(MFG_ID)
        val ours = m != null && m.size >= 3 && m[0] == 'M'.code.toByte() && m[1] == '5'.code.toByte()
        val flags = if (ours) m!![2].toInt() and 0xFF else 0
        val id = if (ours && m!!.size >= 5) (m[3].toInt() and 0xFF) or ((m[4].toInt() and 0xFF) shl 8) else null
        return StickAd(result.device, id, flags and 1 != 0, flags and 2 != 0, result.rssi)
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = manager.adapter
    val enabled: Boolean get() = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var ctl: BluetoothGattCharacteristic? = null
    private var dat: BluetoothGattCharacteristic? = null
    private var ota: BluetoothGattCharacteristic? = null
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private var ready = CompletableDeferred<Unit>()
    private var disconnected = CompletableDeferred<Unit>()
    private var writeDone = CompletableDeferred<Unit>()
    var mtu = 23; private set
    val connected: Boolean get() = gatt != null && ready.isCompleted && !disconnected.isCompleted

    /** The first stick seen within [timeoutMs] that [accept] takes, or null. */
    suspend fun scan(timeoutMs: Long, lowPower: Boolean, accept: (StickAd) -> Boolean = { true }): StickAd? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val ad = parse(result)
                        if (cont.isActive && accept(ad)) {
                            scanner.stopScan(this)
                            cont.resume(ad)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "scan failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // A refused MTU request gives no callback: go on with the default.
                if (!g.requestMtu(517)) g.discoverServices()
            } else {
                disconnected.complete(Unit)
                if (!ready.isCompleted) ready.completeExceptionally(StickException("disconnected ($status)"))
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = m
            Log.i(TAG, "mtu $m")
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            val c = svc?.getCharacteristic(CTL)
            val d = svc?.getCharacteristic(DAT)
            if (svc == null || c == null || d == null) {
                ready.completeExceptionally(StickException("VMM5 service not found"))
                return
            }
            ctl = c; dat = d; ota = svc.getCharacteristic(OTA)
            g.setCharacteristicNotification(d, true)
            val cccd = d.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else ready.completeExceptionally(StickException("notifications refused ($status)"))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.complete(Unit)
        }
        // Android 13+ delivers the value; older versions keep it on the characteristic.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(value.copyOf())
        }
        @Deprecated("pre-33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                notifications.trySend(c.value?.copyOf() ?: return)
            }
        }
    }

    /** Every stick heard within [timeoutMs], strongest first: for choosing one. */
    suspend fun scanAll(timeoutMs: Long): List<StickAd> {
        val scanner = adapter?.bluetoothLeScanner ?: return emptyList()
        val found = java.util.concurrent.ConcurrentHashMap<String, StickAd>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { found[result.device.address] = parse(result) }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try { delay(timeoutMs) } finally { runCatching { scanner.stopScan(cb) } }
        return found.values.sortedByDescending { it.rssi }
    }

    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 15000) {
        close()
        ready = CompletableDeferred()
        disconnected = CompletableDeferred()
        while (notifications.tryReceive().isSuccess) { /* drain */ }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (e: Exception) {
            close()
            throw if (e is StickException) e else StickException("connect timed out")
        }
    }

    fun close() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null; ctl = null; dat = null; ota = null
    }

    private suspend fun send(line: String) = write(ctl, line.toByteArray(Charsets.US_ASCII))

    private suspend fun write(target: BluetoothGattCharacteristic?, bytes: ByteArray) {
        val g = gatt ?: throw StickException("not connected")
        val c = target ?: throw StickException("not connected")
        writeDone = CompletableDeferred()
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = bytes
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) throw StickException("write failed")
        withTimeoutOrNull(2000) { writeDone.await() }
    }

    private suspend fun next(timeoutMs: Long = 5000): ByteArray =
        withTimeoutOrNull(timeoutMs) { notifications.receive() } ?: throw StickException("no answer from the stick")

    /** Reads text notifications until [complete] says the answer is whole. */
    private suspend fun ask(line: String, complete: (String) -> Boolean): String = askWithin(line, 5000, complete)

    private suspend fun askWithin(line: String, timeoutMs: Long, complete: (String) -> Boolean): String {
        send(line)
        val sb = StringBuilder()
        while (true) {
            sb.append(String(next(timeoutMs), Charsets.US_ASCII))
            val soFar = sb.toString()
            if (complete(soFar)) return soFar
        }
    }

    private val oneLine: (String) -> Boolean = { it.endsWith("\n") }

    suspend fun list(): List<Entry> {
        val t = ask("l") { it.contains("END\n") }
        return t.split('\n').drop(1).filter { it.isNotBlank() && it != "END" }.mapNotNull { l ->
            val p = l.trim().split(' ')
            if (p.size == 2) Entry(p[0], p[1].toLongOrNull() ?: return@mapNotNull null) else null
        }
    }

    suspend fun get(entry: Entry, progress: (Long, Long) -> Unit = { _, _ -> }): ByteArray {
        val head = ask("g ${entry.name}", oneLine).trim()
        if (!head.startsWith("FILE ")) throw StickException(head)
        val total = head.split(' ')[2].toLong()
        val out = ByteArray(total.toInt())
        var got = 0L
        val t0 = System.nanoTime()
        while (got < total) {
            val chunk = next()
            val n = minOf(chunk.size.toLong(), total - got).toInt()
            System.arraycopy(chunk, 0, out, got.toInt(), n)
            got += n
            progress(got, total)
        }
        val secs = (System.nanoTime() - t0) / 1e9
        Log.i(TAG, "got ${entry.name}: $total bytes in %.1f s (%.0f KB/s)".format(secs, total / 1024 / secs))
        return out
    }

    /** (percent, millivolts), or null if the firmware predates the command. */
    data class Battery(val percent: Int, val millivolts: Int, val charge: String) // charge: bat | chg | full | usb

    /** Percent, millivolts and (firmware 2.8+) the charge state; null if the firmware predates the command. */
    suspend fun battery(): Battery? {
        val t = try { ask("b", oneLine).trim() } catch (e: StickException) { return null }
        val p = t.split(' ')
        if (p.size < 3 || p[0] != "BATT") return null
        return Battery(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: 0, p.getOrNull(3) ?: "bat")
    }

    suspend fun delete(name: String): Boolean {
        val t = ask("d $name", oneLine).trim()
        return t.startsWith("DELETED")
    }

    suspend fun abort() = runCatching { send("x") }

    /**
     * Firmware 2.14+: says which phone this is. "ok" (the stick's phone),
     * "new" (it has none), "other" (another phone's), "denied" (asked on
     * the stick with [claim], and refused); null if the firmware is older.
     * With [claim] on another phone's stick, the stick asks on its screen
     * and the answer can take up to 30 seconds.
     */
    suspend fun hello(phoneId: String, claim: Boolean): String? {
        val t = try { askWithin("h $phoneId" + if (claim) " claim" else "", if (claim) 35_000 else 5000, oneLine).trim() }
            catch (e: StickException) { return null }
        return if (t.startsWith("HELLO ")) t.removePrefix("HELLO ").trim() else null
    }

    /** Firmware 2.12+: tells the stick the phone holds everything up to recording [n]; it clears its "new notes" flag. */
    suspend fun mark(n: Int): Boolean = runCatching { ask("m $n", oneLine).trim().startsWith("MARKED") }.getOrDefault(false)

    /** Firmware 2.12+: the stick's settings, "theme" "bright" "fmt" "synced" -> value; null before 2.12. */
    suspend fun config(): Map<String, Int>? {
        val t = try { ask("c", oneLine).trim() } catch (e: StickException) { return null }
        if (!t.startsWith("CFG ")) return null
        return t.removePrefix("CFG ").split(' ').chunked(2).mapNotNull { kv ->
            if (kv.size == 2) kv[1].toIntOrNull()?.let { kv[0] to it } else null
        }.toMap()
    }

    /** Firmware 2.12+: changes one setting on the stick ("theme", "bright", "fmt"). */
    suspend fun set(key: String, value: Int): Boolean =
        runCatching { ask("s $key $value", oneLine).trim().startsWith("SET") }.getOrDefault(false)

    /** The firmware version, or null before 2.7.0 (no "v" command). */
    suspend fun version(): String? {
        val t = try { ask("v", oneLine).trim() } catch (e: StickException) { return null }
        return if (t.startsWith("VER ")) t.removePrefix("VER ").trim() else null
    }

    /**
     * Pushes a firmware image. "o <size>" opens the spare slot, the bytes
     * go to the OTA characteristic in MTU-sized writes without response,
     * "op" every 16 KB keeps the stick's count within 32 KB of what was
     * sent, and "oe <crc>" closes and reboots it. Throws on any refusal.
     */
    suspend fun update(image: ByteArray, progress: (Int, Int) -> Unit = { _, _ -> }) {
        if (ota == null) throw StickException("this firmware cannot be updated over Bluetooth (flash 2.7.0 over USB once)")
        val ready = ask("o ${image.size}", oneLine).trim()
        if (ready != "OTA ready") throw StickException(ready)
        val chunk = (mtu - 3).coerceIn(20, 244)
        var sent = 0
        var acked = 0
        var sinceAsk = 0
        val crc = java.util.zip.CRC32()
        while (sent < image.size) {
            val n = minOf(chunk, image.size - sent)
            write(ota, image.copyOfRange(sent, sent + n))
            crc.update(image, sent, n)
            sent += n; sinceAsk += n
            if (sinceAsk >= 16 * 1024 || sent == image.size) {
                sinceAsk = 0
                var tries = 0
                while (true) {
                    val p = ask("op", oneLine).trim()
                    acked = p.removePrefix("OTA ").trim().toIntOrNull() ?: throw StickException(p)
                    progress(acked, image.size)
                    if (sent - acked <= 32 * 1024 || sent == image.size && acked == sent) break
                    if (++tries > 50) throw StickException("the stick stopped taking data at $acked of ${image.size}")
                    delay(100)
                }
            }
        }
        val done = ask("oe %08x".format(crc.value), oneLine).trim()
        if (done != "OTA ok") throw StickException(done)
    }

    /** A short pause lets the stick finish a stop before the next command. */
    suspend fun settle() = delay(100)
}
