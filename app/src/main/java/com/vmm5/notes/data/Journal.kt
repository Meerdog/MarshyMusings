package com.vmm5.notes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.vmm5.notes.data.bodyText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The running journal: one markdown file, musings.md, that every
 * transcript is appended to in the order it was transcribed, under a
 * heading per day. Deleting a note in the app leaves its entry here; the
 * file is the long record, the notes list is the inbox.
 */
class Journal(private val context: Context) {
    val file: File get() = File(Storage(context).dir, "musings.md")

    /** The per-category journals next to the combined one: Work, Run, Intention, Untagged. */
    fun categoryFile(tagCode: String): File {
        val cat = Tags.of(tagCode).category.ifEmpty { "untagged" }.lowercase()
        return File(Storage(context).dir, "musings-$cat.md")
    }
    val files: List<File> get() = (listOf(file) + listOf("work-proj", "run-run", "int-morn", "rec").map { categoryFile(it) } + listOf(ListCapture.file(context))).filter { it.exists() }

    private fun dayHeading(at: Long) = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date(at))

    @Synchronized
    fun append(note: Note, preferCleaned: Boolean) {
        val tag = Tags.of(note.tag)
        val catName = tag.category.ifEmpty { "Untagged" }
        appendTo(file, note, "# Marshy's Musings\n\nVoice notes from the stick, in the order they were transcribed.\n", preferCleaned)
        appendTo(categoryFile(note.tag), note, "# Marshy's Musings · $catName\n\nOnly the $catName notes, in the order they were transcribed.\n", preferCleaned)
    }

    private fun appendTo(f: File, note: Note, intro: String, preferCleaned: Boolean) {
        val fresh = !f.exists() || f.length() == 0L
        val heading = "## " + dayHeading(note.pulledAt)
        val existing = if (fresh) "" else f.readText()
        val sb = StringBuilder()
        if (fresh) sb.append(intro)
        if (!existing.contains("\n$heading\n") && !existing.startsWith("$heading\n")) sb.append("\n").append(heading).append("\n")
        val tag = Tags.of(note.tag)
        val label = if (tag.category.isEmpty()) "Note" else "${tag.category} · ${tag.sub}"
        val len = "${note.seconds / 60}:${"%02d".format(note.seconds % 60)}"
        val name = note.title?.takeIf { it.isNotBlank() }
        sb.append("\n### ").append(Storage(context).time(note.pulledAt)).append(" · ").append(name ?: label)
            .append(" (").append(if (name != null) "$label, " else "").append("#").append(note.number).append(", ").append(len).append(")\n\n")
        note.place?.let { sb.append("_").append(it).append("_\n\n") }
        sb.append((note.bodyText(preferCleaned) ?: "(no transcript)").trim()).append("\n")
        if (preferCleaned && !note.cleaned.isNullOrBlank()) {
            sb.append("\n<details><summary>as spoken</summary>\n\n").append(note.transcript?.trim() ?: "").append("\n\n</details>\n")
        }
        f.appendText(sb.toString())
    }

    val entries: Int get() = if (file.exists()) file.readText().lineSequence().count { it.startsWith("### ") } else 0

    /** Shares one journal file, or all of them when f is null. */
    fun share(f: File? = null) {
        val list = (if (f != null) listOf(f) else files).filter { it.exists() }
        if (list.isEmpty()) return
        val storage = Storage(context)
        val i = if (list.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, storage.uri(list[0]))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { storage.uri(it) }))
        i.type = "text/markdown"
        i.putExtra(Intent.EXTRA_SUBJECT, if (list.size == 1) list[0].name else "Marshy's Musings journals")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, "Share the journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Rewrites musings.md inside the folder the user picked in Settings
     * (a Google Drive folder, say, which the Drive app then syncs). Done
     * after every entry; failures are logged and the local file stands.
     */
    fun mirror(dirUri: String?): Boolean {
        if (dirUri == null || !file.exists()) return false
        return try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri)) ?: return false
            for (f in files) {
                val doc = dir.findFile(f.name) ?: dir.createFile("text/markdown", f.name) ?: return false
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
            true
        } catch (e: Exception) {
            Log.w("Journal", "mirror failed", e); false
        }
    }

    /** The folder's display name, for Settings. */
    fun folderName(dirUri: String?): String? = dirUri?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }

    /** Writes a copy to a document the user picked (Downloads, Drive, ...). */
    fun copyTo(uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
