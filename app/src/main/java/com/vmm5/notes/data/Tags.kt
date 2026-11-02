package com.vmm5.notes.data

import androidx.compose.ui.graphics.Color

/** The tag table, mirroring lib/Tags in the firmware. */
data class Tag(val code: String, val category: String, val sub: String, val color: Color)

object Tags {
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF30D158)
    val Amber = Color(0xFFFFD60A)
    val Grey = Color(0xFF8E8E93)

    val all: List<Tag> = listOf(
        Tag("rec", "", "No tag", Grey),
        Tag("work-proj", "Work", "Project", Blue),
        Tag("work-upd", "Work", "Update", Blue),
        Tag("work-ai", "Work", "AI", Blue),
        Tag("run-place", "Run", "Place", Green),
        Tag("run-thought", "Run", "Thought", Green),
        Tag("run-run", "Run", "Running", Green),
        Tag("int-morn", "Intention", "Morning", Amber),
        Tag("int-mid", "Intention", "Midday", Amber),
        Tag("int-eve", "Intention", "Evening", Amber),
    )
    private val byCode = all.associateBy { it.code }

    fun of(code: String): Tag = byCode[code.lowercase()] ?: all[0]

    /** "Run · Thought", or "No tag". */
    fun label(code: String): String {
        val t = of(code)
        return if (t.category.isEmpty()) t.sub else "${t.category} · ${t.sub}"
    }

    private val name = Regex("^(REC|rec|work-proj|work-upd|work-ai|run-place|run-thought|run-run|int-morn|int-mid|int-eve)_(\\d{4})\\.(wav|opus)$")

    /** (code, number) from a stick file name, or null if it is not a recording. */
    fun parse(fileName: String): Pair<String, Int>? {
        val m = name.matchEntire(fileName) ?: return null
        val number = m.groupValues[2].toInt()
        if (number == 0) return null
        return m.groupValues[1].lowercase() to number
    }
}

/** IMA ADPCM WAV as the stick writes it: 60-byte header, 256-byte blocks of 505 samples at 8 kHz. */
object StickWav {
    const val HEADER = 60
    const val BLOCK = 256
    const val SAMPLES_PER_BLOCK = 505
    const val RATE = 8000

    fun seconds(bytes: Long, rate: Int = RATE): Int = ((maxOf(0L, bytes - HEADER) / BLOCK) * SAMPLES_PER_BLOCK / rate).toInt()

    /** The sample rate in the header (8000, or 16000 for the stick's Hi-Fi mode). */
    fun rate(wav: ByteArray): Int {
        if (wav.size < 28) return RATE
        val r = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or ((wav[26].toInt() and 0xFF) shl 16)
        return if (r in 4000..48000) r else RATE
    }

    fun looksRight(data: ByteArray, expected: Long): Boolean =
        data.size.toLong() == expected && (isOgg(data) || (data.size >= HEADER &&
            String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(data, 8, 4, Charsets.US_ASCII) == "WAVE" &&
            String(data, 52, 4, Charsets.US_ASCII) == "data"))

    fun isOgg(data: ByteArray) = data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "OggS"

    /** Seconds of audio in a stick file of either format: WAV from its size, Ogg Opus from its last page's granule. */
    fun seconds(data: ByteArray): Int = if (isOgg(data)) OggOpus.seconds(data) else seconds(data.size.toLong(), rate(data))

    /** Too short to keep: under a second of audio in either format. */
    fun tooShort(data: ByteArray): Boolean = if (isOgg(data)) OggOpus.seconds(data) < 1 && data.size < 2500 else data.size <= HEADER + 4 * BLOCK
}

/** Just enough Ogg to find the stream's length: the granule (48 kHz samples) on the last page. */
object OggOpus {
    fun seconds(data: ByteArray): Int {
        var last = -1
        var i = data.size - 27
        while (i >= 0) {
            if (data[i] == 'O'.code.toByte() && data[i + 1] == 'g'.code.toByte() && data[i + 2] == 'g'.code.toByte() && data[i + 3] == 'S'.code.toByte()) { last = i; break }
            i--
        }
        if (last < 0) return 0
        var g = 0L
        for (b in 7 downTo 0) g = (g shl 8) or (data[last + 6 + b].toLong() and 0xFF)
        return (g / 48000).toInt()
    }
}
