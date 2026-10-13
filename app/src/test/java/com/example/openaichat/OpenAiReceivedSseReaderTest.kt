package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class OpenAiReceivedSseReaderTest {
    @Test fun parserLimitRetainsWholeExistingBodyWithoutInterpretingTheTail() {
        val root = createTempDir(prefix = "received-limit-")
        val bytes = ("data: " + "x".repeat(100_000) + "\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_tail\"}}\n\n")
            .toByteArray(Charsets.UTF_8)
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "req")
            var events = 0
            try {
                OpenAiSseUtf8Reader.open(OpenAiSseWireByteLimitStream(archive)).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive,
                        OpenAiSseStreamReader.Limits(64, 128, 256)) { events++; true }
                }
                fail("A retained tail must not become a parsed success")
            } catch (failure: ResponsesSseLimitException) {
                assertTrue(failure.message.orEmpty().contains("line limit"))
            }
            assertEquals("Do not execute unparsed terminal/tools", 0, events)
            assertArrayEquals(bytes, archive.file.readBytes())
            val message = JSONObject()
            OpenAiRawSseArchive.attachToMessage(message, root, "chat", "req")
            val card = message.getJSONArray("annotations").getJSONObject(0)
            assertEquals(bytes.size.toLong(), card.getLong("size_bytes"))
            assertEquals(archive.file.absolutePath, card.getString("local_path"))
            assertTrue(card.getBoolean("received_source"))
            OpenAiRawSseArchive.attachToMessage(message, root, "chat", "req")
            assertEquals("Do not duplicate export cards", 1, message.getJSONArray("annotations").length())
            val foreign = JSONObject()
            OpenAiRawSseArchive.attachToMessage(foreign, root, "other-chat", "req")
            assertFalse(foreign.has("annotations"))
        } finally { root.deleteRecursively() }
    }

    @Test fun successfulTerminalDoesNotWaitForAnUnclosedServerSocket() {
        val root = createTempDir(prefix = "received-terminal-")
        val bytes = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_ok\",\"output\":[]}}\n\n"
            .toByteArray(Charsets.UTF_8)
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) throw IOException("Server keeps socket open")
                return super.read(buffer, offset, length)
            }
        }
        try {
            val archive = OpenAiRawSseArchive.begin(source, root, "chat", "req")
            val result = OpenAiSseUtf8Reader.open(archive).use {
                OpenAiReceivedSseReader.read(it, archive) { true }
            }
            assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
            assertArrayEquals(bytes, archive.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedDrainRetainsExactPrefixAndReportsBothFailures() {
        val root = createTempDir(prefix = "received-io-")
        val source = object : InputStream() {
            var delivered = 0
            override fun read(): Int {
                // The wire limiter legitimately probes one extra byte to
                // distinguish exact EOF from overflow. It is still bounded.
                if (delivered >= 16_384) throw IOException("Original socket cancelled")
                delivered++
                return 'x'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (delivered >= 16_384) throw IOException("Original socket cancelled")
                val count = minOf(len, 16_384 - delivered)
                b.fill('x'.code.toByte(), off, off + count)
                delivered += count
                return count
            }
        }
        try {
            val archive = OpenAiRawSseArchive.begin(source, root, "chat", "req")
            try {
                OpenAiSseUtf8Reader.open(OpenAiSseWireByteLimitStream(archive, 32)).use {
                    OpenAiReceivedSseReader.read(it, archive) { true }
                }
                fail("Cancelled drain cannot report success")
            } catch (failure: ResponsesSseLimitException) {
                assertEquals("Original socket cancelled", failure.suppressed.single().message)
            }
            assertEquals(16_384L, archive.file.length())
            assertArrayEquals(ByteArray(16_384) { 'x'.code.toByte() }, archive.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun receivedSourceLargerThan512MiBIsArchivedWithFixedSizeReads() {
        val root = createTempDir(prefix = "received-large-")
        val total = OpenAiSseWireByteLimitStream.MAX_BODY_BYTES + 32_768L
        val source = object : InputStream() {
            var position = 0L
            var maxReadSize = 0
            override fun read(): Int {
                if (position == total) return -1
                position++
                return 'x'.code
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                maxReadSize = maxOf(maxReadSize, len)
                if (position == total) return -1
                val count = minOf(len.toLong(), total - position).toInt()
                b.fill('x'.code.toByte(), off, off + count)
                position += count
                return count
            }
        }
        try {
            val archive = OpenAiRawSseArchive.begin(source, root, "chat", "req")
            try {
                // Trigger the same permanent wire boundary early, then exercise
                // actual >512 MiB retention without constructing a huge String.
                OpenAiSseUtf8Reader.open(OpenAiSseWireByteLimitStream(archive, 64)).use {
                    OpenAiReceivedSseReader.read(it, archive) { fail("No complete event"); true }
                }
                fail("Limits must remain explicit failures")
            } catch (failure: ResponsesSseLimitException) {
                assertTrue(failure.message.orEmpty().contains("wire-byte limit"))
            }
            assertEquals(total, source.position)
            assertEquals(total, archive.archivedBytes)
            assertEquals(total, archive.file.length())
            assertTrue("No whole-body allocation", source.maxReadSize <= 64 * 1024)
            archive.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var verified = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    for (i in 0 until n) if (buffer[i] != 'x'.code.toByte()) fail("Source byte changed")
                    verified += n
                }
                assertEquals(total, verified)
            }
        } finally { root.deleteRecursively() }
    }
}
