package com.vmm5.notes.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.vmm5.notes.data.StickWav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** PCM 16-bit mono at some rate: what both formats decode to. */
class Pcm(val samples: ShortArray, val rate: Int)

/** Decodes a stick file of either format: WAV (IMA ADPCM, in Kotlin) or Ogg Opus (Android's own decoder, 48 kHz out). */
object StickAudio {
    fun load(file: File): Pcm {
        val bytes = file.readBytes()
        val isWav = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
        return if (!isWav) OpusFile.decode(file) else Pcm(ImaAdpcm.decode(bytes), StickWav.rate(bytes))
    }

    /** 16 kHz float samples for Whisper from any rate: 8k is doubled, 48k is averaged down by three. */
    fun toWhisperInput(p: Pcm): FloatArray = when {
        p.rate == 16000 -> FloatArray(p.samples.size) { p.samples[it] / 32768f }
        p.rate == 48000 -> {
            val n = p.samples.size / 3
            FloatArray(n) { i -> (p.samples[3 * i] + p.samples[3 * i + 1] + p.samples[3 * i + 2]) / (3f * 32768f) }
        }
        p.rate == 8000 -> ImaAdpcm.toWhisperInput(p.samples, 8000)
        else -> { // generic linear resample
            val n = (p.samples.size.toLong() * 16000 / p.rate).toInt()
            FloatArray(n) { i -> val x = i.toDouble() * p.rate / 16000; val j = x.toInt().coerceAtMost(p.samples.size - 2); val f = (x - j).toFloat()
                (p.samples[j] * (1 - f) + p.samples[j + 1] * f) / 32768f }
        }
    }
}

/**
 * Splits 16 kHz audio into pieces of about [targetSec] seconds for Whisper,
 * cutting at the quietest 200 ms within 3 s either side of each boundary
 * so words are not sliced. No overlap, so the pieces join without repeats.
 */
object Chunker {
    fun split(samples: FloatArray, rate: Int = 16000, targetSec: Int = 30): List<FloatArray> {
        val target = targetSec * rate
        if (samples.size <= target + target / 2) return listOf(samples)
        val out = ArrayList<FloatArray>()
        var start = 0
        val win = rate / 5          // 200 ms
        val slack = 3 * rate        // ±3 s
        while (samples.size - start > target + target / 2) {
            val ideal = start + target
            var best = ideal; var bestE = Double.MAX_VALUE
            var p = maxOf(start + rate, ideal - slack)
            while (p + win <= minOf(samples.size, ideal + slack)) {
                var e = 0.0
                for (i in p until p + win) e += samples[i] * samples[i]
                if (e < bestE) { bestE = e; best = p + win / 2 }
                p += win / 2
            }
            out.add(samples.copyOfRange(start, best))
            start = best
        }
        out.add(samples.copyOfRange(start, samples.size))
        return out
    }
}

/** Ogg Opus through MediaExtractor + MediaCodec, synchronous, whole file. */
object OpusFile {
    fun decode(file: File): Pcm {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var track = -1; var fmt: android.media.MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); throw java.io.IOException("no audio track in ${file.name}") }
        ex.selectTrack(track)
        val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)!!
        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val out = java.io.ByteArrayOutputStream()
        var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        var lastProgress = System.currentTimeMillis()
        val startedAt = lastProgress
        while (!outputDone) {
            val now = System.currentTimeMillis()
            if (now - lastProgress > 10_000) { codec.stop(); codec.release(); ex.release(); throw java.io.IOException("decoder stalled: no output for 10 s at ${out.size()} bytes of ${file.name}") }
            if (now - startedAt > 120_000) { codec.stop(); codec.release(); ex.release(); throw java.io.IOException("decoder gave up after 2 minutes on ${file.name}") }
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(idx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oidx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oidx >= 0 -> {
                    lastProgress = System.currentTimeMillis()
                    val buf = codec.getOutputBuffer(oidx)!!
                    val chunk = ByteArray(info.size); buf.position(info.offset); buf.get(chunk); out.write(chunk)
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                oidx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    rate = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val bytes = out.toByteArray()
        val frames = bytes.size / 2 / channels
        val mono = ShortArray(frames)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += bb.short
            mono[i] = (acc / channels).toShort()
        }
        return Pcm(mono, rate)
    }
}

