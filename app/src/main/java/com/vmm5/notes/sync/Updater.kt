package com.vmm5.notes.sync

import android.content.Context
import android.util.Log
import com.vmm5.notes.ble.StickClient
import com.vmm5.notes.data.Db
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pushes firmware to the stick over Bluetooth: the newer of the one this
 * app carries (assets/firmware.bin, version in assets/firmware.txt) and a
 * firmware file loaded into the app (FirmwareFile), so the stick can be
 * updated from a .bin shared on its own. The sync service is
 * stopped for the duration so the two do not fight over the connection,
 * and started again after.
 */
object Updater {
    data class Progress(val phase: String, val done: Int = 0, val total: Int = 0, val error: String? = null)
    val progress = MutableStateFlow<Progress?>(null)

    fun bundledVersion(c: Context): String? = runCatching { c.assets.open("firmware.txt").bufferedReader().readText().trim() }.getOrNull()

    /** The version Update would send: the newer of the bundled and the loaded one. */
    fun targetVersion(c: Context): String? {
        val b = bundledVersion(c); val l = FirmwareFile.loadedVersion(c)
        return when {
            b == null -> l
            l == null -> b
            compareVersions(l, b) > 0 -> l
            else -> b
        }
    }

    private fun targetImage(c: Context): ByteArray? {
        val l = FirmwareFile.loadedVersion(c)
        val b = bundledVersion(c)
        return if (l != null && (b == null || compareVersions(l, b) > 0)) FirmwareFile.loadedImage(c)
        else runCatching { c.assets.open("firmware.bin").readBytes() }.getOrNull()
    }

    suspend fun run(c: Context) {
        val db = Db.get(c)
        val image = targetImage(c) ?: run {
            progress.value = Progress("No firmware to send", error = "none"); return
        }
        val version = targetVersion(c) ?: "?"
        SyncService.stop(c)
        delay(1500)
        val client = StickClient(c)
        try {
            progress.value = Progress("Looking for the stick")
            val ad = client.scan(20_000, lowPower = false) { Pairing.accepts(c, it) }
                ?: throw StickClient.StickException("No stick in range. Hold it sideways near the phone.")
            progress.value = Progress("Connecting")
            client.connect(ad.device)
            val before = client.version() ?: "before 2.7.0"
            if (Pairing.handshake(c, client, ad, before) == Pairing.Outcome.NotOurs)
                throw StickClient.StickException("That stick is not paired with this phone.")
            db.log("sync", "Updating the stick from $before to $version (${image.size / 1024} KB)")
            progress.value = Progress("Sending $version", 0, image.size)
            client.update(image) { done, total -> progress.value = Progress("Sending $version", done, total) }
            progress.value = Progress("Sent. The stick is restarting into $version")
            db.log("sync", "Stick updated to $version; it is restarting")
            Prefs(c).setStickVersion(version)
        } catch (e: Exception) {
            Log.w("Updater", "update failed", e)
            db.log("error", "Firmware update failed: ${e.message}")
            progress.value = Progress("Update failed", error = e.message ?: "unknown")
        } finally {
            client.close()
            delay(3000)
            SyncService.start(c)
        }
    }
}
