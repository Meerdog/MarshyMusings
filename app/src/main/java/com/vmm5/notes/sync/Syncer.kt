package com.vmm5.notes.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import android.util.Log
import com.vmm5.notes.ble.StickClient
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Note
import com.vmm5.notes.data.NoteStatus
import com.vmm5.notes.data.StickWav
import com.vmm5.notes.data.Storage
import com.vmm5.notes.data.Tags
import com.vmm5.notes.data.Transfer
import com.vmm5.notes.data.TransferStatus
import com.vmm5.notes.work.Transcription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI shows about the sync, as a single value. */
data class SyncState(
    val phase: Phase = Phase.Idle,
    val detail: String = "",
    val current: String? = null,    // the file being pulled
    val progress: Float = 0f,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    val bluetoothOff: Boolean = false,
    val stickBattery: Int? = null,
) {
    enum class Phase { Idle, Scanning, Connecting, Pulling, Done, Error }
}

/**
 * One pull: connect to a stick, fetch everything it holds, save, delete
 * from the stick, queue transcription. Mirrors the Pebble app's transfer
 * state machine: a row per file, Started -> Saving -> Completed, or
 * Failed, and orphans from a crash are failed before the next run.
 */
class Syncer(private val context: Context) {
    companion object {
        const val TAG = "Syncer"
        val state = MutableStateFlow(SyncState())
        val stateFlow: StateFlow<SyncState> get() = state
    }

    private val db = Db.get(context)
    private val storage = Storage(context)
    private val prefs = Prefs(context)

    /** Set by the last pull: the service turns it into a notification. */
    var lowBattery = false

