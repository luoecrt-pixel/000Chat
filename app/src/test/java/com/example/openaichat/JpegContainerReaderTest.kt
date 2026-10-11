package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Marker fixtures test container checks, not whether entropy bytes decode into a real image. */
class JpegContainerReaderTest {
    private fun hex(source: String) = source.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    private fun jpeg(payload: ByteArray, trailer: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write(hex("FF D8 FF C0 00 08 08 00 01 00 01 01"))
        write(hex("FF DA 00 08 01 01 00 00 3F 00"))
        write(payload); write(hex("FF D9")); write(ByteArray(trailer) { 0x55 })
    }.toByteArray()

    @Test fun scanningUsesBlockReadsAndStopsBeforeLargeMotionPhotoTrailer() {
        val payload = ByteArray(1024 * 1024) { 0x55 }
        // Header is 22 bytes: split an FF/00 stuffed byte across a 64 KiB buffer boundary.
        payload[65535 - 22] = 0xFF.toByte(); payload[65536 - 22] = 0
        payload[131071 - 22] = 0xFF.toByte(); payload[131072 - 22] = 0xD0.toByte()
        val bytes = jpeg(payload, 4 * 1024 * 1024)
        val input = object : ByteArrayInputStream(bytes) {
            var reads = 0
            override fun read(): Int = throw AssertionError("JPEG validator must not read the stream byte by byte")
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int { reads++; return super.read(buffer, offset, count) }
        }
        assertTrue(JpegContainerReader.complete(input))
        assertTrue("Trailer must not be scanned", input.reads <= 18)
    }

    @Test fun missingEoiTruncatedSegmentsAndFrameWithoutScanAreRejected() {
        val complete = jpeg(ByteArray(100) { 0x55 })
        assertTrue(JpegContainerReader.complete(ByteArrayInputStream(complete)))
        assertThrows(IOException::class.java) { JpegContainerReader.complete(ByteArrayInputStream(complete.copyOf(complete.size - 1))) }
        assertThrows(IOException::class.java) { JpegContainerReader.complete(ByteArrayInputStream(hex("FF D8 FF E1 FF FF 00"))) }
        assertFalse(JpegContainerReader.complete(ByteArrayInputStream(hex("FF D8 FF C0 00 08 08 00 01 00 01 01 FF D9"))))
        assertFalse(JpegContainerReader.complete(ByteArrayInputStream(hex("FF D8 FF C0 00 08 08 00 00 00 01 01 FF D9"))))
    }
}
