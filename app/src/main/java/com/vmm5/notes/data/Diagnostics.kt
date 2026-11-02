package com.vmm5.notes.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vmm5.notes.BuildConfig
import com.vmm5.notes.sync.Prefs
import com.vmm5.notes.whisper.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text diagnostic log to send when something needs looking into:
 * the phone and app versions, the stick's last report, the settings, the
 * activity log, the transfers, a line per note (status, timings, errors;
 * no transcripts, no locations) and the app's own recent system log.
 * Shared through the usual share sheet, so it goes wherever the user
 * sends it and nowhere else.
 */
object Diagnostics {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun build(c: Context): File = withContext(Dispatchers.IO) {
        val db = Db.get(c)
        val prefs = Prefs(c)
        val sb = StringBuilder()
        fun t(at: Long?) = at?.let { stamp.format(Date(it)) } ?: "-"
        sb.append("Marshy's Musings diagnostic log\n")
        sb.append("written ").append(t(System.currentTimeMillis())).append('\n')
        sb.append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        sb.append("phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("models on the phone: ").append(Models.all.filter { Models.isDownloaded(c, it) }.joinToString { it.id }.ifEmpty { "none" }).append('\n')
        val bundled = runCatching { c.assets.open("firmware.txt").bufferedReader().readText().trim() }.getOrNull()
        sb.append("firmware carried by the app: ").append(bundled ?: "-")
            .append(", loaded file: ").append(com.vmm5.notes.sync.FirmwareFile.loadedVersion(c) ?: "-").append('\n')

        sb.append("\n== Settings\n").append(prefs.dump()).append('\n')

        val notes = db.notes().snapshot()
        sb.append("\n== Notes (").append(notes.size).append(")\n")
        notes.groupBy { it.status }.forEach { (st, l) -> sb.append(st).append(": ").append(l.size).append('\n') }
        notes.take(300).forEach { n ->
            sb.append("#").append(n.number).append(' ').append(n.deviceName)
                .append(" pulled ").append(t(n.pulledAt))
                .append(' ').append(n.seconds).append("s ").append(n.bytes).append("B ")
                .append(n.status).append(" tries ").append(n.attempts)
                .append(n.model?.let { " $it" } ?: "")
                .append(n.transcribeMs?.let { " ${it / 1000}s" } ?: "")
                .append(n.cleanedBy?.let { " tidied by $it" } ?: "")
                .append(n.error?.let { " ERROR $it" } ?: "")
                .append('\n')
        }

        sb.append("\n== Transfers\n")
        db.transfers().latest().forEach { x ->
            sb.append(t(x.createdAt)).append(' ').append(x.deviceName).append(' ').append(x.bytes).append("B ")
                .append(x.status).append(if (x.deletedFromStick) " deleted-from-stick" else "")
                .append(x.error?.let { " ERROR $it" } ?: "").append('\n')
        }

        sb.append("\n== Activity (newest first)\n")
        db.events().latest(2000).forEach { e ->
            sb.append(t(e.at)).append(" [").append(e.kind).append("] ").append(e.text).append('\n')
        }

        sb.append("\n== System log (this app only)\n")
        sb.append(logcat()).append('\n')

        val dir = File(c.cacheDir, "share").also { it.mkdirs() }
        val name = "marshys-musings-log-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".txt"
        File(dir, name).also { it.writeText(sb.toString()) }
    }

    /** The app's own lines from the system log (Android lets an app read only its own). */
    private suspend fun logcat(): String = withTimeoutOrNull(5000) {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "3000", "--pid", android.os.Process.myPid().toString()))
            p.inputStream.bufferedReader().readText().also { p.destroy() }
        }.getOrElse { "(could not read: ${it.message})" }
    } ?: "(timed out)"

    suspend fun share(c: Context) {
        val f = build(c)
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, Storage(c).uri(f))
            .putExtra(Intent.EXTRA_SUBJECT, "Marshy's Musings diagnostic log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Send the diagnostic log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
