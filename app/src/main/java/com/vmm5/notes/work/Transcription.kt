package com.vmm5.notes.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vmm5.notes.audio.ImaAdpcm
import com.vmm5.notes.data.Db
import com.vmm5.notes.data.Journal
import com.vmm5.notes.data.StickWav
import com.vmm5.notes.data.NoteStatus
import com.vmm5.notes.data.Storage
import com.vmm5.notes.sync.Prefs
import com.vmm5.notes.whisper.Models
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * The processing queue, after the Pebble app's RecordingProcessingQueue
 * but on WorkManager: one job per note, persisted, retried with backoff
 * (three attempts), surviving the app being killed. The stage is the
 * note's status: Pending -> Transcribing -> Completed, or Failed with an
 * error the detail screen shows next to a Retry.
 */
object Transcription {
    fun enqueue(context: Context, noteId: Long, replace: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf("note" to noteId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$noteId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Start over on a note, now: the queued or running job is replaced. A
     * running whisper pass cannot be interrupted mid-call, so the new job
     * waits for it and then runs; the old result is thrown away.
     */
    suspend fun restart(context: Context, noteId: Long) {
        val db = Db.get(context)
        val n = db.notes().get(noteId) ?: return
        db.notes().update(n.copy(status = NoteStatus.Pending, attempts = 0, error = null, transcribeStartedAt = null, progress = null))
        val kept = n.chunks?.split(TranscribeWorker.SEP)?.size ?: 0
        db.log("sync", "Restarting transcription of #${n.number}" + if (kept > 0) " (resuming after $kept finished chunk${if (kept == 1) "" else "s"})" else "", noteId)
        enqueue(context, noteId, replace = true)
    }

    /** Anything left Pending (for example before the model was downloaded). */
    suspend fun enqueuePending(context: Context) {
        Db.get(context).notes().needingTranscription().forEach { enqueue(context, it.id) }
    }
}

class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "Transcribe"
        const val CHANNEL = "transcribe"
        const val SEP = "\u001F"
        // whisper.cpp contexts are not thread-safe and the model is big: one at a time.
        private val lock = Mutex()
        private var loaded: Pair<String, WhisperContext>? = null

        private suspend fun context(path: String): WhisperContext {
            loaded?.let { if (it.first == path) return it.second else it.second.release() }
            val ctx = WhisperContext.createContextFromFile(path)
            loaded = path to ctx
            return ctx
        }

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }

    private fun notification(text: String, channel: String): android.app.Notification {
        com.vmm5.notes.sync.Notifier.channels(applicationContext)
        return androidx.core.app.NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(com.vmm5.notes.R.drawable.ic_notify).setContentTitle("Marshy's Musings").setContentText(text)
            .setOngoing(true).setSilent(true).build()
    }

