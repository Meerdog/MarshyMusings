package com.vmm5.notes.sync

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Firmware shared on its own: a .bin picked from the phone's files (sent
 * by email, Drive, a chat...) is checked and kept in the app, so the stick
 * can be updated without a new version of the app.
 *
 * Accepted: the app image (`M5VoiceRecorder-v<x>-app.bin`, what the phone
 * sends over Bluetooth) or the merged USB image (`M5VoiceRecorder-v<x>.bin`;
 * the app image is cut out of it at 0x10000). It must start with the
 * ESP32 image magic byte, fit the stick's app slot, and carry the
 * firmware's marker "M5VR-FW-VERSION=<x>" (firmware 2.13.0 and later), which
 * also gives its version. The stick checks the CRC and the image itself
 * again when it is sent, and keeps its old firmware if the new one does
 * not start.
 */
object FirmwareFile {
    private const val MARKER = "M5VR-FW-VERSION="
    private const val APP_SLOT = 0x1E0000
    private const val MERGED_APP_OFFSET = 0x10000

    class Rejected(msg: String) : Exception(msg)

    private fun dir(c: Context) = File(c.filesDir, "firmware").also { it.mkdirs() }
    private fun image(c: Context) = File(dir(c), "loaded.bin")
    private fun versionFile(c: Context) = File(dir(c), "loaded.txt")

    fun loadedVersion(c: Context): String? = versionFile(c).takeIf { it.exists() && image(c).exists() }?.readText()?.trim()
    fun loadedImage(c: Context): ByteArray? = image(c).takeIf { it.exists() }?.readBytes()
    fun remove(c: Context) { image(c).delete(); versionFile(c).delete() }

    /** Checks the bytes; returns the app image and its version, or throws [Rejected]. */
    fun parse(bytes: ByteArray): Pair<ByteArray, String> {
        var img = bytes
        // A merged image: bootloader at 0, partition table (magic AA 50) at 0x8000, the app at 0x10000.
        if (bytes.size > MERGED_APP_OFFSET && (bytes[0x8000].toInt() and 0xFF) == 0xAA && (bytes[0x8001].toInt() and 0xFF) == 0x50) {
            // esptool's merge_bin writes the app last, unpadded: the rest of the file is the app image.
            img = bytes.copyOfRange(MERGED_APP_OFFSET, bytes.size)
        }
        if (img.isEmpty() || (img[0].toInt() and 0xFF) != 0xE9) throw Rejected("Not an ESP32 firmware image.")
        if (img.size > APP_SLOT) throw Rejected("Too big for the stick (${img.size / 1024} KB).")
        val text = String(img, Charsets.ISO_8859_1)
        val at = text.indexOf(MARKER)
        if (at < 0) throw Rejected("Not Marshy's Musings firmware, or older than 2.13.0 (no version marker).")
        val end = text.indexOf('\u0000', at).takeIf { it > 0 } ?: (at + MARKER.length + 16)
        val version = text.substring(at + MARKER.length, end).trim()
        if (version.isEmpty() || !version.first().isDigit()) throw Rejected("The firmware's version marker is unreadable.")
        return img to version
    }

    /** Reads a picked file, checks it and keeps it. Returns the version. */
    fun load(c: Context, uri: Uri): String {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Rejected("Could not read the file.")
        val (img, version) = parse(bytes)
        image(c).writeBytes(img)
        versionFile(c).writeText(version)
        return version
    }
}
