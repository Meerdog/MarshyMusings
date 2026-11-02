package com.vmm5.notes.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Journal
import com.vmm5.notes.sync.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * A text model that tidies a transcript. One engine today, Gemini Nano
 * through ML Kit's Prompt API (on-device, Pixel 9 and the like); the
 * interface is here so a downloadable llama.cpp model can slot in next
 * to it the way Whisper models do.
 */
interface Cleaner {
    val name: String
    suspend fun ready(): Boolean
    suspend fun clean(prompt: String, transcript: String): String
}

object NanoCleaner : Cleaner {
    override val name = "Gemini Nano"
    private val lock = Mutex()
    /** The last known status, for Settings: null until checked. */
    val status = MutableStateFlow<Int?>(null)
    val downloading = MutableStateFlow<Int?>(null) // percent, or null
    /** The last thing the download reported, in words, for the Settings row. */
    val lastEvent = MutableStateFlow<String?>(null)

    suspend fun check(): Int = try {
        Generation.getClient().checkStatus().also { status.value = it }
    } catch (e: Throwable) { Log.w("Nano", "status", e); FeatureStatus.UNAVAILABLE.also { status.value = it } }

    suspend fun download() {
        val m = Generation.getClient()
        downloading.value = 0
        lastEvent.value = "Asking AICore to download Gemini Nano…"
        try {
            var total = 1L
            m.download().collect { st ->
                when (st) {
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadStarted -> { total = maxOf(1L, st.bytesToDownload); lastEvent.value = "Download started (${st.bytesToDownload / 1_000_000} MB)" }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadProgress -> { downloading.value = (st.totalBytesDownloaded * 100 / total).toInt().coerceIn(0, 100); lastEvent.value = "Downloading, ${st.totalBytesDownloaded / 1_000_000} MB so far" }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadCompleted -> { downloading.value = 100; lastEvent.value = "Download complete" }
                    is com.google.mlkit.genai.common.DownloadStatus.DownloadFailed -> throw IllegalStateException(st.e.message ?: "download failed")
                    else -> {}
                }
            }
            if (lastEvent.value?.startsWith("Asking") == true) lastEvent.value = "AICore accepted the request; it downloads in the background (Wi-Fi, a few minutes). This row updates on its own."
        } catch (e: Throwable) {
            lastEvent.value = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            throw e
        } finally { downloading.value = null; check() }
    }

    override suspend fun ready(): Boolean = check() == FeatureStatus.AVAILABLE

    override suspend fun clean(prompt: String, transcript: String): String = lock.withLock {
        val r = Generation.getClient().generateContent(prompt.trim() + "\n\nVoice note:\n" + transcript.trim())
        r.candidates.firstOrNull()?.text?.trim().orEmpty()
    }

    fun statusLabel(s: Int?): String = when (s) {
        null -> "Not checked yet"
        FeatureStatus.AVAILABLE -> "Ready on this phone"
        FeatureStatus.DOWNLOADABLE -> "Available to download"
        FeatureStatus.DOWNLOADING -> "Downloading"
        else -> "Not available on this phone"
    }
}

object Cleanup {
    val engine: Cleaner = NanoCleaner

    fun enqueue(context: Context, noteId: Long, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(workDataOf("note" to noteId, "force" to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("cleanup-$noteId", ExistingWorkPolicy.REPLACE, req)
    }
}

/** Tidies one note; then (first time only) writes the note into the journals, which waited for it. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("note", -1)
        val force = inputData.getBoolean("force", false)
        val db = Db.get(applicationContext)
        val prefs = Prefs(applicationContext)
        val note = db.notes().get(id) ?: return Result.failure()
        val text = note.transcript ?: return Result.failure()
        val engine = Cleanup.engine
        var cleanedNote = note
        try {
            if (!engine.ready()) throw IllegalStateException("${engine.name} is not ready")
            val t0 = System.currentTimeMillis()
            val out = engine.clean(prefs.cleanupPrompt(), text)
            if (out.isBlank()) throw IllegalStateException("empty answer")
            cleanedNote = note.copy(cleaned = out, cleanedBy = engine.name, cleanMs = System.currentTimeMillis() - t0)
            db.notes().update(cleanedNote)
            db.log("cleaned", "Tidied #${note.number} with ${engine.name} in ${cleanedNote.cleanMs!! / 1000} s" + if (force) " (again)" else "", id)
        } catch (e: Throwable) {
            Log.w("Cleanup", "note $id", e)
            db.log("error", "Could not tidy #${note.number}: ${e.message ?: e.javaClass.simpleName}", id)
            if (runAttemptCount < 2 && !force) return Result.retry()
        }
        if (!cleanedNote.journaled) journal(cleanedNote, db, prefs)
        return Result.success()
    }

    private suspend fun journal(n: com.vmm5.notes.data.Note, db: Db, prefs: Prefs) {
        val journal = Journal(applicationContext)
        journal.append(n, prefs.cleanedInJournal())
        db.notes().update(n.copy(journaled = true))
        val dir = prefs.journalDir()
        if (dir != null && !journal.mirror(dir)) db.log("error", "Could not update musings.md in the chosen folder")
    }
}
