package com.example.openaichat

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Counts HTTP response BODY octets, before UTF-8 decoding. Decoder-char limits
 * do not represent bytes: e.g. one supplementary code point is four wire bytes.
 *
 * The cap is explicit (never truncate to success). This is a transport safety
 * boundary, NOT proof that a paid oversized terminal payload was preserved.
 * Separately durable raw-spool handling is required for that acceptance case.
 */
class OpenAiSseWireByteLimitStream(
    input: InputStream,
    private val maxBytes: Long = MAX_BODY_BYTES
) : FilterInputStream(input) {
    companion object {
        const val MAX_BODY_BYTES = 512L * 1024L * 1024L
    }
    init { require(maxBytes > 0L) }

    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        // An additional octet after the limit means the body is oversized;
        // exact-sized bodies remain valid, including until EOF.
        val next = super.read()
        if (next < 0) return -1
        bytesRead++
        if (bytesRead > maxBytes)
            throw IOException("Responses SSE wire-byte limit exceeded: $maxBytes bytes")
        return next
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        // FilterInputStream.read(byte[],off,len) delegates to read() on some
        // implementations. Call the underlying input directly and count once.
        val remaining = maxBytes - bytesRead
        if (remaining <= 0L) {
            val extra = `in`.read()
            if (extra < 0) return -1
            bytesRead++
            throw IOException("Responses SSE wire-byte limit exceeded: $maxBytes bytes")
        }
        val wanted = minOf(len.toLong(), remaining).toInt()
        val n = `in`.read(b, off, wanted)
        if (n > 0) bytesRead += n
        return n
    }
}
