package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.MalformedInputException

class OpenAiSseUtf8ReaderTest {
    @Test fun strictReaderAcceptsUnicodeAcrossSingleByteNetworkChunks() {
        val expected = "data: привет 😀\n\n"
        val bytes = expected.toByteArray(Charsets.UTF_8)
        val network = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, minOf(1, len))
        }
        val actual = OpenAiSseUtf8Reader.open(network).use { it.readText() }
        assertEquals(expected, actual)
    }

    @Test fun malformedUtf8FailsExplicitlyAndLeavesRawBytesOnDisk() {
        val dir = createTempDir(prefix = "sse-utf8-invalid-")
        try {
            val payload = byteArrayOf('a'.code.toByte(), 0xC3.toByte(), '('.code.toByte())
            val archive = OpenAiRawSseArchive.begin(
                ByteArrayInputStream(payload), dir, "chat", "request")
            try {
                OpenAiSseUtf8Reader.open(archive).use { it.readText() }
                fail("Malformed provider bytes cannot become replacement characters")
            } catch (e: MalformedInputException) {
                assertTrue(e.inputLength > 0)
            }
            assertArrayEquals("Received bytes must survive decoder failure",
                payload, archive.file.readBytes())
            assertTrue("Failed transport is retained", archive.file.exists())
        } finally { dir.deleteRecursively() }
    }
}
