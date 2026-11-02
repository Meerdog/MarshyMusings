package com.vmm5.notes.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Note
import com.vmm5.notes.data.bodyText
import com.vmm5.notes.data.numberLabel
import com.vmm5.notes.data.Storage
import com.vmm5.notes.data.Tags

/**
 * Hands notes to another app through the share sheet, or straight to the
 * Claude app when it is installed. Several notes go as one text, oldest
 * first (by the stick's number, which is the order they were spoken), each
 * under a header that carries its metadata, so the receiving AI can tell
 * a run thought from an evening reflection and keep them in sequence.
 */
object Share {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    fun claudeInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(CLAUDE_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** The bundle: a short preamble, then one section per note. */
    fun bundle(context: Context, notes: List<Note>, preferCleaned: Boolean = true): String {
        val storage = Storage(context)
        val sorted = notes.sortedBy { it.number }
        val sb = StringBuilder()
        sb.append("Marshy's Musings: ${sorted.size} voice note${if (sorted.size == 1) "" else "s"} from the stick, in the order they were recorded.\n")
        sb.append("Each has a tag (Work / Run / Intention and a sub-tag), its number (#n from the stick, Pn recorded on the phone), and the date it was pulled to the phone or recorded.\n\n")
        for (n in sorted) {
            val tag = Tags.of(n.tag)
            val name = n.title?.takeIf { it.isNotBlank() }
            sb.append("## ").append(name ?: if (tag.category.isEmpty()) "Untagged" else "${tag.category} · ${tag.sub}")
            sb.append(" — ").append(n.numberLabel()).append('\n')
            if (name != null) sb.append("Label: ").append(if (tag.category.isEmpty()) "Untagged" else "${tag.category} · ${tag.sub}").append('\n')
            sb.append("Date: ").append(storage.day(n.pulledAt)).append(' ').append(storage.time(n.pulledAt))
            sb.append(" · Length: ").append(n.seconds / 60).append(':').append("%02d".format(n.seconds % 60))
            sb.append(" · Tag: ").append(n.tag).append('\n')
            if (!tag.category.isEmpty()) sb.append("Category: ").append(tag.category).append(" · Sub-tag: ").append(tag.sub).append('\n')
            if (n.lat != null && n.lon != null) sb.append("Pulled near: ").append(n.place ?: "").append(" (").append(com.vmm5.notes.sync.Where.format(n.lat, n.lon)).append(")\n")
            sb.append('\n').append(n.bodyText(preferCleaned)?.trim() ?: "(not transcribed yet)").append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** Share as text: to Claude directly when asked and installed, else the share sheet. */
    suspend fun text(context: Context, notes: List<Note>, toClaude: Boolean = false) {
        if (notes.isEmpty()) return
        val body = bundle(context, notes, com.vmm5.notes.sync.Prefs(context).cleanedInJournal())
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Marshy's Musings: ${notes.size} voice note${if (notes.size == 1) "" else "s"}")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intent = if (toClaude && claudeInstalled(context)) i.setPackage(CLAUDE_PACKAGE)
        else Intent.createChooser(i, "Share notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val db = Db.get(context)
        db.notes().markShared(notes.map { it.id })
        db.log("shared", "Shared ${notes.size} note${if (notes.size == 1) "" else "s"}" +
            (if (toClaude && claudeInstalled(context)) " to Claude" else "") + ": " + notes.sortedBy { it.number }.joinToString(", ") { "#${it.number}" })
    }

    /** The files themselves: .md transcripts, or the .wav audio. */
    fun files(context: Context, notes: List<Note>, audio: Boolean) {
        val storage = Storage(context)
        val uris = notes.sortedBy { it.number }.mapNotNull { n ->
            val f = if (audio) storage.wav(n.fileId) else storage.md(n.fileId).also { if (!it.exists()) storage.writeMd(n) }
            if (f.exists()) storage.uri(f) else null
        }
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = if (audio) (if (notes.any { storage.wav(it.fileId).name.endsWith(".opus") }) "audio/ogg" else "audio/wav") else "text/markdown"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, if (audio) "Share audio" else "Share files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
