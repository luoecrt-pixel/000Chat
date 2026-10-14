package com.example.openaichat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.nio.charset.MalformedInputException
import java.util.zip.GZIPOutputStream

class BoundedResponseDecoderTest {
    @Test fun strictUtf8PreservesSplitCodepointsForSseAndNonSse() {
        val text = "привет 😀"
        fun network(source: String) = object : ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val events = mutableListOf<String>()
        BoundedResponseDecoder.openSse(network("data: $text\r\n\r\n")).use { reader ->
            BoundedResponseDecoder.readSse(reader) { events += it.data; false }
        }
        assertEquals(listOf(text), events)
        assertEquals(text, BoundedResponseDecoder.readBody(network(text),
            BoundedResponseDecoder.BodyLimits(maxBytes = text.toByteArray().size.toLong(), maxChars = text.length)))
    }

    @Test fun incompleteUtf8NeverBecomesReplacementCharacters() {
        val invalid = byteArrayOf('a'.code.toByte(), 0xF0.toByte(), 0x9F.toByte())
        assertThrows(MalformedInputException::class.java) {
            BoundedResponseDecoder.readBody(ByteArrayInputStream(invalid), BoundedResponseDecoder.BodyLimits())
        }
        val diagnostic = BoundedResponseDecoder.readErrorBody(invalid.toResponseBody())
        assertTrue(diagnostic.contains("MalformedInputException"))
        assertFalse(diagnostic.contains('\uFFFD'))
    }

    @Test fun multilineFramesCommentsAndFinalUnterminatedFrameSurvive() {
        val events = mutableListOf<SseEvent>()
        BoundedResponseDecoder.readSse(BufferedReader(StringReader(
            ": heartbeat\n\nevent: token\nid: owned\ndata: {\ndata: \"text\":\"yes\"}\n\ndata: tail"
        ))) { events += it; false }
        assertEquals(listOf("{\n\"text\":\"yes\"}", "tail"), events.map { it.data })
        assertEquals("token", events[0].event)
        assertEquals("owned", events[1].id)
    }

    @Test fun stoppingCallbackNeverReadsPastItsRecord() {
        val source = "data: terminal\n\n"
        val reader = object : BufferedReader(StringReader("")) {
            var reads = 0
            override fun read(): Int {
                check(reads < source.length) { "Read after accepted terminal" }
                return source[reads++].code
            }
        }
        var callbacks = 0
        BoundedResponseDecoder.readSse(reader) { callbacks++; true }
        assertEquals(1, callbacks)
        assertEquals(source.length, reader.reads)
    }

    @Test fun ownershipCheckRunsForCommentAndHeartbeatLinesBeforeDispatch() {
        var lines = 0
        var records = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            BoundedResponseDecoder.readSse(BufferedReader(StringReader(
                ": heartbeat\n\ndata: later\n\n"
            )), onLine = {
                if (++lines == 2) throw java.util.concurrent.CancellationException("replaced owner")
            }) { records++; false }
        }
        assertEquals(2, lines)
        assertEquals(0, records)
    }

    @Test fun lineEventStreamAndByteLimitsFailWithSpecificReasons() {
        val cases = listOf(
            Triple("data: " + "x".repeat(25) + "\n\n", limits(line = 20), "line limit"),
            Triple("data: " + "x".repeat(12) + "\ndata: " + "y".repeat(12) + "\n\n",
                limits(line = 20, event = 30), "event limit"),
            Triple("data: x\n\n".repeat(5), limits(line = 20, event = 30, stream = 35), "stream limit"),
            Triple("data: 😀\n\n", limits(bytes = 11), "byte limit")
        )
        for ((source, limit, expected) in cases) {
            val failure = assertThrows(ResponsesSseLimitException::class.java) {
                BoundedResponseDecoder.openSse(ByteArrayInputStream(source.toByteArray()), limit).use { reader ->
                    BoundedResponseDecoder.readSse(reader, limit) { false }
                }
            }
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(expected))
        }
    }

    @Test fun nonSseBodyLimitsAndJsonDepthAreCheckedBeforeSuccessfulDecode() {
        val byteFailure = assertThrows(ResponsesSseLimitException::class.java) {
            BoundedResponseDecoder.readBody(ByteArrayInputStream("😀".toByteArray()),
                BoundedResponseDecoder.BodyLimits(maxBytes = 3, maxChars = 10))
        }
        assertTrue(byteFailure.message.orEmpty().contains("byte limit"))
        val charFailure = assertThrows(ResponsesSseLimitException::class.java) {
            BoundedResponseDecoder.readBody(ByteArrayInputStream("abc".toByteArray()),
                BoundedResponseDecoder.BodyLimits(maxBytes = 10, maxChars = 2))
        }
        assertTrue(charFailure.message.orEmpty().contains("character limit"))
        val depthFailure = assertThrows(ResponsesSseLimitException::class.java) {
            BoundedResponseDecoder.readBody(ByteArrayInputStream(("[".repeat(129) + "0" + "]".repeat(129)).toByteArray()),
                BoundedResponseDecoder.BodyLimits())
        }
        assertTrue(depthFailure.message.orEmpty().contains("JSON"))
    }

    @Test fun errorBodyLimitRetainsDiagnosticReasonAndOrdinaryJson() {
        val raw = """{"error":{"message":"denied"}}"""
        assertEquals(raw, BoundedResponseDecoder.readErrorBody(raw.toResponseBody("application/json".toMediaType())))
        val diagnostic = BoundedResponseDecoder.readErrorBody("x".repeat(20).toResponseBody(),
            BoundedResponseDecoder.BodyLimits(maxBytes = 10, maxChars = 10))
        assertTrue(diagnostic.contains("HTTP error body decode failed"))
        assertTrue(diagnostic.contains("byte limit"))
        assertFalse(diagnostic.endsWith("xxxxxxxxxx"))
    }

    @Test fun transparentGzipIsLimitedByDecodedBytesForErrorsAndSse() {
        val server = MockWebServer()
        server.start()
        try {
            fun compressed(text: String): Buffer {
                val bytes = java.io.ByteArrayOutputStream()
                GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
                return Buffer().write(bytes.toByteArray())
            }
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Encoding", "gzip")
                .setBody(compressed("x".repeat(1000))))
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip")
                .setBody(compressed("data: " + "x".repeat(1000) + "\n\n")))
            val client = OkHttpClient()
            client.newCall(Request.Builder().url(server.url("/error")).build()).execute().use { response ->
                assertEquals(429, response.code)
                assertTrue(BoundedResponseDecoder.readErrorBody(response.body,
                    BoundedResponseDecoder.BodyLimits(maxBytes = 100, maxChars = 200)).contains("byte limit"))
            }
            client.newCall(Request.Builder().url(server.url("/stream")).build()).execute().use { response ->
                val failure = assertThrows(ResponsesSseLimitException::class.java) {
                    val limit = limits(line = 2000, event = 2500, stream = 3000, bytes = 100)
                    BoundedResponseDecoder.openSse(response.body!!.byteStream(), limit).use { reader ->
                        BoundedResponseDecoder.readSse(reader, limit) { false }
                    }
                }
                assertTrue(failure.message.orEmpty().contains("byte limit"))
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun limits(line: Int = 128, event: Int = 256, stream: Long = 1024, bytes: Long = 2048) =
        BoundedResponseDecoder.SseLimits(maxLineChars = line, maxEventChars = event,
            maxStreamChars = stream, maxBodyBytes = bytes)
}
