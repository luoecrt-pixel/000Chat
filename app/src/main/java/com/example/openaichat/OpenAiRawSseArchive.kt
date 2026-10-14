package com.example.openaichat

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private raw-byte safety net for a Responses SSE transport.
 *
 * Write BEFORE the UTF-8 decoder, frame parser, JSON allocation and wire-byte
 * budget. A malformed/oversized terminal frame or a process interruption must
 * not silently discard the exact HTTP payload already received.
 *
 * Each connection owns a separate file. We never truncate another connection
 * when Activity and Worker overlap and never store API keys. Existing files are
 * only removed once the owned complete result has been committed with all
 * generated artifacts; failed/incomplete results retain received bytes.
 *
 * This does NOT promise that bytes not yet delivered by the server are
 * recoverable, or that a device without disk space can record them.
 */
class OpenAiRawSseArchive private constructor(
    source: InputStream,
    val file: File,
    private val sink: Sink
) : FilterInputStream(source) {
    internal interface Sink {
        fun write(bytes: ByteArray, offset: Int, length: Int)
        fun sync()
        fun close()
    }

    private class FileSink(file: File) : Sink {
        private val output = FileOutputStream(file, true)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun sync() = output.fd.sync()
        override fun close() = output.close()
    }

    companion object {
        private const val ROOT = "openai_received_sse"
        private const val SYNC_EVERY = 1024L * 1024L

        private fun owner(chatId: String, requestId: String): String {
            require(chatId.isNotBlank() && requestId.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((chatId + "\u0000" + requestId).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun begin(input: InputStream, filesDir: File, chatId: String, requestId: String): OpenAiRawSseArchive =
            begin(input, filesDir, chatId, requestId) { FileSink(it) }

        internal fun begin(
            input: InputStream, filesDir: File, chatId: String, requestId: String,
            sinkFactory: (File) -> Sink
        ): OpenAiRawSseArchive {
            val dir = File(filesDir, ROOT)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory)
                throw ResponsesArchiveStorageException(IOException("Cannot create Responses archive directory"))
            val filename = owner(chatId, requestId) + "_" + UUID.randomUUID() + ".sse"
            val file = File(dir, filename)
            // CREATE_NEW semantics: never replace another paid connection.
            try {
                if (!file.createNewFile()) throw IOException("Responses SSE archive filename collision")
            } catch (error: IOException) {
                throw ResponsesArchiveStorageException(error)
            }
            return try {
                OpenAiRawSseArchive(input, file, sinkFactory(file))
            } catch (error: Exception) {
                file.delete()
                throw ResponsesArchiveStorageException(error)
            }
        }

        fun archivesFor(filesDir: File, chatId: String, requestId: String): List<File> {
            val prefix = owner(chatId, requestId) + "_"
            val root = File(filesDir, ROOT).canonicalFile
            return root.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".sse") &&
                    it.canonicalFile.parentFile == root }
                ?.sortedBy { it.name }.orEmpty()
        }

        /** Local file cards expose received source without sending it to a provider. */
        fun attachToMessage(message: JSONObject, filesDir: File, chatId: String, requestId: String) {
            val files = archivesFor(filesDir, chatId, requestId).filter { it.length() > 0L }
            if (files.isEmpty()) return
            val annotations = message.optJSONArray("annotations") ?: JSONArray()
            val present = (0 until annotations.length()).mapNotNull {
                annotations.optJSONObject(it)?.optString("local_path")
            }.toMutableSet()
            files.forEach { file ->
                if (present.add(file.absolutePath)) {
                    annotations.put(JSONObject().put("type", "local_file")
                        .put("file_id", "local_received_" + file.name.removeSuffix(".sse"))
                        .put("filename", "response-source-" + file.name.substringAfter('_'))
                        .put("local_path", file.absolutePath)
                        .put("mime_type", "application/octet-stream")
                        .put("size_bytes", file.length()).put("state", "READY")
                        .put("received_source", true))
                }
            }
            message.put("annotations", annotations)
        }
    }

    /**
     * Delete only THIS completed connection's archive, and only after close()
     * has fsynced it. Another Activity/Worker stream for the same request may
     * have received different paid bytes that are not part of this commit.
     */
    fun deleteAfterDurableCommit(): Boolean {
        if (!durablyClosed) return false
        return !file.exists() || file.delete()
    }

    private var sinceSync = 0L
    private var closed = false
    private var durablyClosed = false
    private var storageFailure: ResponsesArchiveStorageException? = null
    var archivedBytes: Long = 0L
        private set

    private fun record(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            sink.write(bytes, offset, length)
            archivedBytes += length
            sinceSync += length
            if (sinceSync >= SYNC_EVERY) {
                sink.sync()
                sinceSync = 0L
            }
        } catch (error: IOException) {
            // Never consume further upstream data after local storage failed.
            // A partial file remains on disk for honest diagnostics/recovery.
            val failure = ResponsesArchiveStorageException(error)
            storageFailure = failure
            throw failure
        }
    }

    private fun ensureReadable() {
        storageFailure?.let { throw it }
        if (closed) throw IOException("Responses archive is closed")
    }

    override fun read(): Int {
        ensureReadable()
        val b = `in`.read()
        if (b >= 0) record(byteArrayOf(b.toByte()), 0, 1)
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        ensureReadable()
        if (length == 0) return 0
        val n = `in`.read(buffer, offset, length)
        if (n > 0) record(buffer, offset, n)
        return n
    }

    /** Bypasses only interpretation budgets; remains on the original owned Call. */
    fun receiveRemaining() {
        val buffer = ByteArray(64 * 1024)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }

    override fun skip(count: Long): Long {
        if (count <= 0L) return 0L
        val buffer = ByteArray(minOf(count, 64L * 1024L).toInt())
        var skipped = 0L
        while (skipped < count) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (n < 0) break
            skipped += n
        }
        return skipped
    }

    override fun markSupported(): Boolean = false
    override fun mark(readlimit: Int) = Unit
    override fun reset(): Unit = throw IOException("Responses archive does not support reset")

    override fun close() {
        if (closed) return
        closed = true
        var failure: IOException? = storageFailure
        fun closePart(storage: Boolean = true, action: () -> Unit) {
            try { action() } catch (error: IOException) {
                val reported = if (storage) ResponsesArchiveStorageException(error) else error
                if (failure == null) failure = reported
                else if (failure !== reported) failure!!.addSuppressed(reported)
            }
        }
        closePart { sink.sync() }
        closePart { sink.close() }
        // Closing the upstream socket can fail independently of local storage.
        // Keep it retryable unless a write/sync/close of the archive also failed.
        closePart(storage = false) { super.close() }
        durablyClosed = failure == null
        failure?.let { throw it }
    }
}
