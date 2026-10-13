package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class OpenAiSseWireByteLimitStreamTest {
    @Test fun fourWireOctetsAreCountedBeforeUtf8DecoderTurnsThemIntoTwoChars() {
        val utf8 = "😀".toByteArray(Charsets.UTF_8)
        assertEquals(4, utf8.size)
        val exact = OpenAiSseWireByteLimitStream(ByteArrayInputStream(utf8), 4)
        assertEquals("😀", exact.bufferedReader(Charsets.UTF_8).readText())
        assertEquals(4L, exact.bytesRead)

        val tooShort = OpenAiSseWireByteLimitStream(ByteArrayInputStream(utf8), 3)
        try {
            tooShort.bufferedReader(Charsets.UTF_8).readText()
            fail("UTF8 byte limit should fail rather than silently trim a codepoint")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("wire-byte limit"))
            assertEquals(4L, tooShort.bytesRead)
        }
    }

    @Test fun fragmentedInputCannotBypassExactHttpBodyOctetBudget() {
        val source = "data: {\"type\":\"response.completed\"}\n\n"
            .toByteArray(Charsets.UTF_8)
        val fragmented = object : ByteArrayInputStream(source) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int =
                super.read(buffer, off, minOf(2, len))
        }
        val reader = OpenAiSseWireByteLimitStream(fragmented, source.size.toLong())
        assertArrayEquals(source, reader.readBytes())
        assertEquals(source.size.toLong(), reader.bytesRead)
        assertEquals(-1, reader.read())

        val bounded = OpenAiSseWireByteLimitStream(
            ByteArrayInputStream(source), source.size.toLong() - 1)
        val bytes = ByteArray(1000)
        assertEquals(source.size - 1, bounded.read(bytes, 0, bytes.size))
        try {
            bounded.read(bytes)
            fail("Extra final byte must be rejected, not clipped")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("wire-byte limit"))
        }
    }
    @Test fun limitNeverReportsClippedPayloadAsCompleteSuccess() {
        val source = byteArrayOf(10, 20, 30, 40)
        val reader = OpenAiSseWireByteLimitStream(
            ByteArrayInputStream(source), maxBytes = 2)
        val target = ByteArray(8)
        assertEquals(2, reader.read(target, 0, target.size))
        assertArrayEquals(byteArrayOf(10, 20), target.copyOfRange(0, 2))
        try {
            reader.read(target, 0, target.size)
            fail("The third source byte must not become a successful EOF")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("wire-byte limit"))
            assertEquals(3L, reader.bytesRead)
        }
    }

}
