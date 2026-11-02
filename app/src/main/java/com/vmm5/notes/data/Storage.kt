package com.vmm5.notes.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the notes live on the phone: filesDir/notes/<fileId>.wav (the
 * stick's file, untouched) and <fileId>.md (the transcript with its
 * header). Nothing leaves the phone unless the user shares it.
 */
class Storage(private val context: Context) {
    val dir: File get() = File(context.filesDir, "notes").also { it.mkdirs() }

    /** The audio file for a note: .opus (the stick's default since 2.7) or .wav, whichever is there. */
    fun wav(fileId: String): File {
        for (ext in listOf("opus", "m4a")) { val f = File(dir, "$fileId.$ext"); if (f.exists()) return f }
        return File(dir, "$fileId.wav")
    }
    fun audioMime(fileId: String) = if (wav(fileId).name.endsWith(".opus")) "audio/ogg" else "audio/wav"
    fun md(fileId: String) = File(dir, "$fileId.md")

    /** 2026-10-25_0042_run-thought, like tools/pull.py on the PC. */
    fun fileIdFor(number: Int, tag: String, at: Long = System.currentTimeMillis()): String =
        "${day(at)}_${"%04d".format(number)}_$tag"

    fun day(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
    fun time(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    fun clock(at: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(at))

    /** Today / Yesterday / "Friday, October 23" for the list's day headers. */
    fun dayLabel(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = day(at)
        if (d == day(now)) return "Today"
        if (d == day(now - 86_400_000L)) return "Yesterday"
        val fmt = if (SimpleDateFormat("yyyy", Locale.US).format(Date(at)) == SimpleDateFormat("yyyy", Locale.US).format(Date(now)))
            "EEEE, MMMM d" else "EEEE, MMMM d, yyyy"
        return SimpleDateFormat(fmt, Locale.US).format(Date(at))
    }

    fun writeWav(fileId: String, bytes: ByteArray): File {
        val ext = if (StickWav.isOgg(bytes)) "opus" else "wav"
        val f = File(dir, "$fileId.$ext")
        val tmp = File(dir, "$fileId.$ext.part")
        tmp.writeBytes(bytes)
        // Read back before trusting it: the stick's copy is deleted after this.
        check(tmp.readBytes().contentEquals(bytes)) { "file did not read back" }
        check(tmp.renameTo(f) || f.exists()) { "rename failed" }
        return f
    }

    fun markdown(note: Note): String {
        val tag = Tags.of(note.tag)
        val head = buildString {
            append("# ")
            append(if (tag.category.isEmpty()) "Note" else "${tag.category} · ${tag.sub}")
            append(" #").append(note.number).append('\n')
            append("- date: ").append(day(note.pulledAt)).append(' ').append(time(note.pulledAt)).append(" (pulled)\n")
            append("- tag: ").append(note.tag).append('\n')
            append("- length: ").append(note.seconds / 60).append(':').append("%02d".format(note.seconds % 60)).append('\n')
            append("- source: ").append(note.deviceName).append('\n')
            if (note.model != null) append("- transcribed by: ").append(note.model).append('\n')
            append('\n')
        }
        return head + (note.transcript?.trim() ?: "(no transcript)") + "\n"
    }

    fun writeMd(note: Note): File = md(note.fileId).also { it.writeText(markdown(note)) }

    fun delete(fileId: String) {
        for (ext in listOf("opus", "m4a", "wav")) File(dir, "$fileId.$ext").delete()
        md(fileId).delete()
    }

    fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
