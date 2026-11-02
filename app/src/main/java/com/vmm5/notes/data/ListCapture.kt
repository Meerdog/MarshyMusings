package com.vmm5.notes.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Items spoken with a code phrase ("add to list, buy stamps") become
 * checkbox lines in musings-list.md, next to the journals and mirrored
 * with them. Matching is on the raw Whisper text, loosely: case and
 * punctuation do not matter, the words of the phrase may be separated by
 * anything short, and the item runs to the end of the sentence.
 */
object ListCapture {
    fun extract(transcript: String, phrase: String): List<String> {
        val words = phrase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { Regex.escape(it) }
        if (words.isEmpty()) return emptyList()
        val re = Regex("(?i)\\b" + words.joinToString("[\\W_]{1,3}") + "\\b[\\s,:;\\-]*(.+?)(?=[.!?](?:\\s|$)|\\n|$)")
        return re.findAll(transcript).map { it.groupValues[1].trim().trimEnd('.', ',', ';').trim() }
            .filter { it.length >= 2 }.map { it.replaceFirstChar { c -> c.uppercase() } }.toList()
    }

    fun file(context: Context): File = File(Storage(context).dir, "musings-list.md")

    @Synchronized
    fun append(context: Context, items: List<String>, note: Note) {
        if (items.isEmpty()) return
        val f = file(context)
        val fresh = !f.exists() || f.length() == 0L
        val sb = StringBuilder()
        if (fresh) sb.append("# Marshy's Musings · List\n\nThings said with the code phrase, newest last. Tick them off here.\n\n")
        val day = SimpleDateFormat("MMM d", Locale.US).format(Date(note.pulledAt))
        for (it in items) sb.append("- [ ] ").append(it).append("  <!-- #").append(note.number).append(", ").append(day).append(" -->\n")
        f.appendText(sb.toString())
    }

    fun count(context: Context): Pair<Int, Int> {
        val f = file(context)
        if (!f.exists()) return 0 to 0
        var open = 0; var done = 0
        f.readLines().forEach { if (it.startsWith("- [ ]")) open++ else if (it.startsWith("- [x]", true)) done++ }
        return open to done
    }
}
