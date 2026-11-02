package com.vmm5.notes.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Whisper models (ggml format, from the whisper.cpp release files on
 * Hugging Face), downloaded once into filesDir/models. Sizes are
 * approximate. base.en is the default: a two-minute note transcribes in
 * about its own length on a mid-range phone; tiny.en is 2-3x faster and
 * noticeably sloppier; small.en is better and 3x slower.
 */
data class WhisperModel(val id: String, val label: String, val megabytes: Int) {
    val fileName get() = "ggml-$id.bin"
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

object Models {
    val all = listOf(
        WhisperModel("tiny.en", "Tiny (fast, rough)", 75),
        WhisperModel("base.en", "Base (recommended)", 142),
        WhisperModel("small.en", "Small (best, slow)", 466),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[1]

    /** 0..1 while a download runs, null otherwise. */
    val downloading = MutableStateFlow<Pair<String, Float>?>(null)

    fun dir(c: Context) = File(c.filesDir, "models").also { it.mkdirs() }
    fun file(c: Context, m: WhisperModel) = File(dir(c), m.fileName)
    fun isDownloaded(c: Context, m: WhisperModel) = file(c, m).let { it.exists() && it.length() > 1_000_000 }

    suspend fun download(c: Context, m: WhisperModel) = withContext(Dispatchers.IO) {
        if (isDownloaded(c, m)) return@withContext
        val target = file(c, m)
        val tmp = File(dir(c), "${m.fileName}.part")
        downloading.value = m.id to 0f
        try {
            val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            }
            if (conn.responseCode / 100 != 2) throw IllegalStateException("HTTP ${conn.responseCode} from huggingface.co")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.megabytes * 1_000_000L)
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        downloading.value = m.id to (got.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            check(tmp.length() > 1_000_000) { "download too small" }
            check(tmp.renameTo(target)) { "could not move the model into place" }
            Log.i("Models", "downloaded ${m.fileName}: ${target.length()} bytes")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            downloading.value = null
        }
    }

    fun delete(c: Context, m: WhisperModel) = file(c, m).delete()
}
