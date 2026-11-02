package com.vmm5.notes.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records on the phone into the same Ogg Opus the stick makes (16 kHz,
 * about 16 kbit/s) so it goes down the same transcribe / tidy / journal
 * path. Android 10+ has the Opus encoder; older phones get AAC in an
 * .m4a, which the player and Whisper path decode through MediaCodec too.
 */
class PhoneRecorder(private val context: Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set
    var startedAt = 0L; private set
    val running get() = rec != null

    fun start(target: File) {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        if (Build.VERSION.SDK_INT >= 29) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioEncodingBitRate(16000)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32000)
        }
        r.setAudioSamplingRate(16000)
        r.setAudioChannels(1)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        rec = r; file = target; startedAt = System.currentTimeMillis()
    }

    /** Returns the seconds recorded, or null if nothing usable was made. */
    fun stop(): Int? {
        val r = rec ?: return null
        rec = null
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        return try { r.stop(); secs } catch (e: RuntimeException) { file?.delete(); null } finally { r.release() }
    }

    fun cancel() { rec?.let { runCatching { it.stop() }; it.release() }; rec = null; file?.delete() }

    companion object {
        fun extension() = if (Build.VERSION.SDK_INT >= 29) "opus" else "m4a"
    }
}
