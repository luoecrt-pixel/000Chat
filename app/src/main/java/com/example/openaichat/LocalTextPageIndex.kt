package com.example.openaichat

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Request-scoped sparse UTF-8 index: one scan, then at most 4097 chars before a page. */
internal class LocalTextPageIndex {
    private data class Point(val chars: Long, val bytes: Long)
    private data class Index(val size: Long, val modified: Long, val chars: Long, val points: List<Point>)
    private val indexes = mutableMapOf<String, Index>()
    private fun build(file: File, active: () -> Boolean): Index {
        val size = file.length(); val modified = file.lastModified()
        val points = mutableListOf(Point(0, 0))
        var bytes = 0L; var chars = 0L
        file.inputStream().buffered().use { input ->
            fun next(): Int = input.read().also { if (it < 0) throw IOException("Incomplete UTF-8 file"); bytes++ }
            while (true) {
                val start = bytes
                val first = input.read(); if (first < 0) break
                bytes++
                if (chars - points.last().chars >= 4096) {
                    if (!active()) throw IOException("File indexing cancelled")
                    points.add(Point(chars, start))
                }
                val length = when (first) { in 0..127 -> 1; in 0xC2..0xDF -> 2; in 0xE0..0xEF -> 3; in 0xF0..0xF4 -> 4; else -> throw IOException("Invalid UTF-8 file") }
                var code = first and when (length) { 1 -> 127; 2 -> 31; 3 -> 15; else -> 7 }
                repeat(length - 1) {
                    val byte = next(); if (byte !in 0x80..0xBF) throw IOException("Invalid UTF-8 continuation")
                    code = (code shl 6) or (byte and 63)
                }
                if ((length == 2 && code < 128) || (length == 3 && code < 2048) ||
                    (length == 4 && code < 65536) || code in 0xD800..0xDFFF || code > 0x10FFFF)
                    throw IOException("Invalid UTF-8 code point")
                if (code < 32 && code !in setOf(9, 10, 13, 12, 27)) throw IOException("Binary control character in text file")
                chars += if (length == 4) 2 else 1
            }
        }
        if (!active()) throw IOException("File indexing cancelled")
        if (bytes != size || file.length() != size || file.lastModified() != modified) throw IOException("Text file changed during indexing")
        return Index(size, modified, chars, points)
    }
    fun range(file: File, offset: Long, limit: Int, active: () -> Boolean): JSONObject {
        require(offset >= 0); require(limit in 2..BoundedTextRead.MAX_CHARS)
        if (!active()) throw IOException("File read cancelled")
        val index = indexes[file.path]?.takeIf { it.size == file.length() && it.modified == file.lastModified() }
            ?: build(file, active).also { indexes[file.path] = it }
        if (offset > index.chars) return JSONObject().put("content", "").put("offset", offset)
            .put("next_offset", index.chars).put("eof", true).put("offset_past_eof", true)
        val found = index.points.binarySearchBy(offset) { it.chars }
        val point = index.points[if (found >= 0) found else (-found - 2).coerceAtLeast(0)]
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        file.inputStream().use { stream ->
            stream.channel.position(point.bytes)
            InputStreamReader(stream, decoder).use { reader ->
                return BoundedTextRead.range(reader, offset - point.chars, limit, active).apply {
                    put("offset", offset); put("next_offset", point.chars + getLong("next_offset"))
                    put("index_preceding_chars", offset - point.chars)
                }
            }
        }
    }
}