    /** Returns how many new notes were pulled, or throws. */
    suspend fun pull(client: StickClient, lowPower: Boolean): Int {
        state.value = state.value.copy(phase = SyncState.Phase.Scanning, detail = "Looking for the stick", lastError = null)
        // Only this phone's stick, or one that belongs to no phone (see Pairing).
        val ad = client.scan(if (lowPower) 20000 else 10000, lowPower) { Pairing.accepts(context, it) }
            ?: run { state.value = state.value.copy(phase = SyncState.Phase.Idle, detail = "No stick in range"); return 0 }
        state.value = state.value.copy(phase = SyncState.Phase.Connecting, detail = "Connecting")
        client.connect(ad.device)
        try {
            db.transfers().failOrphans()
            val ver = client.version() ?: "before 2.7.0"
            if (prefs.stickVersion.first() != ver) { prefs.setStickVersion(ver); db.log("sync", "Stick firmware $ver") }
            // 2.12+: the "new notes" bit (the standing scan uses it from the
            // next registration), settings from the app, the synced mark.
            val modern = firmwareAtLeast(ver, 2, 12)
            context.getSharedPreferences("watch", Context.MODE_PRIVATE).edit()
                .putBoolean("flag", modern).putBoolean("ids", firmwareAtLeast(ver, 2, 14)).apply()
            if (Pairing.handshake(context, client, ad, ver) == Pairing.Outcome.NotOurs) {
                state.value = state.value.copy(phase = SyncState.Phase.Idle, detail = Pairing.status.value ?: "That stick is not this phone's")
                return 0
            }
            if (modern) syncSettings(client)
            client.battery()?.let { b ->
                val pct = b.percent; val mv = b.millivolts
                val before = prefs.stickBattery()
                prefs.setStickBattery(pct, mv, b.charge)
                if (b.charge != "bat") lowBattery = false
                state.value = state.value.copy(stickBattery = pct)
                // A line in the log when it moved by 5 points or crossed into low.
                if (before == null || kotlin.math.abs(before.first - pct) >= 5 || (pct <= 20 && before.first > 20)) {
                    db.log(if (pct <= 20) "error" else "battery", "Stick battery $pct% (${mv} mV)" + if (pct <= 20) " — charge it" else "")
                }
                lowBattery = pct <= 20 && b.charge == "bat"
            }
            val entries = client.list().filter { Tags.parse(it.name) != null }.sortedBy { Tags.parse(it.name)!!.second }
            Log.i(TAG, "${entries.size} recordings on the stick, mtu ${client.mtu}")
            if (entries.isEmpty()) {
                state.value = state.value.copy(phase = SyncState.Phase.Done, detail = "Nothing new on the stick", lastSyncedAt = System.currentTimeMillis())
                return 0
            }
            // Every entry below is either pulled, already held, or skipped as
            // too short; a failure throws. So once the loop is through, the
            // phone has everything up to the highest number on the stick.
            val highest = entries.maxOf { Tags.parse(it.name)!!.second }
            var pulled = 0
            val deleteAfter = prefs.deleteAfterPull()
            entries.forEachIndexed { i, e ->
                val (tag, number) = Tags.parse(e.name)!!
                val existing = db.notes().byDeviceName(e.name)
                if (existing != null && existing.bytes == e.bytes && storage.wav(existing.fileId).length() == e.bytes) {
                    // The same file is already on the phone (a previous run
                    // with delete off, or the delete failed): just tidy the stick.
                    if (deleteAfter && client.delete(e.name)) db.transfers().markDeleted(e.name)
                    return@forEachIndexed
                }
                // A name seen before but a different size: the stick's counter
                // started over (a flash erase wipes it), so this is a new
                // recording that happens to share a name. Pull it; never
                // delete what the phone does not hold.
                if (existing != null) db.log("sync", "${e.name} is a new recording under an old name (stick counter restarted); pulling it")
                state.value = state.value.copy(phase = SyncState.Phase.Pulling, current = e.name, progress = 0f,
                    detail = "Pulling ${i + 1} of ${entries.size}")
                val t = Transfer(e.name, number, tag, e.bytes, TransferStatus.Started)
                db.transfers().upsert(t)
                val bytes = try {
                    client.get(e) { got, total -> state.value = state.value.copy(progress = got.toFloat() / total) }
                } catch (ex: Exception) {
                    db.transfers().upsert(t.copy(status = TransferStatus.Failed, error = ex.message))
                    throw ex
                }
                if (!StickWav.looksRight(bytes, e.bytes)) {
                    db.transfers().upsert(t.copy(status = TransferStatus.Failed, error = "incomplete: ${bytes.size} of ${e.bytes}"))
                    db.log("error", "${e.name}: transfer incomplete (${bytes.size} of ${e.bytes} bytes), kept on the stick")
                    throw StickClient.StickException("${e.name}: transfer incomplete, kept on the stick")
                }
                if (StickWav.tooShort(bytes)) {
                    // Under a second of audio: not worth a note. Clear it from the stick.
                    db.transfers().upsert(t.copy(status = TransferStatus.Discarded))
                    db.log("discarded", "${e.name} was under a second long; skipped")
                    if (deleteAfter) client.delete(e.name)
                    return@forEachIndexed
                }
                db.transfers().upsert(t.copy(status = TransferStatus.Saving))
                val now = System.currentTimeMillis()
                var fileId = storage.fileIdFor(number, tag, now)
                var dup = 1
                while (storage.wav(fileId).exists()) fileId = storage.fileIdFor(number, tag, now) + "_" + (++dup)
                storage.writeWav(fileId, bytes)
                val fix = if (prefs.noteLocation()) Where.lastKnown(context) else null
                val note = Note(number = number, tag = tag, deviceName = e.name, fileId = fileId, pulledAt = now,
                    seconds = StickWav.seconds(bytes), bytes = e.bytes, status = NoteStatus.Pending,
                    lat = fix?.lat, lon = fix?.lon, place = fix?.place)
                val id = db.completeTransfer(t, note)
                db.log("pulled", "Pulled ${Tags.label(tag)} #$number (${note.seconds / 60}:${"%02d".format(note.seconds % 60)})", id)
                pulled++
                if (deleteAfter) {
                    client.settle()
                    if (client.delete(e.name)) { db.transfers().markDeleted(e.name); db.log("deleted_stick", "Removed ${e.name} from the stick", id) }
                    else { Log.w(TAG, "${e.name} stayed on the stick"); db.log("error", "${e.name} could not be removed from the stick", id) }
                }
                Transcription.enqueue(context, id)
            }
            if (modern) {
                if (client.mark(highest)) Log.i(TAG, "marked synced up to #$highest")
                else db.log("error", "The stick did not take the synced mark (#$highest); it may wake the app again")
            }
            state.value = state.value.copy(phase = SyncState.Phase.Done, current = null, progress = 0f,
                detail = if (pulled == 0) "Nothing new on the stick" else "$pulled new note${if (pulled == 1) "" else "s"}",
                lastSyncedAt = System.currentTimeMillis())
            if (pulled > 0) db.log("sync", "Sync done: $pulled new note${if (pulled == 1) "" else "s"}")
            return pulled
        } finally {
            client.close()
        }
    }

    /** Sends settings chosen in the app, then reads back what the stick has. */
    private suspend fun syncSettings(client: StickClient) {
        val want = prefs.wantedStickSettings()
        val sent = mutableListOf<String>()
        var ok = true
        want.theme?.let { if (client.set("theme", it)) sent += "theme " + Prefs.STICK_THEMES.getOrElse(it) { "$it" } else ok = false }
        want.bright?.let { if (client.set("bright", it)) sent += "screen " + Prefs.STICK_BRIGHTNESS.getOrElse(it) { "$it" } else ok = false }
        want.fmt?.let { if (client.set("fmt", it)) sent += "format " + Prefs.STICK_FORMATS.getOrElse(it) { "$it" } else ok = false }
        if (ok) prefs.clearWantedStick()
        if (sent.isNotEmpty()) db.log("sync", "Stick settings sent: " + sent.joinToString(", "))
        if (!ok) db.log("error", "The stick refused a setting; it stays queued")
        client.config()?.let { c ->
            val t = c["theme"]; val b = c["bright"]; val f = c["fmt"]
            if (t != null && b != null && f != null) prefs.setStickSettings(t, b, f)
        }
    }
}
