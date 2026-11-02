package com.vmm5.notes

import com.vmm5.notes.sync.FirmwareFile
import com.vmm5.notes.sync.compareVersions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareFileTest {
    private fun app(version: String, size: Int = 4096): ByteArray {
        val b = ByteArray(size) { (it * 7).toByte() }
        b[0] = 0xE9.toByte()
        val m = "M5VR-FW-VERSION=$version\u0000".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(m, 0, b, 1000, m.size)
        return b
    }

    @Test fun readsTheVersionFromAnAppImage() {
        val (img, v) = FirmwareFile.parse(app("2.13.0"))
        assertEquals("2.13.0", v)
        assertEquals(4096, img.size)
    }

    @Test fun cutsTheAppOutOfAMergedImage() {
        val a = app("2.14.1")
        val merged = ByteArray(0x10000 + a.size) { 0xFF.toByte() }
        merged[0] = 0xE9.toByte() // the bootloader's own image
        merged[0x8000] = 0xAA.toByte(); merged[0x8001] = 0x50
        System.arraycopy(a, 0, merged, 0x10000, a.size)
        val (img, v) = FirmwareFile.parse(merged)
        assertEquals("2.14.1", v)
        assertArrayEquals(a, img)
    }

    private fun rejected(b: ByteArray): Boolean = try { FirmwareFile.parse(b); false } catch (e: FirmwareFile.Rejected) { true }

    @Test fun refusesWhatIsNotOurFirmware() {
        assertTrue(rejected("hello".toByteArray()))
        val noMarker = ByteArray(4096); noMarker[0] = 0xE9.toByte()
        assertTrue(rejected(noMarker))
        assertTrue(rejected(app("2.13.0", 0x1E0000 + 1)))
    }

    @Test fun comparesVersions() {
        assertTrue(compareVersions("2.13.0", "2.12.0") > 0)
        assertTrue(compareVersions("2.9.0", "2.10.0") < 0)
        assertEquals(0, compareVersions("2.13", "2.13.0"))
    }
}
