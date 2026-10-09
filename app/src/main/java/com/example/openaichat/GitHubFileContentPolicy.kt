package com.example.openaichat

import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * GitHub contents API does NOT guarantee base64 for large files (encoding=none).
 * Never convert missing/invalid bytes into a successful empty file.
 */
object GitHubFileContentPolicy {
    private const val MAX_DECODED_BYTES = 256 * 1024
    private const val MAX_RETURNED_CHARS = 40_000

    fun decode(path: String, item: JSONObject): JSONObject {
        if (item.optString("type") != "file") throw IOException(
            "GitHub path is not a file: $path; use list_directory for folders"
        )
        val encoding = item.optString("encoding")
        if (encoding != "base64") throw IOException(
            "GitHub file '$path' uses encoding '${encoding.ifBlank { "missing" }}'; " +
                "get_file supports only base64 text files up to $MAX_DECODED_BYTES bytes. " +
                "Use a smaller text file or the GitHub web interface."
        )
        if (!item.has("content") || item.isNull("content")) {
            throw IOException("GitHub response omitted file contents for '$path'")
        }
        val raw = item.getString("content").filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (raw.length > ((MAX_DECODED_BYTES + 2) / 3) * 4 + 4) {
            throw IOException("GitHub text file '$path' exceeds $MAX_DECODED_BYTES bytes")
        }
        val bytes = try { Base64.getDecoder().decode(raw) }
        catch (_: IllegalArgumentException) { throw IOException("Invalid base64 for GitHub file '$path'") }
        if (bytes.size > MAX_DECODED_BYTES) {
            throw IOException("GitHub text file '$path' exceeds $MAX_DECODED_BYTES bytes")
        }
        val statedSize = item.optLong("size", -1L)
        if (statedSize >= 0 && bytes.size.toLong() != statedSize) {
            throw IOException("GitHub file '$path' length does not match metadata")
        }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IOException("GitHub file '$path' is not valid UTF-8 text")
        }
        return JSONObject().put("ok", true).put("path", path)
            .put("sha", item.optString("sha"))
            .put("content", decoded.take(MAX_RETURNED_CHARS))
            .put("truncated", decoded.length > MAX_RETURNED_CHARS)
    }
}
