package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import org.json.JSONObject

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

    @Test fun failedFsyncCannotAuthorizeArchiveDeletion() {
        val dir = root()
        var sourceClosed = false
        var sinkClosed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { sourceClosed = true; super.close() }
        }
        try {
            val archive = OpenAiRawSseArchive.begin(source, dir, "chat", "req") { file ->
                object : OpenAiRawSseArchive.Sink {
                    val output = FileOutputStream(file)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                    override fun sync(): Unit = throw IOException("Injected fsync failure")
                    override fun close() { sinkClosed = true; output.close() }
                }
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), archive.readBytes())
            try { archive.close(); fail("fsync must fail explicitly") }
            catch (_: ResponsesArchiveStorageException) {}
            assertTrue(sourceClosed)
            assertTrue(sinkClosed)
            assertFalse("A merely closed file is not a durable commit", archive.deleteAfterDurableCommit())
            assertArrayEquals(byteArrayOf(1, 2, 3), archive.file.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskFullDoesNotConsumeMoreUpstreamBytesOrClaimTheyWereSaved() {
        val dir = root()
        var upstreamReads = 0
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                upstreamReads++
                return super.read(b, off, len)
            }
        }
        try {
            val archive = OpenAiRawSseArchive.begin(source, dir, "chat", "req") { file ->
                object : OpenAiRawSseArchive.Sink {
                    val output = FileOutputStream(file)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        output.write(bytes, offset, 2)
                        throw IOException("Injected disk full")
                    }
                    override fun sync() = output.fd.sync()
                    override fun close() = output.close()
                }
            }
            repeat(2) {
                try { archive.read(ByteArray(6)); fail("Storage failure must stay permanent") }
                catch (_: ResponsesArchiveStorageException) {}
            }
            try { archive.close() } catch (_: ResponsesArchiveStorageException) {}
            assertEquals("Do not keep receiving after storage failure", 1, upstreamReads)
            assertEquals(0L, archive.archivedBytes)
            assertArrayEquals(byteArrayOf(1, 2), archive.file.readBytes())
            assertFalse(archive.deleteAfterDurableCommit())
            val message = JSONObject()
            OpenAiRawSseArchive.attachToMessage(message, dir, "chat", "req")
            assertEquals("Expose actual file bytes, never the attempted count", 2L,
                message.getJSONArray("annotations").getJSONObject(0).getLong("size_bytes"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun skipStillArchivesBytesAndResetCannotDuplicateReceivedData() {
        val dir = root()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), dir, "chat", "req")
            assertFalse(archive.markSupported())
            assertEquals(3L, archive.skip(3))
            try { archive.reset(); fail("Reset cannot replay paid wire bytes") } catch (_: IOException) {}
            archive.use { it.receiveRemaining() }
            assertArrayEquals(bytes, archive.file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
