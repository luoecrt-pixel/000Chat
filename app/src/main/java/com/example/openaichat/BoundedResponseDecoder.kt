package com.example.openaichat

import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * One decoding boundary for Responses and Chat Completions. Budgets apply to
 * decoded HTTP bodies (including OkHttp's transparent gzip decoding), before
 * a line, JSON tree or whole non-streaming body can be allocated unboundedly.
 * A limit is an explicit failure; model output is never truncated to success.
 */
object BoundedResponseDecoder {
    data class SseLimits(
        val maxLineChars: Int = 128 * 1024 * 1024,
        val maxEventChars: Int = 160 * 1024 * 1024,
        val maxStreamChars: Long = 512L * 1024 * 1024,
        val maxBodyBytes: Long = 512L * 1024 * 1024,
        val maxAllocationBytes: Long = Long.MAX_VALUE,
        val maxSharedAllocationBytes: Long = Long.MAX_VALUE
    ) {
        init {
            require(maxLineChars > 0 && maxEventChars >= maxLineChars && maxStreamChars >= maxEventChars)
            require(maxBodyBytes > 0L && maxAllocationBytes > 0L && maxSharedAllocationBytes > 0L)
        }
    }

    data class BodyLimits(
        val maxBytes: Long = 32L * 1024 * 1024,
        val maxChars: Int = 32 * 1024 * 1024,
        val maxAllocationBytes: Long = Long.MAX_VALUE
    ) {
        init { require(maxBytes > 0L && maxChars > 0 && maxAllocationBytes > 0L) }
    }

    val ERROR_BODY_LIMITS = BodyLimits(maxBytes = 1024L * 1024, maxChars = 1024 * 1024)

    fun openUtf8(input: InputStream): BufferedReader = BufferedReader(InputStreamReader(
        input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    ))

    fun openSse(input: InputStream, limits: SseLimits = SseLimits()): BufferedReader =
        openUtf8(ByteLimitStream(input, limits.maxBodyBytes, "SSE"))

    /** The callback returns true to stop immediately, without reading a later record. */
    fun readSse(
        reader: BufferedReader,
        limits: SseLimits = SseLimits(),
        onLine: () -> Unit = {},
        onRecord: (SseEvent) -> Boolean
    ) {
        val parser = SseEventParser()
        var eventChars = 0L
        var streamChars = 0L
        var stopped = false
        var allocation = ResponsesSseMemoryBudget(limits.maxAllocationBytes, limits.maxSharedAllocationBytes)

        fun boundedLine(): String? {
            val line = StringBuilder()
            while (true) {
                val ch = reader.read()
                if (ch < 0) return if (line.isEmpty()) null else line.toString()
                val pending = line.length.toLong() + 1L
                if (streamChars + pending > limits.maxStreamChars)
                    throw ResponsesSseLimitException("SSE stream limit exceeded: ${limits.maxStreamChars} characters")
                if (eventChars + pending > limits.maxEventChars)
                    throw ResponsesSseLimitException("SSE event limit exceeded: ${limits.maxEventChars} characters")
                if (ch == '\n'.code) return line.toString()
                if (line.length >= limits.maxLineChars)
                    throw ResponsesSseLimitException("SSE line limit exceeded: ${limits.maxLineChars} characters")
                allocation.framingCharacters(eventChars + pending)
                line.append(ch.toChar())
            }
        }

        fun consume(frame: SseEvent): Boolean {
            if (frame.data != "[DONE]") allocation.json(frame.data)
            return onRecord(frame)
        }

        try {
            while (true) {
                val line = boundedLine() ?: break
                onLine()
                streamChars += line.length.toLong() + 1L
                eventChars += line.length.toLong() + 1L
                val frame = parser.acceptLine(line)
                if (frame != null && consume(frame)) { stopped = true; break }
                // Blank heartbeats are complete records too. Release their
                // reservation even when they contained no data fields.
                if (line.removeSuffix("\r").isEmpty()) {
                    eventChars = 0L
                    allocation.close()
                    allocation = ResponsesSseMemoryBudget(limits.maxAllocationBytes, limits.maxSharedAllocationBytes)
                }
            }
            if (!stopped) parser.finish()?.let { consume(it) }
        } finally {
            allocation.close()
        }
    }

    fun readBody(body: ResponseBody?, limits: BodyLimits = BodyLimits()): String {
        if (body == null) return ""
        return readBody(body.byteStream(), limits)
    }

    internal fun readBody(input: InputStream, limits: BodyLimits): String {
        val text = StringBuilder()
        ResponsesSseMemoryBudget(limits.maxAllocationBytes).use { allocation ->
            openUtf8(ByteLimitStream(input, limits.maxBytes, "HTTP body")).use { reader ->
                while (true) {
                    val ch = reader.read()
                    if (ch < 0) break
                    if (text.length >= limits.maxChars)
                        throw ResponsesSseLimitException("HTTP body character limit exceeded: ${limits.maxChars} characters")
                    allocation.framingCharacters(text.length.toLong() + 1L)
                    text.append(ch.toChar())
                }
            }
            // The callers parse metadata/error JSON only after this reservation
            // has accounted for nesting and object overhead.
            val result = text.toString()
            allocation.json(result)
            return result
        }
    }

    /** Keep the HTTP status usable by recovery even if its diagnostic body is invalid. */
    fun readErrorBody(
        body: ResponseBody?,
        limits: BodyLimits = ERROR_BODY_LIMITS,
        onDecodeFailure: (IOException) -> Unit = {}
    ): String = try {
        readBody(body, limits)
    } catch (failure: IOException) {
        try { onDecodeFailure(failure) } catch (_: Exception) { /* Diagnostics cannot replace the HTTP status. */ }
        "HTTP error body decode failed (${failure.javaClass.simpleName}): ${failure.message.orEmpty()}"
    }

    private class ByteLimitStream(input: InputStream, private val maxBytes: Long, private val label: String) :
        FilterInputStream(input) {
        private var bytesRead = 0L
        override fun read(): Int {
            val next = `in`.read()
            if (next >= 0 && ++bytesRead > maxBytes) fail()
            return next
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            val remaining = maxBytes - bytesRead
            if (remaining <= 0L) {
                if (`in`.read() < 0) return -1
                fail()
            }
            val count = `in`.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (count > 0) bytesRead += count
            return count
        }

        private fun fail(): Nothing = throw ResponsesSseLimitException("$label byte limit exceeded: $maxBytes bytes")
    }
}
