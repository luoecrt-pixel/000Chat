package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiReceivedSseReaderTest {
    @Test fun concurrentFramesHoldReservationsThroughCallbackAndReleaseThemForNextRead() {
        val runtime = Runtime.getRuntime()
        val headroom = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        fun source(characters: Int) = "data: {\"type\":\"response.completed\",\"text\":\"" +
            "x".repeat(characters) + "\"}\n\n"
        val heldSource = source((headroom / 8L / 32L).toInt())
        val largerSource = source((headroom / 3L / 32L).toInt())
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val held = (0 until 2).map {
            val result = CompletableFuture<OpenAiSseReadResult>()
            Thread {
                try {
                    result.complete(OpenAiSseStreamReader.read(heldSource.reader().buffered()) {
                        entered.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                        true
                    })
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }.apply { isDaemon = true; start() }
            result
        }
        val root = createTempDir(prefix = "received-concurrent-")
        try {
            assertTrue("Both actual parsed frames must reach their callbacks", entered.await(10, TimeUnit.SECONDS))
            val bytes = largerSource.toByteArray()
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "req")
            var callbacks = 0
            assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiSseUtf8Reader.open(archive).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive) { callbacks++; true }
                }
            }
            assertEquals(0, callbacks)
            assertArrayEquals(bytes, archive.file.readBytes())
            release.countDown()
            held.forEach { assertEquals(OpenAiStreamEnd.COMPLETED, it.get(10, TimeUnit.SECONDS).end) }
            val next = OpenAiSseStreamReader.read(largerSource.reader().buffered()) { callbacks++; true }
            assertEquals(OpenAiStreamEnd.COMPLETED, next.end)
            assertEquals(1, callbacks)
        } finally {
            release.countDown()
            held.forEach { it.get(10, TimeUnit.SECONDS) }
            root.deleteRecursively()
        }
    }

    @Test fun allocationLimitDrainsBodyAndKeepsEarlierSignedEventWithoutDispatchingLargeTerminal() {
        val signed = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"encrypted_content\":\"exact-signed-bytes\"}}\n\n"
        val terminal = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "x".repeat(100_000) + "\"}]}]}}\n\n"
        val bytes = (signed + terminal).toByteArray()
        val root = createTempDir(prefix = "received-memory-")
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "req")
            val events = mutableListOf<JSONObject>()
            assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiSseUtf8Reader.open(archive).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive,
                        OpenAiSseStreamReader.Limits(maxAllocationBytes = 64 * 1024L)) { events += it; true }
                }
            }
            assertEquals(1, events.size)
            assertEquals("exact-signed-bytes", events.single().getJSONObject("item").getString("encrypted_content"))
            assertArrayEquals(bytes, archive.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun denseJsonCannotEvadeAllocationLimitWithShortStrings() {
        val bytes = ("data: {\"type\":\"response.completed\",\"response\":{\"output\":[" +
            List(10_000) { "{}" }.joinToString(",") + "]}}\n\n").toByteArray()
        assertTrue("The framing text alone fits this allocation budget", bytes.size * 32L < 2_000_000L)
        val root = createTempDir(prefix = "received-nodes-")
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "req")
            var callbacks = 0
            assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiSseUtf8Reader.open(archive).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive,
                        OpenAiSseStreamReader.Limits(maxAllocationBytes = 2_000_000L)) { callbacks++; true }
                }
            }
            assertEquals(0, callbacks)
            assertArrayEquals(bytes, archive.file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun quotedEscapedJsonDelimitersRemainActualText() {
        val text = "[{}]\"\\ ".repeat(3_000)
        val event = JSONObject().put("type", "response.completed").put("text", text)
        var captured = ""
        val result = OpenAiSseStreamReader.read(("data: $event\n\n").reader().buffered(),
            OpenAiSseStreamReader.Limits(maxAllocationBytes = 2_000_000L)) {
            captured = it.getString("text"); true
        }
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertEquals(text, captured)
    }

    @Test fun manySmallEventsReleaseReservationsAndDoNotCapGenerationLength() {
        val source = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\n\n".repeat(5_000) +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_ok\"}}\n\n"
        var callbacks = 0
        val result = OpenAiSseStreamReader.read(source.reader().buffered(),
            OpenAiSseStreamReader.Limits(maxAllocationBytes = 64 * 1024L)) { callbacks++; true }
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertEquals(5_001, callbacks)
    }

    @Test fun deepJsonIsRejectedBeforeRecursiveParserAndSourceIsRetained() {
        val bytes = ("data: {\"type\":\"response.completed\",\"nested\":" + "[".repeat(300) +
            "0" + "]".repeat(300) + "}\n\n").toByteArray()
        val root = createTempDir(prefix = "received-depth-")
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "req")
            var callbacks = 0
            assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiSseUtf8Reader.open(archive).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive) { callbacks++; true }
                }
            }
            assertEquals(0, callbacks)
            assertArrayEquals(bytes, archive.file.readBytes())
        } finally { root.deleteRecursively() }
    }

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
