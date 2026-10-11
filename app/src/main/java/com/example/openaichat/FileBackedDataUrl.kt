package com.example.openaichat

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** Immutable wire input: only StreamingJsonRequestBody may turn this into a JSON string. */
internal class FileBackedDataUrl(private val file: File, val mime: String) {
    val sourceBytes = file.length()
    val encodedChars = ((sourceBytes + 2L) / 3L) * 4L
    val jsonBytes = encodedChars + "data:$mime;base64,".length + 2L
    private val readPasses = AtomicLong()
    private val readBytes = AtomicLong()
    private val workNanos = AtomicLong()
    private val digest = hash()
    init {
        require(mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")))
    }
    private fun hash(): ByteArray {
        val started = System.nanoTime()
        readPasses.incrementAndGet()
        val sha = MessageDigest.getInstance("SHA-256")
        try { file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Attachment serialization cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                readBytes.addAndGet(count.toLong())
                sha.update(buffer, 0, count)
            }
        } } finally { workNanos.addAndGet(System.nanoTime() - started) }
        return sha.digest()
    }
    fun writeJson(out: Writer) {
        if (!file.isFile || file.length() != sourceBytes || !hash().contentEquals(digest)) {
            throw IOException("Attachment changed after preparation; original request was not sent")
        }
        out.write("\"data:$mime;base64,")
        val sha = MessageDigest.getInstance("SHA-256")
        val ascii = object : OutputStream() {
            override fun write(b: Int) { out.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(String(b, off, len, Charsets.US_ASCII))
            }
            override fun close() { /* The JSON writer belongs to the caller. */ }
        }
        val started = System.nanoTime()
        readPasses.incrementAndGet()
        try { Base64.getEncoder().wrap(ascii).use { encoded ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Attachment serialization cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    readBytes.addAndGet(count.toLong())
                    sha.update(buffer, 0, count); encoded.write(buffer, 0, count)
                }
            }
        } } finally { workNanos.addAndGet(System.nanoTime() - started) }
        if (!sha.digest().contentEquals(digest)) throw IOException("Attachment changed during serialization")
        out.write('"'.code)
    }
    fun ioStats() = FileBackedIoTelemetry.Snapshot(1, readPasses.get(), readBytes.get(), workNanos.get())
    override fun toString(): String = throw IllegalStateException("Use StreamingJsonRequestBody for file-backed wire data")
}
