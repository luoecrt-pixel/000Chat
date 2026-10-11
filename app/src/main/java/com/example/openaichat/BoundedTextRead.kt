package com.example.openaichat

import org.json.JSONObject
import java.io.Reader
import java.io.IOException

/** A per-call page, never a cap on how much of the file may be read in total. */
object BoundedTextRead {
    const val MAX_CHARS = 40_000
    fun range(reader: Reader, offset: Long, limit: Int, active: () -> Boolean = { true }): JSONObject {
        require(offset >= 0L) { "offset must be nonnegative (UTF-16 character offset)" }
        require(limit in 2..MAX_CHARS) { "limit must be between 2 and $MAX_CHARS" }
        val buffer = CharArray(4096)
        fun checkText(count: Int) {
            for (i in 0 until count) if (buffer[i] < ' ' && buffer[i] !in charArrayOf('\t', '\n', '\r', '\u000c', '\u001b'))
                throw IOException("Binary data is not supported by this text tool")
        }
        var skipped = 0L
        while (skipped < offset) {
            if (!active()) throw IOException("File read cancelled")
            val count = reader.read(buffer, 0, minOf(buffer.size.toLong(), offset - skipped).toInt())
            if (count < 0) return JSONObject().put("content", "").put("offset", offset)
                .put("next_offset", skipped).put("eof", true).put("offset_past_eof", true)
            checkText(count)
            skipped += count
        }
        val out = StringBuilder(limit)
        while (out.length < limit) {
            if (!active()) throw IOException("File read cancelled")
            val count = reader.read(buffer, 0, minOf(buffer.size, limit - out.length))
            if (count < 0) break
            checkText(count)
            out.append(buffer, 0, count)
        }
        // Do not return half of a Unicode surrogate pair. The next page re-reads it.
        if (out.isNotEmpty() && Character.isLowSurrogate(out.first())) {
            throw IOException("offset starts inside a Unicode character; use a returned next_offset")
        }
        val withheldCharacter = out.isNotEmpty() && Character.isHighSurrogate(out.last())
        if (withheldCharacter) out.setLength(out.length - 1)
        val next = offset + out.length
        val eof = !withheldCharacter && reader.read() < 0
        return JSONObject().put("content", out.toString()).put("offset", offset)
            .put("next_offset", next).put("eof", eof).put("offset_unit", "UTF-16 characters")
    }
}
