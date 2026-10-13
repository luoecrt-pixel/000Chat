package com.example.openaichat

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

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
    val file: File
) : FilterInputStream(source) {
    companion object {
        private const val ROOT = "openai_received_sse"
        private const val SYNC_EVERY = 1024L * 1024L

        private fun owner(chatId: String, requestId: String): String {
            require(chatId.isNotBlank() && requestId.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((chatId + "\u0000" + requestId).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun begin(input: InputStream, filesDir: File, chatId: String, requestId: String): OpenAiRawSseArchive {
            val dir = File(filesDir, ROOT)
            if (!dir.isDirectory && !dir.mkdirs())
                throw IOException("Cannot create app-private Responses raw SSE archive")
            val filename = owner(chatId, requestId) + "_" + UUID.randomUUID() + ".sse"
            val file = File(dir, filename)
            // CREATE_NEW semantics: never replace another paid connection.
            if (!file.createNewFile()) throw IOException("Responses SSE archive filename collision")
            return try {
                OpenAiRawSseArchive(input, file)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

        fun archivesFor(filesDir: File, chatId: String, requestId: String): List<File> {
            val prefix = owner(chatId, requestId) + "_"
            return File(filesDir, ROOT).listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".sse") }
                ?.sortedBy { it.name }.orEmpty()
        }

        fun deleteAfterDurableCommit(filesDir: File, chatId: String, requestId: String) {
            archivesFor(filesDir, chatId, requestId).forEach { it.delete() }
        }
    }

    private val sink = FileOutputStream(file, true)
    private var sinceSync = 0L
    private var closed = false
    var archivedBytes: Long = 0L
        private set

    private fun record(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            sink.write(bytes, offset, length)
            archivedBytes += length
            sinceSync += length
            if (sinceSync >= SYNC_EVERY) {
                sink.fd.sync()
                sinceSync = 0L
            }
        } catch (error: IOException) {
            // Never consume further upstream data after local storage failed.
            // A partial file remains on disk for honest diagnostics/recovery.
            throw IOException("Cannot durably archive received Responses SSE bytes", error)
        }
    }

    override fun read(): Int {
        val b = `in`.read()
        if (b >= 0) record(byteArrayOf(b.toByte()), 0, 1)
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = `in`.read(buffer, offset, length)
        if (n > 0) record(buffer, offset, n)
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            sink.fd.sync()
        } finally {
            try { sink.close() } finally { super.close() }
        }
    }
}
