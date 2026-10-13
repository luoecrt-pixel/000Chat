package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class OpenAiRawSseArchiveTest {
    private fun root(): File = createTempDir(prefix = "openai-sse-archive-")

    @Test fun completedTerminalClosesAndSyncsArchiveBeforeCleanup() {
        val dir = root()
        try {
            val bytes = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"output\":[]}}\n\n"
                .toByteArray(Charsets.UTF_8)
            val archive = OpenAiRawSseArchive.begin(
                ByteArrayInputStream(bytes), dir, "chat-one", "request-one")
            assertFalse(archive.deleteAfterDurableCommit())
            val result = OpenAiSseWireByteLimitStream(archive)
                .bufferedReader(Charsets.UTF_8).use {
                    OpenAiSseStreamReader.read(it) { true }
                }
            assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
            assertArrayEquals(bytes, archive.file.readBytes())
            assertTrue(archive.deleteAfterDurableCommit())
            assertFalse(archive.file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedConnectionCannotEraseOverlappingSameOwnerPaidBytes() {
        val dir = root()
        try {
            val aBytes = "data: first\n\n".toByteArray(Charsets.UTF_8)
            val bBytes = "data: second\n\n".toByteArray(Charsets.UTF_8)
            val a = OpenAiRawSseArchive.begin(
                ByteArrayInputStream(aBytes), dir, "chat", "request")
            val b = OpenAiRawSseArchive.begin(
                ByteArrayInputStream(bBytes), dir, "chat", "request")
            a.use { it.readBytes() }
            assertFalse(b.deleteAfterDurableCommit())
            assertEquals(2, OpenAiRawSseArchive.archivesFor(dir, "chat", "request").size)
            assertTrue(a.deleteAfterDurableCommit())
            assertTrue("Preserve other stream's bytes", b.file.exists())
            b.use { it.readBytes() }
            assertArrayEquals(bBytes, b.file.readBytes())
            assertEquals(1, OpenAiRawSseArchive.archivesFor(dir, "chat", "request").size)
            assertTrue(OpenAiRawSseArchive.archivesFor(dir, "other-chat", "request").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun exceededWireLimitPreservesAllActuallyReadBytes() {
        val dir = root()
        try {
            val bytes = "0123456789".toByteArray(Charsets.UTF_8)
            val archive = OpenAiRawSseArchive.begin(
                ByteArrayInputStream(bytes), dir, "chat-limit", "request-limit")
            try {
                OpenAiSseWireByteLimitStream(archive, maxBytes = 3).use { it.readBytes() }
                fail("Oversized stream must not return success")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("wire-byte limit"))
            }
            // One excess byte is read to distinguish overflow from exact EOF;
            // unread provider bytes must never be claimed as preserved.
            assertArrayEquals(bytes.copyOfRange(0, 4), archive.file.readBytes())
            assertTrue(archive.file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun upstreamIoFailureRetainsReceivedPrefixWithoutFabrication() {
        val dir = root()
        try {
            val bytes = byteArrayOf(12, 34, 56)
            val source = object : ByteArrayInputStream(bytes) {
                private var first = true
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!first) throw IOException("Provider socket ended")
                    first = false
                    return super.read(b, off, minOf(len, 2))
                }
            }
            val archive = OpenAiRawSseArchive.begin(source, dir, "chat-io", "request-io")
            try {
                archive.use { it.readBytes() }
                fail("Partial stream must report failure")
            } catch (e: IOException) {
                assertEquals("Provider socket ended", e.message)
            }
            assertArrayEquals(bytes.copyOfRange(0, 2), archive.file.readBytes())
            assertTrue(archive.file.exists())
        } finally { dir.deleteRecursively() }
    }
}