    /** Runs as a foreground job so Android does not stop it at the 10-minute background limit. */
    private suspend fun foreground(text: String) {
        try {
            val ch = com.vmm5.notes.sync.Notifier.statusChannel(applicationContext, transcribing = true)
            val info = if (android.os.Build.VERSION.SDK_INT >= 29)
                androidx.work.ForegroundInfo(7, notification(text, ch), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else androidx.work.ForegroundInfo(7, notification(text, ch))
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground: ${describe(e)}") // restricted app: carry on, chunks still resume
        }
    }

    private fun stopReasonText(): String = if (android.os.Build.VERSION.SDK_INT >= 31) {
        when (val r = stopReason) {
            androidx.work.WorkInfo.STOP_REASON_TIMEOUT -> "timeout (10-minute background limit)"
            androidx.work.WorkInfo.STOP_REASON_DEVICE_STATE -> "device state (battery saver or doze)"
            androidx.work.WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery low"
            androidx.work.WorkInfo.STOP_REASON_PREEMPT -> "pre-empted by other work"
            androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app (restart)"
            androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED -> "not stopped"
            else -> "reason $r"
        }
    } else "unknown (older Android)"

    override suspend fun doWork(): Result {
        val id = inputData.getLong("note", -1)
        val db = Db.get(applicationContext)
        val note = db.notes().get(id) ?: return Result.failure()
        val label = note.deviceName.let { if (it.startsWith("phone-")) "P" else "#" } + note.number
        val storage = Storage(applicationContext)
        val prefs = Prefs(applicationContext)
        val model = Models.byId(prefs.model())
        if (!Models.isDownloaded(applicationContext, model)) {
            db.notes().update(note.copy(status = NoteStatus.Pending, error = "Model not downloaded yet"))
            return Result.failure() // re-queued by enqueuePending() once the model is there
        }
        foreground("Transcribing $label")
        val t0 = System.currentTimeMillis()
        // Chunks finished by an earlier, interrupted run of this note.
        val doneChunks = ArrayList(note.chunks?.split(SEP)?.filter { true } ?: emptyList())
        db.notes().update(note.copy(status = NoteStatus.Transcribing, error = null, attempts = note.attempts + 1,
            transcribeStartedAt = t0, progress = if (doneChunks.isEmpty()) null else "resuming at ${doneChunks.size + 1}"))
        var current = note.copy(status = NoteStatus.Transcribing, error = null, attempts = note.attempts + 1, transcribeStartedAt = t0)
        return try {
            val pcm = com.vmm5.notes.audio.StickAudio.load(storage.wav(note.fileId))
            val samples = com.vmm5.notes.audio.StickAudio.toWhisperInput(pcm)
            val pieces = com.vmm5.notes.audio.Chunker.split(samples)
            if (doneChunks.size > pieces.size) doneChunks.clear()
            Log.i(TAG, "note $id: ${pieces.size} chunk(s), ${doneChunks.size} already done")
            val texts = ArrayList(doneChunks)
            for (i in doneChunks.size until pieces.size) {
                val prog = "${i + 1} of ${pieces.size}"
                foreground("Transcribing $label – $prog")
                current = current.copy(progress = prog)
                db.notes().update(current)
                val text = lock.withLock {
                    val ctx = context(Models.file(applicationContext, model).absolutePath)
                    ctx.transcribeData(pieces[i], printTimestamp = false)
                }.trim()
                texts.add(text)
                // Persist each finished chunk: a stop or a restart resumes here.
                current = current.copy(chunks = texts.joinToString(SEP))
                db.notes().update(current)
            }
            val transcript = texts.filter { it.isNotBlank() }.joinToString(" ").trim()
            val took = System.currentTimeMillis() - t0
            Log.i(TAG, "note $id: ${note.seconds} s of audio in ${took / 1000} s")
            val done = current.copy(status = NoteStatus.Completed, transcript = transcript.ifBlank { "(no speech found)" },
                model = "whisper ${model.id}", error = null, transcribeMs = took, transcribeStartedAt = null, chunks = null, progress = null)
            storage.writeMd(done)
            db.notes().update(done)
            if (prefs.listEnabled()) {
                val items = com.vmm5.notes.data.ListCapture.extract(done.transcript ?: "", prefs.listPhrase())
                if (items.isNotEmpty()) {
                    com.vmm5.notes.data.ListCapture.append(applicationContext, items, done)
                    db.log("list", "Added to the list from $label: " + items.joinToString("; "), id)
                    prefs.journalDir()?.let { Journal(applicationContext).mirror(it) }
                }
            }
            if (prefs.cleanup() && Cleanup.engine.ready()) {
                // The journals wait for the tidied text; CleanupWorker writes them.
                Cleanup.enqueue(applicationContext, id)
            } else if (!note.journaled) {
                val journal = Journal(applicationContext)
                journal.append(done, false)
                db.notes().update(done.copy(journaled = true))
                val dir = prefs.journalDir()
                if (dir != null && !journal.mirror(dir)) db.log("error", "Could not update musings.md in the chosen folder")
            }
            db.log("transcribed", "Transcribed $label (${note.seconds} s of audio, ${pieces.size} chunk${if (pieces.size == 1) "" else "s"}) with ${model.id} in ${took / 1000} s" +
                (if (note.seconds > 0) " (" + String.format(java.util.Locale.US, "%.2f", took / 1000.0 / note.seconds) + "× the audio length, ${com.whispercpp.whisper.WhisperCpuConfig.preferredThreadCount} threads)" else ""), id)
            Result.success(Data.EMPTY)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by WorkManager (system limit, restart, ...). The finished
            // chunks are already saved; the next run picks up after them.
            val why = stopReasonText()
            Log.w(TAG, "note $id stopped: $why")
            db.notes().update(current.copy(status = NoteStatus.Pending, transcribeStartedAt = null, error = "Stopped by the system: $why — will resume"))
            db.log("error", "Transcription of $label stopped by the system ($why) after ${(System.currentTimeMillis() - t0) / 1000} s; ${current.chunks?.split(SEP)?.size ?: 0} chunk(s) kept, it resumes from there", id)
            throw e
        } catch (e: Throwable) {
            val what = describe(e)
            Log.w(TAG, "note $id failed: $what", e)
            val attempts = note.attempts + 1
            db.notes().update(current.copy(status = NoteStatus.Failed, error = what, transcribeStartedAt = null))
            db.log("error", "Transcription of $label failed: $what" + (if (attempts < 3 && e !is OutOfMemoryError) " (will retry)" else ""), id)
            if (attempts < 3 && e !is OutOfMemoryError) Result.retry() else Result.failure()
        }
    }
}