/** IMA ADPCM decoder, a port of lib/ImaAdpcm in the firmware: 256-byte blocks of 505 samples. */
object ImaAdpcm {
    private val step = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** 8 kHz 16-bit PCM from a stick WAV file's bytes. */
    fun decode(wav: ByteArray): ShortArray {
        val blocks = (wav.size - StickWav.HEADER) / StickWav.BLOCK
        val out = ShortArray(blocks * StickWav.SAMPLES_PER_BLOCK)
        var o = 0
        for (b in 0 until blocks) {
            val base = StickWav.HEADER + b * StickWav.BLOCK
            var predictor = ((wav[base].toInt() and 0xFF) or (wav[base + 1].toInt() shl 8)).toShort().toInt()
            var index = (wav[base + 2].toInt() and 0xFF).coerceIn(0, 88)
            out[o++] = predictor.toShort()
            for (i in 4 until StickWav.BLOCK) {
                val byte = wav[base + i].toInt() and 0xFF
                for (code in intArrayOf(byte and 0x0F, byte shr 4)) {
                    val s = step[index]
                    var delta = s shr 3
                    if (code and 4 != 0) delta += s
                    if (code and 2 != 0) delta += s shr 1
                    if (code and 1 != 0) delta += s shr 2
                    predictor = (predictor + if (code and 8 != 0) -delta else delta).coerceIn(-32768, 32767)
                    index = (index + indexTable[code]).coerceIn(0, 88)
                    out[o++] = predictor.toShort()
                }
            }
        }
        return out
    }

    /** 16 kHz float samples for Whisper: as is from Hi-Fi files, linear 2x upsampling of 8 kHz ones. */
    fun toWhisperInput(pcm: ShortArray, rate: Int = StickWav.RATE): FloatArray {
        if (rate >= 16000) return FloatArray(pcm.size) { pcm[it] / 32768f }
        val pcm8k = pcm
        val out = FloatArray(pcm8k.size * 2)
        for (i in pcm8k.indices) {
            val a = pcm8k[i] / 32768f
            val b = (if (i + 1 < pcm8k.size) pcm8k[i + 1] else pcm8k[i]) / 32768f
            out[2 * i] = a
            out[2 * i + 1] = (a + b) / 2f
        }
        return out
    }
}

enum class Effect { Dry, Room, Chorus }

/**
 * Two playback effects. Room: Schroeder's four parallel combs into two
 * series all-passes, short and with a little damping, mixed about a
 * quarter wet, so a voice sounds like it is in a room rather than a
 * tank. Chorus: a 20 ms delay swept by a slow sine, mixed half and half.
 */
object Effects {
    fun apply(pcm: ShortArray, rate: Int, effect: Effect): ShortArray = when (effect) {
        Effect.Dry -> pcm
        Effect.Room -> room(pcm, rate)
        Effect.Chorus -> chorus(pcm, rate)
    }

    private fun room(pcm: ShortArray, rate: Int): ShortArray {
        val combMs = doubleArrayOf(23.1, 27.7, 31.3, 36.9)
        val allMs = doubleArrayOf(5.0, 1.7)
        val feedback = 0.68f
        val damp = 0.35f
        val combs = combMs.map { FloatArray((it * rate / 1000).toInt()) }
        val combIdx = IntArray(4); val combLow = FloatArray(4)
        val alls = allMs.map { FloatArray((it * rate / 1000).toInt()) }
        val allIdx = IntArray(2)
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val x = pcm[i] / 32768f
            var acc = 0f
            for (c in 0 until 4) {
                val buf = combs[c]; val j = combIdx[c]
                val y = buf[j]
                combLow[c] = y * (1 - damp) + combLow[c] * damp
                buf[j] = x + combLow[c] * feedback
                combIdx[c] = (j + 1) % buf.size
                acc += y
            }
            var w = acc * 0.25f
            for (a in 0 until 2) {
                val buf = alls[a]; val j = allIdx[a]
                val y = buf[j]
                buf[j] = w + y * 0.5f
                w = y - w * 0.5f
                allIdx[a] = (j + 1) % buf.size
            }
            out[i] = ((x * 0.8f + w * 0.3f).coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        return out
    }

    private fun chorus(pcm: ShortArray, rate: Int): ShortArray {
        val baseMs = 20.0; val depthMs = 3.0; val lfoHz = 0.8
        val maxDelay = ((baseMs + depthMs + 1) * rate / 1000).toInt()
        val buf = FloatArray(maxDelay)
        var w = 0
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val x = pcm[i] / 32768f
            buf[w] = x
            val d = (baseMs + depthMs * Math.sin(2 * Math.PI * lfoHz * i / rate)) * rate / 1000
            val di = d.toInt(); val frac = (d - di).toFloat()
            val r0 = (w - di + maxDelay) % maxDelay
            val r1 = (r0 - 1 + maxDelay) % maxDelay
            val delayed = buf[r0] * (1 - frac) + buf[r1] * frac
            out[i] = ((x * 0.6f + delayed * 0.5f).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            w = (w + 1) % maxDelay
        }
        return out
    }
}

/** Plays a stick WAV through AudioTrack; one at a time. */
class Player {
    private var track: AudioTrack? = null
    @Volatile var playing = false; private set

    suspend fun play(file: File, effect: Effect = Effect.Dry, onDone: () -> Unit) = withContext(Dispatchers.IO) {
        stop()
        val loaded = StickAudio.load(file)
        val rate = loaded.rate
        val pcm = Effects.apply(loaded.samples, rate, effect)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.setNotificationMarkerPosition(pcm.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) { playing = false; onDone() }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
        playing = true
        t.play()
    }

    fun stop() {
        playing = false
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
