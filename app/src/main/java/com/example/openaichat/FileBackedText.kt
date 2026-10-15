package com.example.openaichat

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Writer
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** A complete normalized UTF-8 document, escaped directly into the JSON wire stream. */
internal class FileBackedText(private val file: File, private val prefix: String = "", private val suffix: String = "") {
    private val sourceBytes = file.length()
    private val readPasses = AtomicLong()
    private val readBytes = AtomicLong()
    private val workNanos = AtomicLong()
    private val digest: ByteArray
    val jsonBytes: Long
    init {
        val sha = MessageDigest.getInstance("SHA-256")
        var extra = 0L
        scan(sha) { chars, n ->
            for (i in 0 until n) extra += when (val c = chars[i]) {
                '"', '\\', '\b', '\u000C', '\n', '\r', '\t' -> 1L
                else -> if (c < ' ') 5L else 0L
            }
        }
        digest = sha.digest()
        jsonBytes = sourceBytes + extra + StreamingJsonRequestBody.countBytes(prefix) + StreamingJsonRequestBody.countBytes(suffix) - 2L
    }
    private fun scan(sha: MessageDigest, consume: (CharArray, Int) -> Unit) {
        val started = System.nanoTime()
        readPasses.incrementAndGet()
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(DigestInputStream(file.inputStream().buffered(), sha), decoder).use { reader ->
                val chars = CharArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Text serialization cancelled")
                    val n = reader.read(chars); if (n < 0) break
                    consume(chars, n)
                }
            }
            readBytes.addAndGet(sourceBytes)
        } finally {
            workNanos.addAndGet(System.nanoTime() - started)
        }
    }

    fun writeJson(out: Writer) {
        if (!file.isFile || file.length() != sourceBytes) throw IOException("Text attachment changed after preparation")
        out.write('"'.code)
        StreamingJsonRequestBody.writeQuotedContent(prefix, out)
        val sha = MessageDigest.getInstance("SHA-256")
        scan(sha) { chars, n -> StreamingJsonRequestBody.writeQuotedContent(String(chars, 0, n), out) }
        if (file.length() != sourceBytes || !sha.digest().contentEquals(digest)) {
            throw IOException("Text attachment changed after preparation")
        }
        StreamingJsonRequestBody.writeQuotedContent(suffix, out)
        out.write('"'.code)
    }
    fun ioStats() = FileBackedIoTelemetry.Snapshot(1, readPasses.get(), readBytes.get(), workNanos.get())
    override fun toString(): String = throw IllegalStateException("Use StreamingJsonRequestBody for file-backed text")
}
