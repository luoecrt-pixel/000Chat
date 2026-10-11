package com.example.openaichat

import android.util.AtomicFile
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Private UTF-16 disk snapshot: O(page) seek, with conditional HTTP validation on every call. */
internal class GitHubTextPageCache(private val directory: File, private val cacheBudgetBytes: Long = MAX_CACHE_BYTES) {
    companion object {
        private val maintenance = Any()
        private const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
        private const val MAX_CACHE_BYTES = 220L * 1024 * 1024
        private const val RESERVATION_STEP = 1024L * 1024
        private val reservations = mutableMapOf<String, Long>()
    }
    fun read(key: String, offset: Long, limit: Int, expected: String?, active: () -> Boolean,
             fetch: (etag: String?, save: (InputStreamReader, String) -> Unit) -> String): JSONObject =
        KeyedFileLocks.with(directory.absolutePath + ":" + StreamingJsonRequestBody.sha256(key), active) {
        require(offset >= 0 && offset <= Long.MAX_VALUE / 2) { "Invalid character offset" }
        require(limit in 2..BoundedTextRead.MAX_CHARS)
        if (offset > 0 && expected.isNullOrBlank()) throw IOException("Later pages require the first page's ETag; restart from offset 0")
        if (!active()) throw IOException("File read cancelled")
        directory.mkdirs()
        val id = StreamingJsonRequestBody.sha256(key)
        val abandoned = File(directory, "$id.partial")
        synchronized(maintenance) {
            // Our file lock excludes a live writer for this key; a leftover is from failure/death.
            if (abandoned.isFile && !abandoned.delete()) throw IOException("Could not release abandoned repository snapshot")
            reservations.remove(abandoned.absolutePath)
        }
        reserve(abandoned, 0L, active) // also enforce the budget on old caches and HTTP 304 paths
        val meta = File(directory, "$id.json")
        val atomic = AtomicFile(meta)
        val stored = try { JSONObject(String(atomic.readFully(), Charsets.UTF_8)) } catch (_: Exception) { null }
        val filename = stored?.optString("data_file")?.takeIf { it.matches(Regex("$id\\.[0-9a-f]{64}\\.utf16")) }
        var data = File(directory, filename ?: "$id.missing.utf16")
        val cached = stored?.takeIf { filename != null && data.isFile }
        val cachedTag = cached?.optString("etag")?.takeIf { it.isNotBlank() }
        if (expected != null && cachedTag != null && expected != cachedTag) throw IOException("File version differs; restart from offset 0")
        var replaced = false
        val actual = fetch(cachedTag) save@ { reader, etag ->
            if (expected != null && etag != expected) throw IOException("Repository file changed between pages; restart from offset 0")
            if (etag.isBlank()) throw IOException("GitHub returned no ETag; consistent range reading is unavailable")
            val temp = File(directory, "$id.partial")
            val sameVersion = cachedTag == etag && data.isFile
            if (!sameVersion) synchronized(maintenance) {
                // A stale snapshot is a disposable cache, not the repository source. Free it
                // before downloading its replacement, so two 200 MiB copies never coexist.
                if (data.isFile && !data.delete()) throw IOException("Could not release old repository snapshot")
                atomic.delete()
                if (temp.isFile && !temp.delete()) throw IOException("Could not release partial repository snapshot")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var chars = 0L
            try {
                val snapshotStream = if (sameVersion) null else DataOutputStream(BufferedOutputStream(temp.outputStream()))
                snapshotStream.use { out ->
                    val buffer = CharArray(4096)
                    while (true) {
                        if (!active()) throw IOException("File read cancelled")
                        val count = reader.read(buffer); if (count < 0) break
                        chars += count
                        // Maximum decoded UTF-16 storage, independent of the number of pages.
                        if (chars * 2L > MAX_SOURCE_BYTES * 2L) throw IOException("GitHub text exceeds disk safety budget; content was not truncated")
                        if (out != null) reserve(temp, chars * 2L, active)
                        for (i in 0 until count) {
                            val c = buffer[i]
                            if (c < ' ' && c !in charArrayOf('\t', '\n', '\r', '\u000c', '\u001b')) throw IOException("Binary repository file is not supported")
                            out?.writeChar(c.code)
                            digest.update((c.code ushr 8).toByte()); digest.update(c.code.toByte())
                        }
                    }
                }
                if (!active()) throw IOException("File read cancelled")
                val installed = File(directory, "$id." + StreamingJsonRequestBody.sha256(etag) + ".utf16")
                if (sameVersion) {
                    // Same ETag must denote exactly the same representation.
                    val expectedDigest = cached?.getString("snapshot_sha256")
                    val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expectedDigest != actualDigest) throw IOException("GitHub reused an ETag for changed bytes")
                    temp.delete(); replaced = false
                    return@save
                }
                synchronized(maintenance) {
                    if (!temp.renameTo(installed)) throw IOException("Could not install repository snapshot")
                    reservations.remove(temp.absolutePath)
                }
                val metadata = JSONObject().put("etag", etag).put("chars", chars)
                    .put("data_file", installed.name)
                    .put("snapshot_sha256", digest.digest().joinToString("") { "%02x".format(it) })
                val output = atomic.startWrite()
                try {
                    output.write(metadata.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
                } catch (e: Exception) { atomic.failWrite(output); installed.delete(); throw e }
                if (data != installed) data.delete()
                data = installed
                replaced = true
            } finally { synchronized(maintenance) { temp.delete(); reservations.remove(temp.absolutePath) } }
        }
        if (expected != null && actual != expected) throw IOException("Repository file changed between pages; restart from offset 0")
        if (!replaced && (cachedTag == null || actual != cachedTag || !data.isFile)) throw IOException("Invalid conditional repository response")
        val metadata = if (replaced) JSONObject(String(atomic.readFully(), Charsets.UTF_8)) else cached ?: throw IOException("Missing cached snapshot")
        val chars = metadata.getLong("chars")
        if (data.length() != chars * 2L) { data.delete(); atomic.delete(); throw IOException("Incomplete repository snapshot; restart reading") }
        data.setLastModified(System.currentTimeMillis())
        RandomAccessFile(data, "r").use { input ->
            val start = minOf(offset, chars)
            input.seek(start * 2L)
            val reader = object : Reader() {
                private val bytes = ByteArray(8192)
                override fun read(buffer: CharArray, off: Int, len: Int): Int {
                    val available = (input.length() - input.filePointer) / 2L
                    if (available == 0L) return -1
                    val count = minOf(available, len.toLong(), bytes.size.toLong() / 2).toInt()
                    input.readFully(bytes, 0, count * 2)
                    for (i in 0 until count) buffer[off + i] = (((bytes[i * 2].toInt() and 255) shl 8) or (bytes[i * 2 + 1].toInt() and 255)).toChar()
                    return count
                }
                override fun close() {}
            }
            BoundedTextRead.range(reader, 0L, limit, active).apply {
                put("offset", offset); put("next_offset", start + getLong("next_offset"))
                if (offset > chars) put("offset_past_eof", true)
                put("etag", actual); put("snapshot_sha256", metadata.getString("snapshot_sha256"))
            }
        }
    }
    /** Reserve bounded chunks before writing; includes other in-flight/abandoned .partial files. */
    private fun reserve(temp: File, bytes: Long, active: () -> Boolean) = synchronized(maintenance) {
        require(cacheBudgetBytes > 0)
        if (bytes > 0L && (reservations[temp.absolutePath] ?: 0L) >= bytes) return@synchronized
        if (!active()) throw IOException("File read cancelled")
        val target = minOf(cacheBudgetBytes, ((bytes + RESERVATION_STEP - 1L) / RESERVATION_STEP) * RESERVATION_STEP)
        if (target < bytes) throw IOException("Repository snapshot exceeds cache disk budget; content was not truncated")
        fun total(): Long = directory.listFiles().orEmpty().filter { it.extension in setOf("utf16", "partial") }.sumOf {
            if (it == temp) target else maxOf(it.length(), reservations[it.absolutePath] ?: 0L)
        }
        val candidates = directory.listFiles().orEmpty().filter {
            it != temp && it.extension in setOf("utf16", "partial")
        }.sortedBy { it.lastModified() }
        for (old in candidates) {
            if (total() <= cacheBudgetBytes) break
            if (!active()) throw IOException("File read cancelled")
            KeyedFileLocks.ifIdle(directory.absolutePath + ":" + old.name.substringBefore('.')) {
                if (old.delete()) {
                    reservations.remove(old.absolutePath)
                    if (old.extension == "utf16") {
                        val oldMeta = AtomicFile(File(directory, old.name.substringBefore('.') + ".json"))
                        try { if (JSONObject(String(oldMeta.readFully(), Charsets.UTF_8)).optString("data_file") == old.name) oldMeta.delete() } catch (_: Exception) { }
                    }
                }
            }
        }
        if (total() > cacheBudgetBytes) throw IOException("Repository cache disk budget is busy; retry after other file reads finish")
        if (target > 0L) reservations[temp.absolutePath] = target
    }
    fun decoder() = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
}
