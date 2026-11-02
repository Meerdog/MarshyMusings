package com.vmm5.notes

import com.vmm5.notes.audio.ImaAdpcm
import com.vmm5.notes.data.StickWav
import com.vmm5.notes.data.Tags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecoderTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    // tone.wav was encoded by the firmware's encoder; tone.pcm is what the
    // firmware's decoder makes of it. Ours must match sample for sample.
    @Test
    fun decoderMatchesTheFirmware() {
        val wav = res("tone.wav")
        assertTrue(StickWav.looksRight(wav, wav.size.toLong()))
        val ours = ImaAdpcm.decode(wav)
        val ref = res("tone.pcm").let { b -> ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) } }
        assertEquals(ref.size, ours.size)
        assertArrayEquals(ref, ours)
        assertEquals(3, StickWav.seconds(wav.size.toLong()))
    }

    @Test
    fun upsamplingDoublesTheLength() {
        val x = ImaAdpcm.toWhisperInput(shortArrayOf(0, 16384, -16384))
        assertEquals(6, x.size)
        assertEquals(0.25f, x[1], 1e-6f)
        assertEquals(0.5f, x[2], 1e-6f)
    }

    @Test
    fun namesParse() {
        assertEquals("run-thought" to 42, Tags.parse("run-thought_0042.wav"))
        assertEquals("rec" to 7, Tags.parse("REC_0007.wav"))
        assertNull(Tags.parse("filler.bin"))
        assertNull(Tags.parse("rec_0000.wav"))
        assertEquals("Run · Thought", Tags.label("run-thought"))
        assertEquals("No tag", Tags.label("rec"))
    }
}
