package com.example.openaichat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Freezes ordinary JSON bytes once, while keeping file-backed attachments streaming. */
class StreamingJsonRequestBody(private val value: JSONObject) : RequestBody() {
    private val materializedBytes: ByteArray? by lazy {
        if (containsFileBackedValue(value)) null
        else {
            val output = ByteArrayOutputStream()
            OutputStreamWriter(output, Charsets.UTF_8).use { writeJson(value, it) }
            output.toByteArray()
        }
    }
    private val jsonBytes by lazy { materializedBytes?.size?.toLong() ?: countBytes(value) }
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength(): Long = jsonBytes
    override fun writeTo(sink: BufferedSink) {
        materializedBytes?.let { sink.write(it); return }
        val writer = OutputStreamWriter(sink.outputStream(), Charsets.UTF_8)
        writeJson(value, writer)
        writer.flush() // The sink belongs to OkHttp.
    }

    companion object {
        private fun containsFileBackedValue(value: Any?): Boolean = when (value) {
            is FileBackedDataUrl, is FileBackedText -> true
            is JSONObject -> value.keys().asSequence().any { containsFileBackedValue(value.opt(it)) }
            is JSONArray -> (0 until value.length()).any { containsFileBackedValue(value.opt(it)) }
            else -> false
        }
        fun countBytes(value: Any?): Long {
            return when (value) {
                null, JSONObject.NULL -> 4L
                is FileBackedDataUrl -> value.jsonBytes // No disk I/O or Base64 just to measure length.
                is FileBackedText -> value.jsonBytes
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList()
                    2L + (keys.size - 1).coerceAtLeast(0) + keys.sumOf { quotedBytes(it) + 1L + countBytes(value.opt(it)) }
                }
                is JSONArray -> 2L + (value.length() - 1).coerceAtLeast(0) + (0 until value.length()).sumOf { countBytes(value.opt(it)) }
                is String -> quotedBytes(value)
                is Number -> JSONObject.numberToString(value).length.toLong()
                is Boolean -> if (value) 4L else 5L
                else -> throw IllegalArgumentException("Unsupported JSON value: ${value.javaClass.simpleName}")
            }
        }
        private fun quotedBytes(text: String): Long {
            var bytes = 2L
            var index = 0
            while (index < text.length) {
                val c = text[index++]
                bytes += when {
                    c in charArrayOf('"', '\\', '\b', '\u000c', '\n', '\r', '\t') -> 2
                    c < ' ' -> 6
                    c.code < 128 -> 1
                    c.code < 2048 -> 2
                    Character.isHighSurrogate(c) && index < text.length && Character.isLowSurrogate(text[index]) -> { index++; 4 }
                    Character.isSurrogate(c) -> 1 // OutputStreamWriter's default replacement is ASCII '?'.
                    else -> 3
                }
            }
            return bytes
        }
        fun sha256(value: Any?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val discard = object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }
            OutputStreamWriter(DigestOutputStream(discard, digest), Charsets.UTF_8).use { writeJson(value, it) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun quoted(text: String, out: Writer) {
            out.write('"'.code)
            writeQuotedContent(text, out)
            out.write('"'.code)
        }
        internal fun writeQuotedContent(text: String, out: Writer) {
            var start = 0
            text.forEachIndexed { index, c ->
                val escaped = when (c) {
                    '"' -> "\\\""; '\\' -> "\\\\"; '\b' -> "\\b"; '\u000C' -> "\\f"
                    '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
                    else -> if (c < ' ') "\\u%04x".format(c.code) else null
                }
                if (escaped != null) {
                    out.write(text, start, index - start)
                    out.write(escaped)
                    start = index + 1
                }
            }
            out.write(text, start, text.length - start)
        }
        fun writeJson(value: Any?, out: Writer) {
            when (value) {
                null, JSONObject.NULL -> out.write("null")
                is FileBackedDataUrl -> value.writeJson(out)
                is FileBackedText -> value.writeJson(out)
                is JSONObject -> {
                    out.write('{'.code)
                    // Stable ordering is also required for reproducible protocol fingerprints.
                    value.keys().asSequence().toList().sorted().forEachIndexed { i, key ->
                        if (i > 0) out.write(','.code)
                        quoted(key, out); out.write(':'.code); writeJson(value.opt(key), out)
                    }
                    out.write('}'.code)
                }
                is JSONArray -> {
                    out.write('['.code)
                    for (i in 0 until value.length()) {
                        if (i > 0) out.write(','.code)
                        writeJson(value.opt(i), out)
                    }
                    out.write(']'.code)
                }
                is String -> quoted(value, out)
                is Number -> out.write(JSONObject.numberToString(value))
                is Boolean -> out.write(value.toString())
                else -> throw IllegalArgumentException("Unsupported JSON value: ${value.javaClass.simpleName}")
            }
        }
    }
}
